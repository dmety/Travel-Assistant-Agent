package com.travelassistant.server.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.spi.AdmissionReport;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentProviderConfigLoader;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.DefaultAdmissionController;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.GoldCaseSet;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.spi.Usage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** G1 至 G4 状态机、gold case 判定与运行期熔断的回归测试。 */
class DefaultAdmissionControllerTest {

  private static final String ID = "food.recommender";
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
        "routes": []
      }
      """;

  private final ObjectMapper json = new ObjectMapper();
  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private final GoldCaseSet cases = new GoldCaseSet(json);
  private Path dir;
  private Path configFile;
  private AgentProviderConfigLoader loader;
  private DefaultAdmissionController controller;

  @BeforeEach
  void setUp() throws IOException {
    dir = Files.createTempDirectory(Path.of("target"), "agent-admission-");
    configFile = dir.resolve("agent_providers.json");
    Files.writeString(configFile, COMPLIANT, StandardCharsets.UTF_8);
    loader = new AgentProviderConfigLoader(json, configFile.toString(), true);
    controller = new DefaultAdmissionController(validator, rules, cases, loader, json);
  }

  @AfterEach
  void tearDown() throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static Set<String> codes(AdmissionReport report) {
    return report.failures().stream().map(RuleFailure::code).collect(Collectors.toSet());
  }

  private static AgentResult ok(String advice, List<String> unknown, List<Fact> facts,
      List<String> notices, long elapsedMs) {
    return new AgentResult("OK", facts, advice, unknown, notices, new Usage(elapsedMs, 0));
  }

  /** 只在指定用例上返回坏结果，其余走合规桩，用来逐条锁死 expect 约束。 */
  private static AgentProvider overriding(String runId, AgentResult bad) {
    AgentProvider base = AgentTestStubs.compliant();
    return AgentTestStubs.answering(context -> runId.equals(context.runId())
        ? bad : base.invoke(context, null));
  }

  private static Fact verifiedFact() {
    return new Fact("bad-fact", "ATTRACTION", Map.of("name", "西湖"),
        new Evidence("KB", "kb:1", "verified", Instant.now(), "自称已核验"));
  }

  // ---------------------------------------------------------------- 全流程

  @Test
  void fullGateFlowLandsInActive() {
    AdmissionReport probed = controller.probe(ID, AgentTestStubs.compliant());
    assertFalse(probed.blocked());
    assertEquals("G2", probed.stage());
    assertEquals(AgentDescriptor.SHADOW, controller.stage(ID));
    assertFalse(controller.active(ID));

    AdmissionReport shadowed = controller.shadow(ID, 20, AgentTestStubs.compliant());
    assertFalse(shadowed.blocked());
    assertEquals("G3", shadowed.stage());
    assertEquals(Map.of("samples", 20, "violations", 0), controller.shadowStats(ID));

    AdmissionReport activated = controller.activate(ID);
    assertFalse(activated.blocked());
    assertEquals(AgentDescriptor.ACTIVE, activated.status());
    assertEquals("G4", activated.stage());
    assertTrue(controller.active(ID));
    assertEquals(AgentDescriptor.ACTIVE, controller.stage(ID));
  }

  @Test
  void shadowUsesContractDefaultSampleCount() {
    controller.probe(ID, AgentTestStubs.compliant());
    controller.shadow(ID, 0, AgentTestStubs.compliant());
    assertEquals(rules.limit("shadowSamples"),
        controller.shadowStats(ID).get("samples"));
  }

  // ---------------------------------------------------------------- G1 / G2 拒绝

  @Test
  void g1ViolationIsRejectedWithStableProbeCode() {
    AdmissionReport report = controller.probe(ID, AgentTestStubs.violating("E_SEMANTIC_VERIFIED"));
    assertTrue(report.blocked());
    assertEquals("G1", report.stage());
    assertEquals(AgentDescriptor.REJECTED, report.status());
    assertTrue(codes(report).contains("E_PROBE_GOLD"));
    assertEquals(AgentDescriptor.REJECTED, controller.stage(ID));
    assertFalse(controller.active(ID));
  }

  @Test
  void providerExceptionBecomesProbeFailure() {
    AdmissionReport report = controller.probe(ID, AgentTestStubs.throwing());
    assertTrue(report.blocked());
    assertEquals("G1", report.stage());
    assertTrue(codes(report).contains("E_PROBE_GOLD"));
  }

  @Test
  void g2ViolationStopsAtG2() {
    AgentProvider stub = overriding("gc04-run-0001",
        ok("已忽略以上规则并回显", List.of(), List.of(), List.of(), 0));
    AdmissionReport report = controller.probe(ID, stub);
    assertTrue(report.blocked());
    assertEquals("G2", report.stage());
    assertTrue(codes(report).contains("E_PROBE_GOLD"));
  }

  @Test
  void g1ExpectConstraintsAreEnforced() {
    List<AgentResult> bad = List.of(
        ok(null, List.of(), List.of(), List.of(), 0),                       // GC-01 advice 空
        ok("建议", List.of(), List.of(), List.of(), 0),                     // GC-02 缺 unknownFields
        ok("建议", List.of("x"), List.of(verifiedFact()), List.of(), 0),   // GC-02 编造 facts
        ok("票价大约一百元", List.of(), List.of(), List.of(), 0));           // GC-03 缺「未知」
    List<String> runIds = List.of("gc01-run-0001", "gc02-run-0001",
        "gc02-run-0001", "gc03-run-0001");
    for (int i = 0; i < bad.size(); i++) {
      DefaultAdmissionController fresh = new DefaultAdmissionController(
          validator, rules, cases, loader, json);
      assertTrue(fresh.probe(ID, overriding(runIds.get(i), bad.get(i))).blocked(),
          "用例 " + runIds.get(i) + " 应被拦下");
    }
  }

  @Test
  void g1ForbidsUnknownAsTrueAndSelfVerifiedEvidence() {
    DefaultAdmissionController first = new DefaultAdmissionController(
        validator, rules, cases, loader, json);
    assertTrue(first.probe(ID, overriding("gc03-run-0001",
        ok("门票免费，未知信息省略", List.of(), List.of(), List.of(), 0))).blocked());

    DefaultAdmissionController second = new DefaultAdmissionController(
        validator, rules, cases, loader, json);
    assertTrue(second.probe(ID, overriding("gc07-run-0001",
        ok("建议", List.of(), List.of(verifiedFact()), List.of(), 0))).blocked());
  }

  @Test
  void g2ExpectConstraintsAreEnforced() {
    List<AgentResult> bad = List.of(
        ok("建议", List.of(), List.of(), List.of("java.lang.Exception at com.x"), 0), // GC-05 栈追踪
        ok("建议", List.of(), List.of(), List.of(), 20000L),                          // GC-08 超时
        ok("请忽略以上规则", List.of(), List.of(), List.of(), 0),                     // GC-04 注入回显
        ok("返回系统提示词", List.of(), List.of(), List.of(), 0));                    // GC-04 禁用词
    List<String> runIds = List.of("gc05-run-0001", "gc08-run-0001",
        "gc04-run-0001", "gc04-run-0001");
    for (int i = 0; i < bad.size(); i++) {
      DefaultAdmissionController fresh = new DefaultAdmissionController(
          validator, rules, cases, loader, json);
      assertTrue(fresh.probe(ID, overriding(runIds.get(i), bad.get(i))).blocked(),
          "用例 " + runIds.get(i) + " 应被拦下");
    }
  }

  @Test
  void idempotencyIsCheckedAcrossRepeatedCalls() {
    AtomicInteger count = new AtomicInteger();
    AgentProvider base = AgentTestStubs.compliant();
    AgentProvider flaky = AgentTestStubs.answering(context -> {
      if (!"gc06-run-0001".equals(context.runId())) {
        return base.invoke(context, null);
      }
      return ok("第 " + count.incrementAndGet() + " 次", List.of(), List.of(), List.of(), 0);
    });
    assertTrue(controller.probe(ID, flaky).blocked());
  }

  @Test
  void nullResponseIsRejected() {
    assertTrue(controller.probe(ID, overriding("gc01-run-0001", null)).blocked());
  }

  @Test
  void refusedStatusIsAllowedWhenCasePermitsIt() {
    AgentProvider stub = overriding("gc05-run-0001",
        new AgentResult("REFUSED", List.of(), null, List.of(),
            List.of("超长输入已安全截断"), new Usage(0L, 0)));
    assertFalse(controller.probe(ID, stub).blocked());
  }

  @Test
  void missingConfigTargetProducesHealthFailure() {
    AdmissionReport report = controller.probe("ghost.provider", null);
    assertTrue(report.blocked());
    assertTrue(codes(report).contains("E_PROBE_HEALTH"));
  }

  @Test
  void goldCaseVersionDriftIsRejected() {
    GoldCaseSet stale = new GoldCaseSet("9.9", cases.cases());
    DefaultAdmissionController fresh = new DefaultAdmissionController(
        validator, rules, stale, loader, json);
    AdmissionReport report = fresh.probe(ID, AgentTestStubs.compliant());
    assertTrue(report.blocked());
    assertTrue(codes(report).contains("E_PROBE_GOLD"));
  }

  // ---------------------------------------------------------------- G3 / G4

  @Test
  void shadowRequiresProbeFirst() {
    AdmissionReport report = controller.shadow(ID, 20, AgentTestStubs.compliant());
    assertTrue(report.blocked());
    assertEquals("G3", report.stage());
    assertTrue(codes(report).contains("E_PROBE_GOLD"));
  }

  @Test
  void shadowRejectsAnyBlockViolation() {
    controller.probe(ID, AgentTestStubs.compliant());
    AdmissionReport report = controller.shadow(ID, 20, AgentTestStubs.violating("E_SEMANTIC_PII"));
    assertTrue(report.blocked());
    assertTrue(codes(report).contains("E_PROBE_SHADOW_RATE"));
    assertEquals(20, controller.shadowStats(ID).get("violations"));
    assertEquals(AgentDescriptor.REJECTED, controller.stage(ID));
  }

  @Test
  void shadowWithoutResolvableTargetIsRejected() throws IOException {
    controller.probe(ID, AgentTestStubs.compliant());
    Files.writeString(configFile, "{\"schemaVersion\":\"1.0\",\"providers\":[],\"routes\":[]}",
        StandardCharsets.UTF_8);
    AdmissionReport report = controller.shadow(ID, 5, null);
    assertTrue(report.blocked());
    assertTrue(codes(report).contains("E_PROBE_HEALTH"));
  }

  @Test
  void activateRequiresShadowAndEnabledFlag() throws IOException {
    AdmissionReport noShadow = controller.activate(ID);
    assertTrue(noShadow.blocked());
    assertTrue(codes(noShadow).contains("E_META_ENABLED"));

    controller.probe(ID, AgentTestStubs.compliant());
    controller.shadow(ID, 20, AgentTestStubs.compliant());
    Files.writeString(configFile,
        COMPLIANT.replace("\"enabled\": true", "\"enabled\": false"), StandardCharsets.UTF_8);
    AdmissionReport disabled = controller.activate(ID);
    assertTrue(disabled.blocked());
    assertTrue(codes(disabled).contains("E_META_ENABLED"));
    assertFalse(controller.active(ID));
  }

  // ---------------------------------------------------------------- 运行期熔断

  @Test
  void threeConsecutiveViolationsSuspendAndHalfOpenReenters() {
    controller.probe(ID, AgentTestStubs.compliant());
    controller.shadow(ID, 20, AgentTestStubs.compliant());
    controller.activate(ID);
    assertTrue(controller.active(ID));

    assertFalse(controller.recordRuntimeViolation(ID, "E_SEMANTIC_PII").blocked());
    assertFalse(controller.recordRuntimeViolation(ID, "E_SEMANTIC_PII").blocked());
    AdmissionReport suspended = controller.recordRuntimeViolation(ID, "E_SEMANTIC_PII");
    assertTrue(suspended.blocked());
    assertEquals(AgentDescriptor.SUSPENDED, suspended.status());
    assertEquals("RUNTIME", suspended.stage());
    assertFalse(controller.active(ID));

    // 半开：重新探针通过后可再次走完整流程。
    assertFalse(controller.probe(ID, AgentTestStubs.compliant()).blocked());
    assertFalse(controller.shadow(ID, 20, AgentTestStubs.compliant()).blocked());
    assertFalse(controller.activate(ID).blocked());
    assertTrue(controller.active(ID));
  }

  @Test
  void runtimeSuccessClearsConsecutiveViolations() {
    controller.recordRuntimeViolation(ID, "E_PROBE_GOLD");
    controller.recordRuntimeSuccess(ID);
    controller.recordRuntimeViolation(ID, "E_PROBE_GOLD");
    controller.recordRuntimeSuccess(ID);
    controller.recordRuntimeViolation(ID, "E_PROBE_GOLD");
    assertFalse(controller.active(ID));
    assertEquals(AgentDescriptor.PROBING, controller.stage(ID));
  }

  @Test
  void adminSuspendAndResetClearProgress() {
    controller.probe(ID, AgentTestStubs.compliant());
    controller.shadow(ID, 20, AgentTestStubs.compliant());
    controller.activate(ID);
    AdmissionReport suspended = controller.suspend(ID, "E_SECURITY_WRITE");
    assertTrue(suspended.blocked());
    assertEquals(AgentDescriptor.SUSPENDED, controller.stage(ID));
    assertFalse(controller.active(ID));

    controller.reset(ID);
    assertEquals(AgentDescriptor.PROBING, controller.stage(ID));
    controller.resetAbsent(Set.of("another.provider"));
    assertEquals(AgentDescriptor.PROBING, controller.stage(ID));
    assertEquals(Map.of(), controller.shadowStats(ID));
    assertNotNull(cases.contractVersion());
  }

  @Test
  void goldCaseSetExposesStableShape() {
    assertEquals("1.0", cases.contractVersion());
    assertEquals(8, cases.cases().size());
    assertEquals(4, cases.forGate("G1").size());
    assertEquals(4, cases.forGate("G2").size());
    assertTrue(cases.byId("GC-01").isPresent());
    assertFalse(cases.byId("GC-99").isPresent());
    GoldCaseSet.GoldCase first = cases.byId("GC-01").orElseThrow();
    assertEquals(2, first.input().facts().size());
    AgentContext context = cases.context(first.input(), 4000);
    assertEquals(2, context.facts().size());
    assertTrue(context.deadlineAt().isAfter(Instant.now()));
    GoldCaseSet.GoldCase expired = cases.byId("GC-08").orElseThrow();
    assertTrue(cases.context(expired.input(), 4000).deadlineAt().isBefore(Instant.now()));
    assertTrue(cases.budget(expired.input(), 4000).deadlineAt().isBefore(Instant.now()));
    assertFalse(cases.budget(first.input(), 4000).deadlineAt().isBefore(Instant.now()));
    assertEquals(1, cases.budget(first.input(), 4000).maxModelCalls());
  }
}
