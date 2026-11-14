package com.travelassistant.server.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * DeepSeek 对话补全客户端（OpenAI 兼容协议）。
 * 为什么手写 HTTP 而不用 Spring AI 的 {@code ChatClient}：本项目的模型调用必须能在
 * **没有密钥时正常启动、正常降级**，而 Spring AI 的自动配置在缺少密钥时会导致上下文启动失败。
 * 这里改为显式 HTTP 调用，密钥为空就整体停用，行程、景点、预算、协作都不受影响。
 * 源码零引用 Spring AI，因此 POM 中不再保留 {@code spring-ai-deepseek} 及其 BOM
 * （此前打进 jar 的 5 个 spring-ai 依赖与 jtokkit 约 3.8 MB 属于死重）。
 * 后续若要换回 ChatClient，需先验证缺密钥时自动配置不会导致启动失败，再替换本类。
 *
 * 模型只负责措辞，不负责事实：调用方传给它的全部是已经查到的数据，
 * 返回的文本也不会被解析成结构化指令。
 */
@Component
public class DeepSeekClient {
  private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

  private final RestTemplate restTemplate;
  private final String apiKey;
  private final String baseUrl;
  private final String model;

  /**
   * 进程内累计用量。每次真实请求都会累加，供 {@code /api/agent/status} 回答
   * 「这一轮到底调了多少」。进程重启即归零——不落盘，避免把用量数据当成业务数据。
   */
  private final AtomicLong callCount = new AtomicLong();
  private final AtomicLong promptTokens = new AtomicLong();
  private final AtomicLong completionTokens = new AtomicLong();
  private final AtomicLong cachedTokens = new AtomicLong();
  /** 失败（抛 AgentUnavailableException）次数，用于失败率。 */
  private final AtomicLong errorCount = new AtomicLong();
  /** 成功调用的累计时延，用于平均时延。 */
  private final AtomicLong totalLatencyMs = new AtomicLong();
  /** 最近一次成功调用的时延（ms）。 */
  private volatile long lastLatencyMs = 0;
  /** 最近一次失败原因（不含密钥等敏感信息）；null 表示尚未失败。 */
  private volatile String lastError = null;
  /** 最近一次调用的摘要，形如「ChatAgent：输入 1167 / 输出 210 tokens（320ms）」。 */
  private volatile String lastCall = null;
  private final Instant startedAt = Instant.now();

  @org.springframework.beans.factory.annotation.Autowired
  public DeepSeekClient(RestTemplateBuilder builder,
      @Value("${travel.ai.api-key:}") String apiKey,
      @Value("${travel.ai.base-url:https://api.deepseek.com}") String baseUrl,
      @Value("${travel.ai.model:deepseek-chat}") String model,
      @Value("${travel.ai.timeout-ms:20000}") long timeoutMs) {
    this(builder
        .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .build(), apiKey, baseUrl, model);
  }

  /** 供测试注入自定义 RestTemplate（以便挂 MockRestServiceServer）。 */
  public DeepSeekClient(RestTemplate restTemplate, String apiKey, String baseUrl, String model) {
    this.restTemplate = restTemplate;
    this.apiKey = apiKey == null ? "" : apiKey.strip();
    this.baseUrl = baseUrl == null || baseUrl.isBlank()
        ? "https://api.deepseek.com" : baseUrl.strip();
    this.model = model == null || model.isBlank() ? "deepseek-chat" : model.strip();
  }

  /** 是否已配置密钥。 */
  public boolean configured() {
    return !apiKey.isEmpty();
  }

  public String model() {
    return model;
  }

