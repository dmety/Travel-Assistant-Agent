package com.travelassistant.server.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.spi.AdmissionController;
import com.travelassistant.server.application.agent.spi.AdmissionReport;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentProviderConfigLoader;
import com.travelassistant.server.application.agent.spi.AgentRegistryImpl;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.BuiltinRulesProvider;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.DefaultAdmissionController;
import com.travelassistant.server.application.agent.spi.DescriptorOnlyProvider;
import com.travelassistant.server.application.agent.spi.GoldCaseSet;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.infrastructure.audit.AdminAuditLog;
import com.travelassistant.server.interfaces.rest.AgentCapabilityController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 注册表、配置加载与能力协商接口的集成测试，覆盖 P0 的三条退出条件。 */
class AgentRegistryIntegrationTest {

  private static final String COMPLIANT = """
      {
        "schemaVersion": "1.0",
        "providers": [{
          "id": "food.recommender", "kind": "HTTP",
          "endpoint": "https://agent.example.com/v1/invoke", "healthPath": "/healthz",
          "authRef": "FOOD_AGENT_TOKEN", "version": "0.1.0",
          "owner": "travel-team", "description": "外部美食推荐 Provider",
          "capabilities": ["FOOD_RECOMMEND"], "schemaVersion": "1.0",
          "timeoutMs": 4000, "maxResponseBytes": 65536,
          "sideEffect": "NONE", "enabled": true
        }],
        "routes": [{"intent": "FOOD_RECOMMEND", "primary": "food.recommender",
                    "fallback": ["builtin.rules"]}]
      }
      """;

  private final ObjectMapper json = new ObjectMapper();
  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private Path dir;
  private Path configFile;
  private List<AgentProvider> beans;
  private AdmissionController admission;

  @BeforeEach
  void setUp() throws IOException {
    dir = Files.createTempDirectory(Path.of("target"), "agent-registry-");
    configFile = dir.resolve("agent_providers.json");
    beans = builtinBeans();
    AgentProviderConfigLoader admissionLoader =
        new AgentProviderConfigLoader(json, configFile.toString(), true);
    admission = new DefaultAdmissionController(validator, rules,
        new GoldCaseSet(json), admissionLoader, json);
  }

