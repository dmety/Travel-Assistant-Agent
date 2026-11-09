package com.travelassistant.server.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.travelassistant.server.application.agent.spi.AdmissionReport;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Resolution;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.spi.Usage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPI 值类型的字段契约测试：record 的 accessor 是运行时签名的一部分，不能只测构造。 */
class SpiTypesTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  @Test
  void evidenceAndFactExposeAllFields() {
    Evidence evidence = new Evidence("KB", "kb-1", Evidence.UNVERIFIED, NOW, "原文摘录");
    assertEquals("KB", evidence.sourceKind());
    assertEquals("kb-1", evidence.sourceId());
    assertEquals(Evidence.UNVERIFIED, evidence.verificationStatus());
    assertEquals(NOW, evidence.fetchedAt());
    assertEquals("原文摘录", evidence.excerpt());
    assertEquals(Evidence.VERIFIED, "verified");
    assertEquals(Evidence.PENDING, "pending");

    Fact fact = new Fact("a1", "ATTRACTION", Map.of("name", "西湖"), evidence);
    assertEquals("a1", fact.factId());
    assertEquals("ATTRACTION", fact.type());
    assertEquals(Map.of("name", "西湖"), fact.value());
    assertEquals(evidence, fact.evidence());
  }

  @Test
  void usageAndResultExposeAllFields() {
    Usage usage = new Usage(820L, 1);
    assertEquals(820L, usage.elapsedMs());
    assertEquals(1, usage.modelCalls());

    Fact fact = new Fact("a1", "CONTENT", Map.of(), new Evidence("EXTERNAL", null,
        Evidence.PENDING, NOW, null));
    AgentResult result = new AgentResult("OK", List.of(fact), "建议文本", List.of("人均价格"),
        List.of("数据未核验"), usage);
    assertEquals("OK", result.status());
    assertEquals(1, result.facts().size());
    assertEquals("建议文本", result.advice());
    assertEquals(List.of("人均价格"), result.unknownFields());
    assertEquals(List.of("数据未核验"), result.notices());
    assertEquals(usage, result.usage());

    AgentResult refused = AgentResult.refused(List.of(), List.of("城市"), "全部 Provider 不可用");
    assertEquals("REFUSED", refused.status());
    assertNull(refused.advice());
    assertEquals(0L, refused.usage().elapsedMs());
    assertEquals("全部 Provider 不可用", refused.notices().get(0));
  }

  @Test
  void contextBudgetAndResolutionExposeAllFields() {
    AgentContext context = new AgentContext("run-000001", "sha256:abc", "FOOD_RECOMMEND",
        Map.of("city", "武汉"), List.of(), Map.of("hint", "少辣"), NOW);
    assertEquals("run-000001", context.runId());
    assertEquals("sha256:abc", context.userIdHash());
    assertEquals("FOOD_RECOMMEND", context.intent());
    assertEquals(Map.of("city", "武汉"), context.context());
    assertTrue(context.facts().isEmpty());
    assertEquals(Map.of("hint", "少辣"), context.hints());
    assertEquals(NOW, context.deadlineAt());

    RunBudget budget = new RunBudget(NOW, 2, 8);
    assertEquals(NOW, budget.deadlineAt());
    assertEquals(2, budget.maxModelCalls());
    assertEquals(8, budget.maxToolCalls());

    Resolution resolution = new Resolution("FOOD_RECOMMEND", "food.recommender", List.of("builtin.rules"));
    assertEquals("FOOD_RECOMMEND", resolution.intent());
    assertEquals("food.recommender", resolution.primary());
    assertEquals(List.of("builtin.rules"), resolution.fallback());
  }

  @Test
  void descriptorStatusHelpers() {
    AgentDescriptor active = new AgentDescriptor("builtin.planning", "1.0.0", "1.0",
        Set.of("PLAN_DRAFT"), "BEAN", "NONE", true, AgentDescriptor.ACTIVE, null);
    assertTrue(active.active());
    assertEquals("builtin.planning", active.id());
    assertEquals("1.0.0", active.version());
    assertEquals("1.0", active.schemaVersion());
    assertEquals(Set.of("PLAN_DRAFT"), active.capabilities());
    assertEquals("BEAN", active.kind());
    assertEquals("NONE", active.sideEffect());
    assertTrue(active.enabled());
    assertEquals(AgentDescriptor.ACTIVE, active.status());
    assertNull(active.lastRejectionCode());

    AgentDescriptor rejected = new AgentDescriptor("food.recommender", "0.1.0", "1.0",
        Set.of("FOOD_RECOMMEND"), "HTTP", "NONE", false, AgentDescriptor.REJECTED, "E_META_AUTH");
    assertFalse(rejected.active());
    assertEquals("E_META_AUTH", rejected.lastRejectionCode());
  }

  @Test
  void failureAndReportHelpers() {
    RuleFailure failure = RuleFailure.block("E_META_AUTH", "M-09", "providers[0].token",
        "内联凭据", "改用 authRef");
    assertEquals("E_META_AUTH", failure.code());
    assertEquals("M-09", failure.rule());
    assertEquals(RuleFailure.BLOCK, failure.level());
    assertEquals("providers[0].token", failure.field());
    assertEquals("内联凭据", failure.actual());
    assertEquals("改用 authRef", failure.expected());
    assertEquals("", failure.hint());

    RuleFailure warning = new RuleFailure("", "M-12", RuleFailure.WARN,
        "healthPath", "null", "建议提供", "");
    AdmissionReport clean = new AdmissionReport("1.0", "food.recommender", "PROBING",
        "G0", List.of(), List.of(warning), NOW);
    assertFalse(clean.blocked());
    assertNull(clean.lastRejectionCode());
    assertEquals(1, clean.warnings().size());

    AdmissionReport blocked = new AdmissionReport("1.0", "food.recommender", "REJECTED",
        "G0", List.of(failure), List.of(), NOW);
    assertTrue(blocked.blocked());
    assertEquals("E_META_AUTH", blocked.lastRejectionCode());
    assertEquals("1.0", blocked.contractVersion());
    assertEquals("food.recommender", blocked.providerId());
    assertEquals("REJECTED", blocked.status());
    assertEquals("G0", blocked.stage());
    assertEquals(NOW, blocked.checkedAt());
  }
}