  /**
   * 发起一次对话补全，返回助手文本。
   *
   * @throws AgentUnavailableException 未配置密钥、调用失败或响应无法解析
   */
  public String complete(String systemPrompt, String userPrompt) {
    Map<String, Object> body = baseBody(800);
    body.put("messages", List.of(
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", userPrompt)));
    JsonNode root = post(body);
    JsonNode content = root.path("choices").path(0).path("message").path("content");
    if (content.isMissingNode() || content.asText("").isBlank()) {
      throw new AgentUnavailableException("模型返回内容为空");
    }
    return content.asText().strip();
  }

  /** 模型要求调用的一次工具：{@code arguments} 是 JSON 字符串，不保证合法。 */
  public record ToolCall(String id, String name, String arguments) {
  }

  /** 一轮带工具的模型输出：要么给正文，要么要求调工具（两者可能同时有）。 */
  public record ModelTurn(String content, List<ToolCall> toolCalls) {
    public ModelTurn {
      content = content == null ? "" : content;
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean wantsTools() {
      return !toolCalls.isEmpty();
    }
  }

  /**
   * 带工具的一轮对话（OpenAI 兼容的 function calling）。
   *
   * <p>为什么保留 {@link #complete} 不合并：解析、措辞这类单轮调用不需要工具，
   * 走 {@code complete} 更省 token 也更好排查；需要模型自己决定「查什么、查几次」的场景
   * （对话 Agent）才走这里，上下文消息由调用方维护。
   *
   * @param messages 完整的消息序列：system / user / assistant（含 tool_calls）/ tool
   * @param tools    工具声明，{@code null} 或空则退化成普通补全
   * @param maxTokens 本次响应的 token 上限，内部夹在 200~2000
   */
  public ModelTurn chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
      int maxTokens) {
    Map<String, Object> body = baseBody(maxTokens);
    body.put("messages", messages == null ? List.of() : messages);
    if (tools != null && !tools.isEmpty()) {
      body.put("tools", tools);
      body.put("tool_choice", "auto");
    }
    JsonNode root = post(body);
    JsonNode message = root.path("choices").path(0).path("message");
    String content = message.path("content").isNull() || message.path("content").isMissingNode()
        ? "" : message.path("content").asText("").strip();
    List<ToolCall> calls = new ArrayList<>();
    JsonNode rawCalls = message.path("tool_calls");
    if (rawCalls.isArray()) {
      for (JsonNode call : rawCalls) {
        String id = call.path("id").asText("");
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("{}");
        if (!name.isBlank()) {
          calls.add(new ToolCall(id.isBlank() ? "call-" + calls.size() : id, name, args));
        }
      }
    }
    if (content.isBlank() && calls.isEmpty()) {
      throw new AgentUnavailableException("模型返回内容为空");
    }
    return new ModelTurn(content, calls);
  }

  private Map<String, Object> baseBody(int maxTokens) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("temperature", 0.3);
    body.put("max_tokens", Math.min(Math.max(maxTokens, 200), 2000));
    body.put("stream", false);
    return body;
  }

  /** 统一的请求与错误翻译，两条调用路径共用。 */
  private JsonNode post(Map<String, Object> body) {
    if (!configured()) {
      throw new AgentUnavailableException(
          "未配置模型密钥（环境变量 DEEPSEEK_API_KEY），本次结果不含模型建议");
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(apiKey);
    long started = System.nanoTime();
    try {
      ResponseEntity<JsonNode> response = restTemplate.postForEntity(
          baseUrl + "/chat/completions", new HttpEntity<>(body, headers), JsonNode.class);
      JsonNode root = response.getBody();
      if (root == null) {
        throw new AgentUnavailableException("模型返回空响应");
      }
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      recordUsage(root, elapsedMs);
      return root;
    } catch (HttpStatusCodeException e) {
      String why = explain(e);
      recordError(why);
      throw new AgentUnavailableException(why, e);
    } catch (RestClientException e) {
      String why = "模型服务请求失败：" + brief(e.getMessage());
      recordError(why);
      throw new AgentUnavailableException(why, e);
    }
  }

  /** 记一次失败（用于失败率统计）；不记录密钥等敏感细节。 */
  private void recordError(String why) {
    errorCount.incrementAndGet();
    lastError = why;
  }

  /**
   * 记一次成功调用的 token 用量与时延。
   *
   * <p>放在 {@link #post} 里而不是各个调用方：它是 {@code chat} 与 {@code complete}
   * 两条路径的唯一出口，写在这里不会漏计。调用方是谁不用业务代码传参，
   * 用栈回溯取最近的业务类名即可，免得为统计去改八个调用点的签名。
   */
  private void recordUsage(JsonNode root, long elapsedMs) {
    JsonNode usage = root.path("usage");
    long prompt = usage.path("prompt_tokens").asLong(0);
    long completion = usage.path("completion_tokens").asLong(0);
    long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(usage.path("prompt_cache_hit_tokens").asLong(0));
    long count = callCount.incrementAndGet();
    promptTokens.addAndGet(prompt);
    completionTokens.addAndGet(completion);
    cachedTokens.addAndGet(cached);
    totalLatencyMs.addAndGet(elapsedMs);
    lastLatencyMs = elapsedMs;
    String caller = callerName();
    lastCall = caller + "：输入 " + prompt + " / 输出 " + completion + " tokens（" + elapsedMs + "ms）";
    log.info("模型调用 #{} {} → 输入 {} 输出 {}（缓存命中 {}），本进程累计 输入 {}/输出 {}，耗时 {}ms",
        count, caller, prompt, completion, cached, promptTokens.get(), completionTokens.get(), elapsedMs);
  }

  /** 从调用栈取最近的业务类名，用来区分这次是谁在问模型。 */
  private static String callerName() {
    for (StackTraceElement element : new Throwable().getStackTrace()) {
      String name = element.getClassName();
      // 跳过本类、JDK 与 Spring 代理层，剩下的第一个就是业务调用方
      if (name.equals(DeepSeekClient.class.getName()) || name.startsWith("java.")
          || name.startsWith("jdk.") || name.startsWith("org.springframework.")) {
        continue;
      }
      int dot = name.lastIndexOf('.');
      String simple = dot < 0 ? name : name.substring(dot + 1);
      // CGLIB 代理类形如 XxxService$$SpringCGLIB$$0，截掉后缀
      int proxy = simple.indexOf("$$");
      return proxy < 0 ? simple : simple.substring(0, proxy);
    }
    return "unknown";
  }

  /**
   * 本进程的累计用量快照。
   *
   * <p>只有用量，没有余量：硅基流动的余额接口 {@code /v1/user/info} 已下线（返回 410），
   * 其余候选路径均为 404，程序侧拿不到账户余额，只能到控制台看。
   */
  public Map<String, Object> usageSnapshot() {
    Map<String, Object> body = new LinkedHashMap<>();
    long calls = callCount.get();
    long errors = errorCount.get();
    long totalRequests = calls + errors;
    body.put("model", model);
    body.put("endpoint", baseUrl);
    body.put("calls", calls);
    body.put("errors", errors);
    body.put("totalRequests", totalRequests);
    body.put("failureRate", totalRequests == 0 ? 0d : (double) errors / totalRequests);
    body.put("promptTokens", promptTokens.get());
    body.put("completionTokens", completionTokens.get());
    body.put("cachedPromptTokens", cachedTokens.get());
    body.put("totalTokens", promptTokens.get() + completionTokens.get());
    body.put("avgLatencyMs", calls == 0 ? 0d : (double) totalLatencyMs.get() / calls);
    body.put("lastLatencyMs", lastLatencyMs);
    body.put("lastCall", lastCall);
    body.put("lastError", lastError);
    body.put("since", startedAt.toString());
    body.put("balanceAvailable", false);
    return body;
  }

  /** 把常见 HTTP 状态翻成可操作的中文，密钥原文不进日志和异常。 */
  private String explain(HttpStatusCodeException e) {
    int status = e.getStatusCode().value();
    log.warn("DeepSeek 调用失败，HTTP {}", status);
    return switch (status) {
      case 401 -> "模型密钥无效或已失效，请检查 DEEPSEEK_API_KEY";
      case 402 -> "模型账户余额不足";
      case 429 -> "模型调用频率超限，请稍后重试";
      default -> {
        if (status >= 500) {
          yield "模型服务暂时不可用（HTTP " + status + "）";
        }
        yield "模型调用被拒绝（HTTP " + status + "）";
      }
    };
  }

  private String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 160 ? message.substring(0, 160) + "…" : message;
  }
}
