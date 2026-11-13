package com.travelassistant.server.application.agent.spi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置式 HTTP Provider 适配器：只做 HTTP 调用与契约校验，不关心对端语言。
 *
 * <p>用 JDK 内置 HttpClient 而非 RestTemplate，是为了把 {@code Redirect.NEVER} 显式写死：
 * T-02 禁止重定向，T-03 禁止 Set-Cookie。endpoint 的 M-08 判定由注册期 G0 负责；
 * G1 / G2 探针用本地桩时允许环回地址，正式配置一律 https 加 allowlist。
 *
 * <p>凭据只从 {@code authRef} 指向的环境变量读取，绝不进配置、日志或异常消息。
 */
public class HttpAgentAdapter implements AgentProvider {

  /** 运行期契约违规：整体丢弃响应，调用方据此切 fallback。 */
  public static class ContractViolationException extends RuntimeException {
    private final List<RuleFailure> failures;

    public ContractViolationException(List<RuleFailure> failures) {
      super("Agent 响应违反契约：" + (failures.isEmpty() ? "" : failures.get(0).code()));
      this.failures = List.copyOf(failures);
    }

    public List<RuleFailure> failures() {
      return failures;
    }
  }

  private static final int DEFAULT_TIMEOUT_MS = 4000;
  private static final int DEFAULT_MAX_BYTES = 65536;

  private final AgentDescriptor descriptor;
  private final URI endpoint;
  private final String authRef;
  private final int timeoutMs;
  private final int maxResponseBytes;
  private final ContractValidator validator;
  private final ContractRules rules;
  private final ObjectMapper json;
  private final HttpClient client;
  /** 运行期判定回执：传 null 表示一次合规响应，传原因码表示一次 BLOCK 违规。 */
  private final java.util.function.Consumer<String> runtimeSink;

  public HttpAgentAdapter(Map<String, Object> config, ContractValidator validator,
      ContractRules rules, ObjectMapper json) {
    this(config, validator, rules, json, null);
  }

  public HttpAgentAdapter(Map<String, Object> config, ContractValidator validator,
      ContractRules rules, ObjectMapper json, java.util.function.Consumer<String> runtimeSink) {
    this.validator = validator;
    this.rules = rules;
    this.json = json;
    this.runtimeSink = runtimeSink;
    this.endpoint = URI.create(String.valueOf(config.getOrDefault("endpoint", "https://agent.example.com/")));
    this.authRef = str(config, "authRef");
    this.timeoutMs = intOr(config.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
    this.maxResponseBytes = intOr(config.get("maxResponseBytes"), DEFAULT_MAX_BYTES);
    this.descriptor = new AgentDescriptor(
        String.valueOf(config.getOrDefault("id", "unknown.provider")),
        String.valueOf(config.getOrDefault("version", "0.0.0")),
        String.valueOf(config.getOrDefault("schemaVersion", rules.contractVersion())),
        stringSet(config.get("capabilities")),
        "HTTP",
        String.valueOf(config.getOrDefault("sideEffect", "NONE")),
        true,
        AgentDescriptor.ACTIVE,
        null);
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public AgentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public AgentResult invoke(AgentContext context, RunBudget budget) {
    long started = System.currentTimeMillis();
    Map<String, Object> request = requestBody(context);
    String payload;
    try {
      payload = json.writeValueAsString(request);
    } catch (IOException e) {
      throw new IllegalStateException("Agent 请求序列化失败", e);
    }

    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofMillis(Math.max(100, timeoutMs)))
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    String token = authRef == null ? null : System.getenv(authRef);
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", "Bearer " + token);
    }

    HttpResponse<InputStream> response;
    try {
      response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return AgentResult.refused(List.of(), List.of("外部 Agent 响应"), "外部 Agent 调用失败，已切兜底");
    }

    // 边读边计数：超过 maxResponseBytes 立即断流，绝不把整个响应先缓冲进内存（F6）。
    String body;
    try (InputStream in = response.body()) {
      if (response.statusCode() != 200) {
        return AgentResult.refused(List.of(), List.of("外部 Agent 响应"),
            "外部 Agent 返回非 200 状态，已切兜底");
      }
      if (response.headers().firstValue("Set-Cookie").isPresent()) {
        return AgentResult.refused(List.of(), List.of("外部 Agent 响应"),
            "外部 Agent 返回 Set-Cookie，违反 T-03");
      }
      body = readBounded(in, maxResponseBytes);
    } catch (IOException e) {
      return AgentResult.refused(List.of(), List.of("外部 Agent 响应"),
          "外部 Agent 响应读取失败，已切兜底");
    }
    if (body == null) {
      return AgentResult.refused(List.of(), List.of("外部 Agent 响应"),
          "外部 Agent 响应超出体积上限，已切兜底");
    }

    Map<String, Object> result;
    try {
      result = json.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (IOException e) {
      return AgentResult.refused(List.of(), List.of("外部 Agent 响应"),
          "外部 Agent 返回非法 JSON，已切兜底");
    }
    List<RuleFailure> failures = validator.validateResult(result);
    if (runtimeSink != null) {
      runtimeSink.accept(failures.isEmpty() ? null : failures.get(0).code());
    }
    if (!failures.isEmpty()) {
      throw new ContractViolationException(failures);
    }
    return toAgentResult(result, System.currentTimeMillis() - started);
  }

  private Map<String, Object> requestBody(AgentContext context) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", rules.contractVersion());
    body.put("runId", context.runId());
    body.put("intent", context.intent());
    if (context.userIdHash() != null) {
      body.put("userIdHash", context.userIdHash());
    }
    if (context.context() != null && !context.context().isEmpty()) {
      body.put("context", context.context());
    }
    List<Map<String, Object>> facts = new ArrayList<>();
    for (Fact fact : context.facts()) {
      facts.add(factMap(fact));
    }
    body.put("facts", facts);
    body.put("hints", context.hints() == null ? Map.of() : context.hints());
    body.put("deadlineAt", context.deadlineAt() == null
        ? Instant.now().plusMillis(timeoutMs).toString() : context.deadlineAt().toString());
    return body;
  }