  @AfterEach
  void tearDown() throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private List<AgentProvider> builtinBeans() {
    List<AgentProvider> result = new ArrayList<>();
    result.add(new DescriptorOnlyProvider(new AgentDescriptor("builtin.planning", "1.0.0",
        rules.contractVersion(), Set.of("PLAN_DRAFT"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null)));
    result.add(new DescriptorOnlyProvider(new AgentDescriptor("builtin.decision", "1.0.0",
        rules.contractVersion(), Set.of("DECISION_ADVICE"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null)));
    result.add(new DescriptorOnlyProvider(new AgentDescriptor("builtin.ticket", "1.0.0",
        rules.contractVersion(), Set.of("TICKET_ADVICE"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null)));
    result.add(new BuiltinRulesProvider(rules));
    return result;
  }

  private AgentRegistryImpl registry() {
    return registry(true);
  }

  private AgentRegistryImpl registry(boolean enabled) {
    AgentProviderConfigLoader loader =
        new AgentProviderConfigLoader(json, configFile.toString(), enabled);
    return new AgentRegistryImpl(beans, validator, rules, loader, json, admission);
  }

  /** 用本地桩走完 G1 至 G4，模拟管理员完成准入，不发起真实外部调用。 */
  private void approve(String id) {
    assertFalse(admission.probe(id, AgentTestStubs.compliant()).blocked());
    assertFalse(admission.shadow(id, 20, AgentTestStubs.compliant()).blocked());
    assertFalse(admission.activate(id).blocked());
  }

  private void write(String content) throws IOException {
    Files.writeString(configFile, content, StandardCharsets.UTF_8);
  }

  private AgentDescriptor descriptor(AgentRegistryImpl registry, String id) {
    return registry.descriptors().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
  }

  private Set<String> codes(AdmissionReport report) {
    return report.failures().stream().map(RuleFailure::code).collect(Collectors.toSet());
  }

  // ---------------------------------------------------------------- P0 退出条件

  @Test
  void noConfigFallsBackToBuiltins() {
    AgentRegistryImpl registry = registry();
    assertEquals("OK", registry.note());
    assertEquals(4, registry.descriptors().size());
    assertTrue(registry.find("builtin.rules").isPresent());
    assertEquals(1, registry.resolve("FOOD_RECOMMEND", null).fallback().size());
    assertEquals("builtin.rules", registry.resolve("FOOD_RECOMMEND", null).fallback().get(0));
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
    assertEquals(4, registry.reports().size());
    assertNull(registry.admission("ghost.provider"));
  }

  @Test
  void builtinBeanWithCapabilityBecomesDefaultPrimary() {
    beans.add(new DescriptorOnlyProvider(new AgentDescriptor("builtin.food", "1.0.0",
        rules.contractVersion(), Set.of("FOOD_RECOMMEND"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null)));
    AgentRegistryImpl registry = registry();
    assertEquals(5, registry.descriptors().size());
    // 没有配置路由时，声明了对应能力的内置 Bean 自动成为 primary；
    // builtin.rules 声明全部能力，必须排除，否则所有 intent 都会先撞兜底。
    assertEquals("builtin.food", registry.resolve("FOOD_RECOMMEND", null).primary());
    assertEquals("builtin.planning", registry.resolve("PLAN_DRAFT", null).primary());
    assertEquals("builtin.rules", registry.resolve("FOOD_RECOMMEND", null).fallback().get(0));
  }

  @Test
  void compliantConfigNeedsG4BeforeItCanRoute() throws IOException {
    write(COMPLIANT);
    AgentRegistryImpl registry = registry();
    // 只写配置不再自动 ACTIVE：G0 通过后停在 PROBING，必须显式跑完 G1 至 G4。
    assertEquals(AgentDescriptor.PROBING, descriptor(registry, "food.recommender").status());
    assertTrue(registry.find("food.recommender").isEmpty());
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
    assertNotNull(registry.admission("food.recommender"));
    assertFalse(registry.admission("food.recommender").blocked());

    approve("food.recommender");
    registry.reload();
    assertEquals(AgentDescriptor.ACTIVE, descriptor(registry, "food.recommender").status());
    assertTrue(registry.find("food.recommender").isPresent());
    assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());
    assertEquals("HTTP", descriptor(registry, "food.recommender").kind());
    assertTrue(descriptor(registry, "food.recommender").enabled());
  }

  @Test
  void nonCompliantConfigIsRejectedWithStableReasonCodes() throws IOException {
    String bad = COMPLIANT.replace("\"authRef\": \"FOOD_AGENT_TOKEN\"",
        "\"authRef\": \"FOOD_AGENT_TOKEN\", \"token\": \"sk-fake\"")
        .replace("\"capabilities\": [\"FOOD_RECOMMEND\"], \"schemaVersion\": \"1.0\"",
            "\"capabilities\": [\"FOOD_RECOMMEND\"], \"schemaVersion\": \"2.0\"");
    write(bad);
    AgentRegistryImpl registry = registry();
    assertEquals(AgentDescriptor.REJECTED, descriptor(registry, "food.recommender").status());
    assertTrue(registry.find("food.recommender").isEmpty());
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
    AdmissionReport report = registry.admission("food.recommender");
    assertTrue(codes(report).contains("E_META_AUTH"));
    assertTrue(codes(report).contains("E_META_SCHEMA"));
    assertNotNull(descriptor(registry, "food.recommender").lastRejectionCode());
  }

  @Test
  void disabledExternalProviderStaysProbingAndDoesNotRoute() throws IOException {
    write(COMPLIANT.replace("\"enabled\": true", "\"enabled\": false"));
    AgentRegistryImpl registry = registry();
    assertEquals(AgentDescriptor.PROBING, descriptor(registry, "food.recommender").status());
    assertFalse(descriptor(registry, "food.recommender").enabled());
    assertTrue(registry.find("food.recommender").isEmpty());
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
  }

  @Test
  void removingConfigRevokesApproval() throws IOException {
    write(COMPLIANT);
    AgentRegistryImpl registry = registry();
    approve("food.recommender");
    registry.reload();
    assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());

    Files.delete(configFile);
    registry.reload();
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
    // 配置再回来时也必须重跑闸门，不能沿用旧的 ACTIVE。
    write(COMPLIANT);
    registry.reload();
    assertEquals(AgentDescriptor.PROBING, descriptor(registry, "food.recommender").status());
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
  }

  @Test
  void turningOffConfigFallsBackToBuiltins() throws IOException {
    write(COMPLIANT);
    AgentRegistryImpl registry = registry();
    approve("food.recommender");
    registry.reload();
    assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());
    Files.delete(configFile);
    registry.reload();
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
    assertEquals("builtin.rules", registry.resolve("FOOD_RECOMMEND", null).fallback().get(0));
    assertEquals(4, registry.descriptors().size());
  }

  @Test
  void disabledLoaderFallsBackToBuiltins() throws IOException {
    write(COMPLIANT);
    AgentRegistryImpl registry = registry(false);
    assertEquals(4, registry.descriptors().size());
    assertNull(registry.resolve("FOOD_RECOMMEND", null).primary());
  }

  // ---------------------------------------------------------------- 配置健壮性

  @Test
  void brokenConfigKeepsLastKnownGoodSnapshot() throws IOException {
    write(COMPLIANT);
    AgentRegistryImpl registry = registry();
    approve("food.recommender");
    registry.reload();
    String goodVersion = registry.version();
    write("{ this is not json");
    registry.reload();
    assertTrue(registry.note().startsWith("LAST_KNOWN_GOOD"));
    assertEquals(goodVersion, registry.version());
    assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());
  }

