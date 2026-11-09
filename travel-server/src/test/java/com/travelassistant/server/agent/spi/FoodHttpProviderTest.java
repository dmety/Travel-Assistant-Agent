package com.travelassistant.server.agent.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.HttpAgentAdapter;
import com.travelassistant.server.application.agent.spi.RunBudget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 外部美食 Provider 的响应契约验证：用 JDK 本地桩模拟
 * {@code tools/agent/food_provider_server.py} 的 /v1/invoke 输出。
 *
 * <p>这里刻意不把桩注册进 AgentRegistryImpl：G0 会拒绝环回地址与 IP 字面量，
 * 本机服务本来就不该被写成 ACTIVE。第二个测试把这条边界锁死，避免以后有人
 * 为了「跑通」去放宽 allowlist。
 */
@DisplayName("外部美食 HTTP Provider 契约")
class FoodHttpProviderTest {

  private static final String RESPONSE = """
      {
       "schemaVersion": "1.0",
       "providerId": "py.food.recommender",
       "status": "OK",
       "facts": [{
         "factId": "py.food:00:B1",
         "type": "CONTENT",
         "value": {
           "category": "FOOD", "kind": "RESTAURANT", "name": "Test Restaurant",
           "mealSlot": "LUNCH", "pricePerPersonCny": 68.0,
           "openHours": "10:00-21:00", "distanceMeters": 73, "poiId": "B1",
           "tags": ["Chinese", "Local"], "allergenFlags": [],
           "sourceLayer": "FACT", "constraintNotes": [],
           "reasonTags": ["位于所选半径内"], "rating": 4.7,
           "ratingSource": "AMAP", "ratingFetchedAt": "2026-11-09T10:00:00Z",
           "reviewLinks": []
         },
         "evidence": {
           "sourceKind": "AMAP", "sourceId": "B1",
           "verificationStatus": "unverified",
           "fetchedAt": "2026-11-09T10:00:00Z",
           "excerpt": "地图 POI 快照，未核验"
         }
       }],
       "advice": "已整理附近餐饮候选，评分与人均价格以候选字段为准；营业时段与忌口信息请到店核实",
       "unknownFields": [],
       "notices": ["距离按直线计算，不是步行距离或步行时间",
                   "评分与人均是地图快照，未经商户核验",
                   "评价平台外链白名单未确认，本轮不生成外链"],
       "usage": {"elapsedMs": 0, "modelCalls": 0}
      }
      """;

  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private String endpoint;
  private final AtomicReference<String> received = new AtomicReference<>("");

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/invoke", exchange -> {
      received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/invoke";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private Map<String, Object> config() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("id", "py.food.recommender");
    config.put("kind", "HTTP");
    config.put("endpoint", endpoint);
    config.put("capabilities", List.of("FOOD_RECOMMEND"));
    config.put("timeoutMs", 4000);
    config.put("maxResponseBytes", 65536);
    config.put("version", "0.1.0");
    config.put("schemaVersion", "1.0");
    config.put("sideEffect", "NONE");
    return config;
  }

  private static AgentContext context() {
    Map<String, Object> anchorValue = new LinkedHashMap<>();
    anchorValue.put("lat", 30.259);
    anchorValue.put("lon", 120.13);
    anchorValue.put("coordinateSystem", "GCJ-02");
    Fact anchor = new Fact("anchor:a1", "ATTRACTION", anchorValue,
        new Evidence("KB", "a1", Evidence.UNVERIFIED, Instant.now(), "锚点"));
    return new AgentContext("run-000001", null, "FOOD_RECOMMEND",
        Map.of("city", "杭州", "dietaryFlags", List.of()),
        List.of(anchor), Map.of(), Instant.now().plusSeconds(10));
  }

  @Test
  @DisplayName("Python Provider 形状的响应通过运行期契约校验并转成 AgentResult")
  void externalFoodResponseIsAccepted() {
    HttpAgentAdapter adapter = new HttpAgentAdapter(config(), validator, rules, json);
    AgentResult result = adapter.invoke(context(),
        new RunBudget(Instant.now().plusSeconds(10), 1, 4));

    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.facts()).hasSize(1);
    assertThat(result.facts().get(0).type()).isEqualTo("CONTENT");
    assertThat(result.facts().get(0).value()).containsEntry("category", "FOOD");
    assertThat(result.facts().get(0).evidence().verificationStatus())
        .isEqualTo(Evidence.UNVERIFIED);
    assertThat(result.advice()).isNotBlank();
    assertThat(validator.validateResult(
        validator.toResponseMap("py.food.recommender", result))).isEmpty();
    // 锚点 fact 确实下发给了外部 Provider，不是 Provider 自己读仓库。
    assertThat(received.get()).contains("coordinateSystem").contains("ATTRACTION");
  }

  @Test
  @DisplayName("G0 拒绝环回地址：本地服务不能写进运行态配置冒充 ACTIVE")
  void g0RejectsLoopbackEndpoint() {
    Map<String, Object> config = config();
    config.put("endpoint", endpoint);
    config.put("healthPath", "/healthz");
    config.put("owner", "travel-team");
    config.put("description", "本机开发桩");
    config.put("authRef", "PY_AGENT_TOKEN");
    config.put("enabled", false);
    var report = validator.validateConfig(config, 0, true);
    assertThat(report.failures()).extracting(
        com.travelassistant.server.application.agent.spi.RuleFailure::code)
        .contains("E_META_ENDPOINT");
    assertThat(report.blocked()).isTrue();
  }
}