  private static Map<String, Object> factMap(Fact fact) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("factId", fact.factId());
    map.put("type", fact.type());
    map.put("value", fact.value());
    Evidence e = fact.evidence();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("sourceKind", e.sourceKind());
    evidence.put("sourceId", e.sourceId());
    evidence.put("verificationStatus", e.verificationStatus());
    evidence.put("fetchedAt", e.fetchedAt() == null ? null : e.fetchedAt().toString());
    evidence.put("excerpt", e.excerpt());
    map.put("evidence", evidence);
    return map;
  }

  private AgentResult toAgentResult(Map<String, Object> body, long elapsedMs) {
    List<Fact> facts = new ArrayList<>();
    Object rawFacts = body.get("facts");
    if (rawFacts instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> raw)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fact = (Map<String, Object>) raw;
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) fact.get("value");
        @SuppressWarnings("unchecked")
        Map<String, Object> ev = (Map<String, Object>) fact.get("evidence");
        facts.add(new Fact(str(fact, "factId"), str(fact, "type"), value,
            new Evidence(str(ev, "sourceKind"), str(ev, "sourceId"),
                str(ev, "verificationStatus"), parseInstant(str(ev, "fetchedAt")),
                str(ev, "excerpt"))));
      }
    }
    List<String> unknown = stringList(body.get("unknownFields"));
    List<String> notices = stringList(body.get("notices"));
    long reported = elapsedMs;
    Object usage = body.get("usage");
    if (usage instanceof Map<?, ?> raw && raw.get("elapsedMs") instanceof Number n) {
      reported = n.longValue();
    }
    return new AgentResult(str(body, "status"), facts, str(body, "advice"),
        unknown, notices, new Usage(reported, 0));
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return result;
  }

  private static Set<String> stringSet(Object value) {
    if (!(value instanceof List<?> list)) {
      return Set.of();
    }
    Set<String> result = new java.util.LinkedHashSet<>();
    for (Object item : list) {
      if (item instanceof String s) {
        result.add(s);
      }
    }
    return result;
  }

  private static Instant parseInstant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value instanceof String s ? s : null;
  }

  /**
   * 从流中最多读 {@code maxBytes} 字节并解码为 UTF-8 字符串；一旦超过上限立即返回 {@code null}，
   * 不再拉取剩余字节。内存占用因此被限制在「上限 + 一个缓冲块」，恶意端点无法用超大响应耗尽内存。
   */
  static String readBounded(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(chunk)) != -1) {
      total += n;
      if (total > maxBytes) {
        return null;
      }
      buffer.write(chunk, 0, n);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static int intOr(Object value, int fallback) {
    return value instanceof Number n ? n.intValue() : fallback;
  }
}
