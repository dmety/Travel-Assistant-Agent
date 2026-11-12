package com.travelassistant.server.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.HttpAgentAdapter;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 本地桩验证 HTTP 适配器的传输与契约边界。环回地址只用于 G1/G2 探针，正式配置受 M-08 约束。 */
class HttpAgentAdapterTest {

  private static final String OK_RESPONSE = """
      {"schemaVersion":"1.0","providerId":"py.food.recommender","status":"OK",
       "facts":[],"advice":"建议依据本次下发的事实安排行程。","unknownFields":[],
       "notices":[],"usage":{"elapsedMs":12}}
      """;

  private final ObjectMapper json = new ObjectMapper();
  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);

  private HttpServer server;
  private String endpoint;
  private volatile int status = 200;
  private volatile String responseBody = OK_RESPONSE;
  private volatile Map<String, String> extraHeaders = Map.of();
  private volatile long delayMs = 0;
  private volatile String lastRequestBody;
  private volatile String lastAuthorization;
  private volatile String lastContentType;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/invoke", exchange -> {
      lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
        exchange.getResponseHeaders().add(header.getKey(), header.getValue());
      }
      byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
      if (status == 204) {
        exchange.sendResponseHeaders(204, -1);
      } else {
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
      }
      exchange.close();
    });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/invoke";
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private HttpAgentAdapter adapter() {
    return adapter(65536, 2000);
  }

  private HttpAgentAdapter adapter(int maxBytes, int timeoutMs) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("id", "py.food.recommender");
    config.put("kind", "HTTP");
    config.put("endpoint", endpoint);
    config.put("authRef", "PY_AGENT_TOKEN_THAT_IS_NOT_SET");
    config.put("version", "0.1.0");
    config.put("capabilities", List.of("FOOD_RECOMMEND"));
    config.put("schemaVersion", "1.0");
    config.put("timeoutMs", timeoutMs);
    config.put("maxResponseBytes", maxBytes);
    config.put("sideEffect", "NONE");
    return new HttpAgentAdapter(config, validator, rules, json);
  }

  private AgentContext context() {
    Evidence evidence = new Evidence("KB", "kb-1", "unverified",
        Instant.parse("2026-11-09T10:00:00Z"), null);
    Fact fact = new Fact("a1", "ATTRACTION", Map.of("name", "西湖"), evidence);
    return new AgentContext("run-000001", null, "FOOD_RECOMMEND",
        Map.of("city", "武汉"), List.of(fact), Map.of("hint", "少辣"), Instant.now().plusSeconds(10));
  }

  private RunBudget budget() {
    return new RunBudget(Instant.now().plusSeconds(10), 1, 4);
  }

  private static Set<String> codes(HttpAgentAdapter.ContractViolationException e) {
    return e.failures().stream().map(RuleFailure::code).collect(Collectors.toSet());
  }

  @Test
  void compliantResponseIsAcceptedAndRequestMatchesSchema() throws IOException {
    AgentResult result = adapter().invoke(context(), budget());
    assertEquals("OK", result.status());
    assertEquals("建议依据本次下发的事实安排行程。", result.advice());
    assertTrue(result.facts().isEmpty());
    assertEquals(12L, result.usage().elapsedMs());
    assertEquals("ACTIVE", adapter().descriptor().status());
    assertNull(lastAuthorization);

    JsonNode request = json.readTree(lastRequestBody);
    assertEquals("1.0", request.path("schemaVersion").asText());
    assertEquals("run-000001", request.path("runId").asText());
    assertEquals("FOOD_RECOMMEND", request.path("intent").asText());
    assertEquals(1, request.path("facts").size());
    assertEquals("少辣", request.path("hints").path("hint").asText());
    assertEquals("武汉", request.path("context").path("city").asText());
    assertNotNull(request.path("deadlineAt").asText());
    assertEquals("application/json; charset=UTF-8", lastContentType);
  }

  @Test
  void compliantResponseWithFactRoundTripsEvidence() {
    responseBody = """
        {"schemaVersion":"1.0","providerId":"py.food.recommender","status":"OK",
         "facts":[{"factId":"b1","type":"CONTENT","value":{"category":"FOOD"},
                   "evidence":{"sourceKind":"EXTERNAL","verificationStatus":"unverified",
                               "fetchedAt":"2026-11-09T10:00:00Z"}}],
         "advice":null,"unknownFields":["人均价格"],"notices":[],
         "usage":{"elapsedMs":30,"modelCalls":1}}
        """;
    AgentResult result = adapter().invoke(context(), budget());
    assertEquals(1, result.facts().size());
    assertEquals("b1", result.facts().get(0).factId());
    assertEquals("EXTERNAL", result.facts().get(0).evidence().sourceKind());
    assertEquals(Instant.parse("2026-11-09T10:00:00Z"), result.facts().get(0).evidence().fetchedAt());
    assertEquals(List.of("人均价格"), result.unknownFields());
    assertEquals(30L, result.usage().elapsedMs());
  }

  @Test
  void non200StatusIsRefused() {
    status = 500;
    assertEquals("REFUSED", adapter().invoke(context(), budget()).status());
    status = 204;
    assertEquals("REFUSED", adapter().invoke(context(), budget()).status());
  }

  @Test
  void redirectIsNotFollowed() {
    status = 302;
    extraHeaders = Map.of("Location", "http://127.0.0.1/other");
    assertEquals("REFUSED", adapter().invoke(context(), budget()).status());
  }

  @Test
  void setCookieIsRefused() {
    extraHeaders = Map.of("Set-Cookie", "JSESSIONID=abc");
    assertEquals("REFUSED", adapter().invoke(context(), budget()).status());
  }

  @Test
  void invalidJsonIsRefused() {
    responseBody = "{ not json";
    assertEquals("REFUSED", adapter().invoke(context(), budget()).status());
  }

  @Test
  void oversizedResponseIsRefused() {
    assertEquals("REFUSED", adapter(64, 2000).invoke(context(), budget()).status());
  }

  @Test
  void timeoutIsRefusedInsteadOfThrowing() {
    delayMs = 400;
    assertEquals("REFUSED", adapter(65536, 120).invoke(context(), budget()).status());
  }

  @Test
  void verifiedFactIsRejectedWithStableCode() {
    responseBody = """
        {"schemaVersion":"1.0","providerId":"py.food.recommender","status":"OK",
         "facts":[{"factId":"b1","type":"CONTENT","value":{},
                   "evidence":{"sourceKind":"KB","verificationStatus":"verified",
                               "fetchedAt":"2026-11-09T10:00:00Z"}}],
         "advice":null,"unknownFields":[],"notices":[],"usage":{"elapsedMs":1}}
        """;
    HttpAgentAdapter.ContractViolationException e = assertThrows(
        HttpAgentAdapter.ContractViolationException.class,
        () -> adapter().invoke(context(), budget()));
    assertTrue(codes(e).contains("E_SEMANTIC_VERIFIED"));
  }

  @Test
  void additionalResponseFieldIsRejected() {
    responseBody = OK_RESPONSE.replace("\"usage\"", "\"debug\":\"trace\",\"usage\"");
    HttpAgentAdapter.ContractViolationException e = assertThrows(
        HttpAgentAdapter.ContractViolationException.class,
        () -> adapter().invoke(context(), budget()));
    assertTrue(codes(e).contains("E_SCHEMA_ADDITIONAL"));
  }

  @Test
  void missingUsageIsRejected() {
    responseBody = """
        {"schemaVersion":"1.0","providerId":"py.food.recommender","status":"OK",
         "facts":[],"advice":null,"unknownFields":[],"notices":[]}
        """;
    HttpAgentAdapter.ContractViolationException e = assertThrows(
        HttpAgentAdapter.ContractViolationException.class,
        () -> adapter().invoke(context(), budget()));
    assertTrue(codes(e).contains("E_SCHEMA_REQUIRED"));
  }
}
