package com.travelassistant.server.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.DecisionReport;
import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.DraftBudgetEstimate;
import com.travelassistant.server.application.agent.DraftCheck;
import com.travelassistant.server.application.agent.DraftDay;
import com.travelassistant.server.application.agent.DraftQuestion;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.BuiltinProviderConfiguration;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.ticket.TicketAssistantService;
import com.travelassistant.server.domain.attraction.Attraction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 三个内置 Provider 的 invoke 委托、参数映射与降级边界。 */
class BuiltinServiceProviderTest {

  private final ContractRules rules = new ContractRules();
  private final BuiltinProviderConfiguration configuration = new BuiltinProviderConfiguration();
  private PlanningService planning;
  private DecisionService decisions;
  private TicketAssistantService tickets;

  @BeforeEach
  void setUp() {
    planning = mock(PlanningService.class);
    decisions = mock(DecisionService.class);
    tickets = mock(TicketAssistantService.class);
  }

  private static AgentContext context(Map<String, Object> values) {
    return new AgentContext("run-000001", null, "PLAN_DRAFT", values,
        List.of(), Map.of(), Instant.now().plusSeconds(10));
  }

  private static RunBudget budget() {
    return new RunBudget(Instant.now().plusSeconds(10), 1, 4);
  }

  private static PlanDraft draft(String advice, List<DraftQuestion> questions,
      List<String> notices) {
    return new PlanDraft(new PlanningRequest("杭州", 2, List.of(), null, "CNY", 2),
        List.of(), null, List.of(), advice, notices, List.of("本地景点知识库"),
        questions, List.of(), List.of(), null, Instant.now());
  }

  private static DecisionReport decision(String advice, List<String> notices) {
    return new DecisionReport("trip-1", 1L, "杭州两日", null, null, null, null,
        List.of(new DecisionReport.Risk("BUDGET_OVER", "HIGH", "超出预算", "预算", "减少景点"),
            new DecisionReport.Risk("WEATHER", "INFO", "可能有雨", "天气", "带伞")),
        List.of("减少一天"), advice, notices, List.of("本系统行程数据"), Instant.now());
  }

  private static TicketAssistantService.Result ticketResult(List<String> questions,
      List<String> notices) {
    return new TicketAssistantService.Result(null, questions,
        List.of(new TicketAssistantService.Match(0, "成人票", new BigDecimal("40"))),
        new BigDecimal("40"), false, false, null, Map.of(), notices);
  }

  // ---------------------------------------------------------------- planning

  @Test
  void planningDelegatesAndMapsResult() {
    when(planning.plan(any())).thenReturn(draft("已按候选景点排好两日行程",
        List.of(new DraftQuestion("MISSING_BUDGET", "budget", "预算是多少", "填数字",
            "/api/agent/plan")), List.of("未接入实时天气")));
    AgentProvider provider = configuration.builtinPlanning(rules, planning);
    AgentResult result = provider.invoke(context(Map.of(
        "destination", "杭州", "days", 2, "preferences", List.of("人文"),
        "budget", "1000", "currency", "CNY", "travelers", 2)), budget());

    assertEquals("OK", result.status());
    // 信封 advice 只留安全摘要：模型原文进 CONTENT fact，避免 S-03 / S-05 判违规
    assertNotNull(result.advice());
    assertTrue(result.advice().contains("结构化内容"));
    Fact draftFact = result.facts().stream()
        .filter(f -> "builtin.planning:draft-summary".equals(f.factId())).findFirst().orElseThrow();
    assertEquals("已按候选景点排好两日行程", draftFact.value().get("advice"));
    assertTrue(result.unknownFields().contains("budget"));
    assertTrue(result.notices().contains("未接入实时天气"));

    ArgumentCaptor<PlanningRequest> captor = ArgumentCaptor.forClass(PlanningRequest.class);
    verify(planning).plan(captor.capture());
    assertEquals("杭州", captor.getValue().destination());
    assertEquals(2, captor.getValue().days());
    assertEquals(List.of("人文"), captor.getValue().preferences());
    assertEquals(new BigDecimal("1000"), captor.getValue().budget());
    assertEquals(2, captor.getValue().travelers());
  }

  @Test
  void planningPassesDatesThrough() {
    when(planning.plan(any())).thenReturn(draft("已排好行程", List.of(), List.of()));
    configuration.builtinPlanning(rules, planning).invoke(context(Map.of(
        "destination", "十堰", "days", 2,
        "departDate", "2026-11-20", "returnDate", "2026-11-21")), budget());

    ArgumentCaptor<PlanningRequest> captor = ArgumentCaptor.forClass(PlanningRequest.class);
    verify(planning).plan(captor.capture());
    // 日期被吞掉就会一直重复问「请填写出发日期」，前端补问卡片等于白点
    assertEquals(LocalDate.of(2026, 11, 20), captor.getValue().departDate());
    assertEquals(LocalDate.of(2026, 11, 21), captor.getValue().returnDate());
  }