  @Test
  void malformedConfigShapesAreRejectedGracefully() throws IOException {
    write("[1, 2, 3]");
    assertTrue(registry().note().contains("LAST_KNOWN_GOOD"));
    write("{\"schemaVersion\":\"1.0\",\"providers\":{}}");
    assertTrue(registry().note().contains("LAST_KNOWN_GOOD"));
  }

  @Test
  void duplicateIdsAreRejectedNotSilentlyOverwritten() throws IOException {
    String provider = """
        {"id": "food.recommender", "kind": "HTTP",
         "endpoint": "https://agent.example.com/v1/invoke", "healthPath": "/healthz",
         "authRef": "FOOD_AGENT_TOKEN", "version": "0.1.0",
         "owner": "travel-team", "description": "重复条目",
         "capabilities": ["FOOD_RECOMMEND"], "schemaVersion": "1.0",
         "timeoutMs": 4000, "maxResponseBytes": 65536,
         "sideEffect": "NONE", "enabled": true}
        """;
    write("{\"schemaVersion\":\"1.0\",\"providers\":[" + provider + "," + provider + "],"
        + "\"routes\":[{\"intent\":\"FOOD_RECOMMEND\",\"primary\":\"food.recommender\","
        + "\"fallback\":[\"builtin.rules\"]}]}");
    AgentRegistryImpl registry = registry();
    assertTrue(codes(registry.admission("food.recommender")).contains("E_META_ID"));
    assertEquals(2, registry.descriptors().stream()
        .filter(d -> "food.recommender".equals(d.id())
            && AgentDescriptor.REJECTED.equals(d.status())).count());
    assertTrue(registry.find("food.recommender").isEmpty());
  }

  @Test
  void conflictWithBuiltinIdIsRejected() throws IOException {
    write(COMPLIANT.replace("\"id\": \"food.recommender\"", "\"id\": \"builtin.planning\"")
        .replace("\"primary\": \"food.recommender\"", "\"primary\": \"builtin.planning\""));
    AgentRegistryImpl registry = registry();
    // M-02 保留前缀先命中，重复 id 再命中；外部条目被拒，但内置 Bean 不能被覆盖。
    assertTrue(registry.descriptors().stream().anyMatch(d -> "builtin.planning".equals(d.id())
        && AgentDescriptor.REJECTED.equals(d.status())));
    assertTrue(registry.find("builtin.planning").isPresent());
    assertEquals("BEAN", registry.find("builtin.planning").orElseThrow().descriptor().kind());
  }

  @Test
  void duplicateBeanIdFailsFast() {
    beans = List.of(beans.get(0), beans.get(0));
    assertThrows(IllegalStateException.class, this::registry);
  }