  @Test
  void planningIgnoresMalformedDateInsteadOfFailing() {
    when(planning.plan(any())).thenReturn(draft("已排好行程", List.of(), List.of()));
    configuration.builtinPlanning(rules, planning).invoke(context(Map.of(
        "destination", "十堰", "departDate", "2026/11/20", "returnDate", 12345)), budget());

    ArgumentCaptor<PlanningRequest> captor = ArgumentCaptor.forClass(PlanningRequest.class);
    verify(planning).plan(captor.capture());
    assertNull(captor.getValue().departDate());
    assertNull(captor.getValue().returnDate());
  }

  @Test
  void planningCarriesStructuredDraftAsOneContentFact() {
    PlanDraft full = new PlanDraft(
        new PlanningRequest("十堰", 2, List.of("自然"), new BigDecimal("1500"), "CNY", 2),
        List.of(new Attraction("hb-wudang", "武当山风景区", "十堰", "湖北省", "风景名胜",
            "5A景区", "4.8", "07:30-17:30", "240元", "十堰市丹江口", null, null, null,
            "道教名山", 32.4003, 111.0036, "知识库", List.of("本地景点知识库"),
            "unverified", "unverified", "WGS-84")),
        null, List.of(), "建议按两日安排", List.of("未接入实时天气"), List.of("本地景点知识库"),
        List.of(new DraftQuestion("MISSING_DATE", "startDate", "哪天出发", "yyyy-MM-dd",
            "/api/agent/plan")),
        List.of(new DraftDay(1, LocalDate.of(2026, 11, 12), "第 1 天",
            List.of(new DraftDay.Item("hb-wudang", "武当山风景区", "十堰", "知识库",
                "240元", "07:30-17:30", List.of(), "票价原文未核验")))),
        List.of(new DraftCheck("MISSING_BUDGET", "INFO", "budget", "预算未知", null)),
        new DraftBudgetEstimate("CNY", new BigDecimal("1500"), 2, new BigDecimal("240"),
            new BigDecimal("480"), 0, "WITHIN_KNOWN_SCOPE", "只统计已核验门票"),
        Instant.now());
    when(planning.plan(any())).thenReturn(full);

    AgentResult result = configuration.builtinPlanning(rules, planning)
        .invoke(context(Map.of("destination", "十堰", "days", 2)), budget());

    assertEquals("OK", result.status());
    Fact fact = result.facts().stream()
        .filter(f -> "builtin.planning:draft-summary".equals(f.factId()))
        .findFirst().orElseThrow();
    assertEquals("CONTENT", fact.type());
    assertEquals("PLAN_DRAFT", fact.value().get("category"));
    assertEquals("unverified", fact.evidence().verificationStatus());
    assertNotNull(fact.evidence().fetchedAt());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> candidates = (List<Map<String, Object>>) fact.value().get("candidates");
    assertEquals(1, candidates.size());
    // 与 /api/agent/plan 同键名，前端只保留一套解析
    assertEquals("武当山风景区", candidates.get(0).get("name"));
    assertEquals("07:30-17:30", candidates.get(0).get("open_time"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> days = (List<Map<String, Object>>) fact.value().get("dailySchedules");
    assertEquals(1, days.size());
    assertEquals("2026-11-12", days.get(0).get("date"));
  }

  @Test
  void planningDraftFactSurvivesContractValidation() {
    when(planning.plan(any())).thenReturn(draft("建议", List.of(), List.of()));
    AgentResult result = configuration.builtinPlanning(rules, planning)
        .invoke(context(Map.of("destination", "杭州")), budget());
    ContractValidator validator = new ContractValidator(rules);
    assertTrue(validator.validateResult(validator.toResponseMap("builtin.planning", result)).isEmpty());
  }

  @Test
  void planningProseWithBannedAssertionsDoesNotBreakTheContract() {
    // 模型常把知识库里的「免费」照抄进正文；这类断言进信封 advice 会触发 S-03
    when(planning.plan(any())).thenReturn(
        draft("太极湖免费开放，全天开放，门票 240 元", List.of(), List.of()));
    AgentResult result = configuration.builtinPlanning(rules, planning)
        .invoke(context(Map.of("destination", "十堰")), budget());
    assertEquals("OK", result.status());
    assertFalse(result.advice().contains("免费"));
    ContractValidator validator = new ContractValidator(rules);
    assertTrue(validator.validateResult(validator.toResponseMap("builtin.planning", result)).isEmpty());
  }

  @Test
  void planningWithoutAdviceIsDegraded() {
    when(planning.plan(any())).thenReturn(draft(null, List.of(), List.of()));
    AgentResult result = configuration.builtinPlanning(rules, planning)
        .invoke(context(Map.of("destination", "杭州")), budget());
    assertEquals("DEGRADED", result.status());
    assertNull(result.advice());
  }

  @Test
  void planningFailureDegradesWithoutLeakingExceptionText() {
    when(planning.plan(any())).thenThrow(new IllegalStateException("内部秘密 boom"));
    AgentResult result = configuration.builtinPlanning(rules, planning)
        .invoke(context(Map.of("destination", "杭州")), budget());
    assertEquals("REFUSED", result.status());
    assertNull(result.advice());
    assertTrue(result.notices().stream().noneMatch(n -> n.contains("boom")));
    assertTrue(result.notices().stream().noneMatch(n -> n.contains("Exception")));
  }

  @Test
  void planningToleratesMalformedOptionalInputs() {
    when(planning.plan(any())).thenReturn(draft("建议", List.of(), List.of()));
    configuration.builtinPlanning(rules, planning).invoke(context(Map.of(
        "destination", "杭州", "budget", "不是数字", "preferences", "不是数组",
        "days", "不是数字")), budget());
    ArgumentCaptor<PlanningRequest> captor = ArgumentCaptor.forClass(PlanningRequest.class);
    verify(planning).plan(captor.capture());
    assertNull(captor.getValue().budget());
    assertNull(captor.getValue().days());
    assertTrue(captor.getValue().preferences().isEmpty());
  }

  // ---------------------------------------------------------------- decision

  @Test
  void decisionRefusesWithoutExplicitUserInsteadOfUsingHashAsIdentity() {
    AgentResult result = configuration.builtinDecision(rules, decisions)
        .invoke(context(Map.of("itineraryId", "trip-1")), budget());
    assertEquals("REFUSED", result.status());
    assertTrue(result.unknownFields().contains("user"));
    verifyNoInteractions(decisions);
  }

  @Test
  void decisionRefusesWhenItineraryIdMissing() {
    AgentResult result = configuration.builtinDecision(rules, decisions)
        .invoke(context(Map.of("user", "alice")), budget());
    assertEquals("REFUSED", result.status());
    assertTrue(result.unknownFields().contains("itineraryId"));
    verifyNoInteractions(decisions);
  }

  @Test
  void decisionDelegatesWhenRealIdentityIsPresent() {
    when(decisions.review(any(), any(), any()))
        .thenReturn(decision("建议压缩第二天行程", List.of("预算数据缺失")));
    AgentResult result = configuration.builtinDecision(rules, decisions)
        .invoke(context(Map.of("itineraryId", "trip-1", "user", "alice",
            "travelers", 3, "includeWeather", false)), budget());
    assertEquals("OK", result.status());
    assertTrue(result.unknownFields().contains("BUDGET_OVER"));
    assertFalse(result.unknownFields().contains("WEATHER"));
    verify(decisions).review(org.mockito.ArgumentMatchers.eq("trip-1"),
        org.mockito.ArgumentMatchers.eq("alice"), any());
  }

  // ---------------------------------------------------------------- ticket

  @Test
  void ticketDelegatesAndAddsStructuredFact() {
    when(tickets.advise(any(), any())).thenReturn(
        ticketResult(List.of("请提供同行游客年龄"), List.of("渠道信息仅供参考")));
    AgentResult result = configuration.builtinTicket(rules, tickets)
        .invoke(context(Map.of("message", "帮我看看西湖门票")), budget());
    assertEquals("DEGRADED", result.status());
    assertNull(result.advice());
    assertTrue(result.unknownFields().contains("请提供同行游客年龄"));
    assertTrue(result.notices().stream().anyMatch(n -> n.contains("既有购票接口")));
    Fact fact = result.facts().stream()
        .filter(f -> "builtin.ticket:match-summary".equals(f.factId())).findFirst().orElseThrow();
    assertEquals("CONTENT", fact.type());
    assertEquals(1, fact.value().get("matchCount"));
    assertEquals("unverified", fact.evidence().verificationStatus());
    assertNotNull(fact.evidence().fetchedAt());
  }

  @Test
  void ticketWithCompleteConditionsProducesAdvice() {
    when(tickets.advise(any(), any())).thenReturn(ticketResult(List.of(), List.of()));
    AgentResult result = configuration.builtinTicket(rules, tickets)
        .invoke(context(Map.of("message", "两位成人")), budget());
    assertEquals("OK", result.status());
    assertNotNull(result.advice());
    assertTrue(result.unknownFields().isEmpty());
  }

  @Test
  void ticketFailureDegrades() {
    when(tickets.advise(any(), any())).thenThrow(new IllegalArgumentException("bad input"));
    AgentResult result = configuration.builtinTicket(rules, tickets)
        .invoke(context(Map.of("message", "x")), budget());
    assertEquals("REFUSED", result.status());
    assertTrue(result.notices().stream().noneMatch(n -> n.contains("bad input")));
  }

  // ---------------------------------------------------------------- descriptor

  @Test
  void descriptorsCarryCapabilityAndActiveStatus() {
    assertEquals(AgentDescriptor.ACTIVE,
        configuration.builtinPlanning(rules, planning).descriptor().status());
    assertTrue(configuration.builtinDecision(rules, decisions).descriptor()
        .capabilities().contains("DECISION_ADVICE"));
    assertTrue(configuration.builtinTicket(rules, tickets).descriptor()
        .capabilities().contains("TICKET_ADVICE"));
    assertEquals(rules.contractVersion(),
        configuration.builtinPlanning(rules, planning).descriptor().schemaVersion());
  }

}