  @Test
  void reloadAtomicallyReplacesSnapshot() throws IOException {
    AgentRegistryImpl registry = registry();
    assertEquals(4, registry.descriptors().size());
    write(COMPLIANT);
    approve("food.recommender");
    registry.reload();
    assertEquals(5, registry.descriptors().size());
    assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());
    Files.delete(configFile);
    registry.reload();
    assertEquals(4, registry.descriptors().size());
  }

  // ---------------------------------------------------------------- 能力协商接口

  @Test
  void capabilitiesAreSanitizedAndExposeStatus() throws IOException {
    write(COMPLIANT);
    AgentCapabilityController controller =
        new AgentCapabilityController(registry(), rules, admission, new AdminAuditLog());
    Map<String, Object> body = controller.capabilities();
    assertEquals("1.0", body.get("contractVersion"));
    assertNotNull(body.get("registryVersion"));
    assertEquals("OK", body.get("registryNote"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) body.get("providers");
    assertEquals(5, providers.size());
    String serialized = json.writeValueAsString(body);
    assertFalse(serialized.contains("endpoint"));
    assertFalse(serialized.contains("authRef"));
    assertFalse(serialized.contains("FOOD_AGENT_TOKEN"));
    assertTrue(serialized.contains("lastRejectionCode"));
    assertTrue(serialized.contains("ACTIVE"));

    Map<String, Object> detailed = controller.providers();
    assertTrue(detailed.containsKey("routes"));
    assertEquals(200, controller.admission("food.recommender").getStatusCode().value());
    assertEquals(404, controller.admission("ghost.provider").getStatusCode().value());
    Map<String, Object> reloaded = controller.reload(null);
    assertEquals(true, reloaded.get("reloaded"));
    assertEquals(5, reloaded.get("providerCount"));
  }

  @Test
  void rejectedProviderAdmissionReportIsQueryable() throws IOException {
    String bad = COMPLIANT.replace("\"authRef\": \"FOOD_AGENT_TOKEN\"",
        "\"authRef\": \"FOOD_AGENT_TOKEN\", \"token\": \"sk-fake\"");
    write(bad);
    AgentCapabilityController controller =
        new AgentCapabilityController(registry(), rules, admission, new AdminAuditLog());
    AdmissionReport report = controller.admission("food.recommender").getBody();
    assertNotNull(report);
    assertTrue(codes(report).contains("E_META_AUTH"));
    assertEquals("G0", report.stage());
  }

  @Test
  void gateEndpointsReturnReportAndCurrentStatus() throws IOException {
    write(COMPLIANT);
    AdmissionController stub = mock(AdmissionController.class);
    AdmissionReport report = new AdmissionReport("1.0", "food.recommender",
        AgentDescriptor.REJECTED, "G1", List.of(), List.of(), Instant.now());
    when(stub.probe(eq("food.recommender"), isNull())).thenReturn(report);
    when(stub.shadow(eq("food.recommender"), anyInt(), isNull())).thenReturn(report);
    when(stub.activate(eq("food.recommender"))).thenReturn(report);
    when(stub.shadowStats(eq("food.recommender")))
        .thenReturn(Map.of("samples", 20, "violations", 0));
    AgentCapabilityController controller =
        new AgentCapabilityController(registry(), rules, stub, new AdminAuditLog());

    Map<String, Object> probed = controller.probe("food.recommender", null);
    assertEquals(report, probed.get("report"));
    assertEquals(AgentDescriptor.PROBING, probed.get("status"));
    assertNotNull(probed.get("registryVersion"));
    assertEquals(Map.of("samples", 20, "violations", 0), probed.get("shadow"));

    Map<String, Object> shadowed = controller.shadow("food.recommender", 20, null);
    assertEquals(report, shadowed.get("report"));
    Map<String, Object> activated = controller.activate("food.recommender", null);
    assertEquals(report, activated.get("report"));
  }

  // ---------------------------------------------------------------- 内置 Provider 输出合规

  @Test
  void builtinProviderResultsPassTheSameValidator() {
    AgentContext context = new AgentContext("run-000001", null, "FOOD_RECOMMEND",
        Map.of("city", "武汉"), List.of(), Map.of(), Instant.now());
    RunBudget budget = new RunBudget(Instant.now().plusSeconds(10), 1, 4);
    for (AgentProvider bean : beans) {
      AgentResult result = bean.invoke(context, budget);
      assertEquals("REFUSED", result.status());
      assertTrue(validator.validateResult(
          validator.toResponseMap(bean.descriptor().id(), result)).isEmpty());
    }
  }

  @Test
  void loaderExposesItsConfigPath() {
    AgentProviderConfigLoader loader =
        new AgentProviderConfigLoader(json, configFile.toString(), false);
    assertEquals(configFile, loader.configPath());
    AgentProviderConfigLoader.Document doc = loader.load();
    assertFalse(doc.present());
    assertFalse(doc.broken());
    assertTrue(doc.providers().isEmpty());
  }

  @Test
  void loaderParsesCompliantConfig() throws IOException {
    write(COMPLIANT);
    AgentProviderConfigLoader loader =
        new AgentProviderConfigLoader(json, configFile.toString(), true);
    AgentProviderConfigLoader.Document doc = loader.load();
    assertTrue(doc.present());
    assertFalse(doc.broken());
    assertEquals(1, doc.providers().size());
    assertEquals("food.recommender", doc.providers().get(0).get("id"));
    assertEquals(1, doc.routes().size());
    assertNull(doc.error());
    assertNotNull(doc.loadedAt());
  }

  @Test
  void loaderReportsBrokenConfigWithoutThrowing() throws IOException {
    write("{ broken");
    AgentProviderConfigLoader.Document doc =
        new AgentProviderConfigLoader(json, configFile.toString(), true).load();
    assertTrue(doc.broken());
    assertNotNull(doc.error());
    assertTrue(doc.providers().isEmpty());
  }
  @Test
  void configuredLocalHttpProviderPassesRealGatesRoutesAndFallsBackOnFailure() throws Exception {
    var failing = new java.util.concurrent.atomic.AtomicBoolean(false);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/v1/invoke", exchange -> {
      requests.incrementAndGet();
      var body = json.readTree(exchange.getRequestBody().readAllBytes());
      String runId = body.path("runId").asText();
      String advice = "gc03-run-0001".equals(runId) ? "票价未知，请以现场公示为准" : "已按已核实事实整理建议";
      var result = new AgentResult("OK", List.of(), advice,
          "gc02-run-0001".equals(runId) ? List.of("destination", "budget") : List.of(),
          List.of("本地模拟助手，不是真实推荐"), new com.travelassistant.server.application.agent.spi.Usage(0L, 0));
      byte[] out = json.writeValueAsBytes(validator.toResponseMap("food.recommender", result));
      exchange.sendResponseHeaders(failing.get() ? 503 : 200, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    http.start();
    try {
      String endpoint = "http://127.0.0.1:" + http.getAddress().getPort() + "/v1/invoke";
      write(COMPLIANT.replace("https://agent.example.com/v1/invoke", endpoint));
      var loader = new AgentProviderConfigLoader(json, configFile.toString(), true);
      var defaultAdmission = new DefaultAdmissionController(validator, rules, new GoldCaseSet(json), loader, json);
      assertTrue(defaultAdmission.probe("food.recommender", null).blocked());
      assertEquals(0, requests.get(), "G0-rejected config must never trigger a network probe");
      var local = new ContractValidator(rules, endpoint);
      var gates = new DefaultAdmissionController(local, rules, new GoldCaseSet(json), loader, json);
      assertFalse(gates.probe("food.recommender", null).blocked());
      assertFalse(gates.shadow("food.recommender", 20, null).blocked());
      assertFalse(gates.activate("food.recommender").blocked());
      var registry = new AgentRegistryImpl(beans, local, rules, loader, json, gates);
      assertEquals("food.recommender", registry.resolve("FOOD_RECOMMEND", null).primary());
      var anchors = mock(com.travelassistant.server.application.agent.orchestration.AnchorFactService.class);
      when(anchors.build(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
      var runtime = new com.travelassistant.server.application.agent.orchestration.AgentOrchestrator(
          registry, local, rules, anchors,
          mock(com.travelassistant.server.application.agent.orchestration.SummaryComposer.class),
          1, 4);
      var request = new com.travelassistant.server.application.agent.orchestration.DispatchRequest(
          "FOOD_RECOMMEND", Map.of("city", "杭州"), Map.of());
      assertEquals("food.recommender", runtime.dispatch(request).providerId());
      failing.set(true);
      var degraded = runtime.dispatch(request);
      assertEquals("builtin.rules", degraded.providerId());
      assertEquals("REFUSED", degraded.status());
      assertEquals(2, degraded.attempts().size());
      assertTrue(requests.get() >= 22);
    } finally {
      http.stop(0);
    }
  }

}
