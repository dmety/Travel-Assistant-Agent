package com.travelassistant.server.agent.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentRegistry;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.vote.JudgeProviderConfiguration;
import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 用户定案的协作范式：所有子 Agent 的信息全量给主 Agent → 主 Agent 整理草案 → 子 Agent 投票。
 * 这里验证信息确实被汇集、草案只由真实事实组成、评委按草案内容而不是标签投票。
 */
class VoteDraftFlowTest {

  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private final ObjectMapper json = new ObjectMapper();
  private final JudgeProviderConfiguration config = new JudgeProviderConfiguration();

  private Map<String, AgentProvider> providers() {
    Map<String, AgentProvider> out = new LinkedHashMap<>();
    out.put(JudgeProviderConfiguration.JUDGE_TICKET, config.judgeTicket(rules));
    out.put(JudgeProviderConfiguration.JUDGE_FOOD, config.judgeFood(rules));
    out.put(JudgeProviderConfiguration.JUDGE_PARKING, config.judgeParking(rules));
    return out;
  }

  private VoteOrchestrator orchestrator(DeepSeekClient model, Map<String, AgentProvider> beans) {
    AgentRegistry registry = mock(AgentRegistry.class);
    when(registry.find(anyString())).thenAnswer(inv ->
        Optional.ofNullable(beans.get(inv.getArgument(0, String.class))));
    when(registry.version()).thenReturn("test-registry");
    return new VoteOrchestrator(registry, validator, rules, model, json);
  }

  private static DeepSeekClient absentModel() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(false);
    return model;
  }

  private static VoteOrchestrator.VoteRequest request(Map<String, Object> context) {
    return new VoteOrchestrator.VoteRequest("DECISION_ADVICE", context, Map.of(), List.of());
  }

  private static Map<String, Object> card(String type, String intent, Object data) {
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("type", type);
    card.put("intent", intent);
    card.put("data", data);
    return card;
  }

  private static Map<String, Object> ticketCard() {
    Map<String, Object> guidance = new LinkedHashMap<>();
    guidance.put("method", JudgeProviderConfiguration.TICKET_METHOD);
    guidance.put("stockVerified", false);
    guidance.put("channels", List.of(Map.of(
        "name", "八达岭长城官方参观／购票指引",
        "url", "https://www.badaling.cn/website/pc/index.html",
        "note", "官网指引入口，不保证为直接下单页面")));
    return card("envelope", "TICKET_ADVICE", Map.of("ticketGuidance", guidance));
  }

  private static List<Map<String, Object>> sampleCards() {
    List<Map<String, Object>> cards = new ArrayList<>();
    cards.add(card("places", null, List.of(
        Map.of("name", "黄鹤楼"), Map.of("name", "东湖"))));
    cards.add(card("food", "FOOD_RECOMMEND", List.of(Map.of("name", "热干面"))));
    cards.add(ticketCard());
    return cards;
  }

  @Test
  void allSubAgentFactsFeedTheMainAgentDraft() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", "武汉");
    context.put("days", 2);
    context.put(JudgeProviderConfiguration.COLLECTED_CARDS_KEY, sampleCards());

    VoteOrchestrator.VoteRun run = orchestrator(absentModel(), providers())
        .vote(request(context));

    assertThat(run.status()).isEqualTo("OK");
    // 3 张工具卡 + 3 位评委的第一轮事实，全部汇集给主 Agent
    assertThat(run.collectedFactCount()).isEqualTo(6);
    assertThat(run.notices()).anyMatch(n -> n.contains("汇集 3 条子 Agent 事实"));

    Map<String, Object> draft = run.draft();
    assertThat(draft).containsKeys("dailySchedules", "food", "weather",
        "ticketGuidance", "unknowns", "groundingFactIds", "source");
    assertThat(draft.get("source")).isEqualTo("MAIN_AGENT");
    assertThat(draft.get("destination")).isEqualTo("武汉");
    assertThat(draft.get("days")).isEqualTo(2);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> daily =
        (List<Map<String, Object>>) draft.get("dailySchedules");
    assertThat(daily).hasSize(2);
    assertThat(daily.get(0).get("items")).isEqualTo(List.of("黄鹤楼", "东湖"));
    assertThat((List<?>) daily.get(1).get("items")).isEmpty();

    @SuppressWarnings("unchecked")
    List<String> food = (List<String>) draft.get("food");
    assertThat(food).contains("热干面");

    @SuppressWarnings("unchecked")
    Map<String, Object> guidance =
        (Map<String, Object>) draft.get("ticketGuidance");
    assertThat(guidance.get("stockVerified")).isEqualTo(false);
    assertThat(guidance.get("method")).isEqualTo(JudgeProviderConfiguration.TICKET_METHOD);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> channels =
        (List<Map<String, Object>>) guidance.get("channels");
    assertThat(channels).hasSize(1);
    assertThat(channels.get(0).get("url"))
        .isEqualTo("https://www.badaling.cn/website/pc/index.html");

    @SuppressWarnings("unchecked")
    List<String> grounding = (List<String>) draft.get("groundingFactIds");
    assertThat(grounding).contains("collected.card.1", "collected.card.3");
  }

  @Test
  void draftWithoutCollectedFactsDoesNotInventAnything() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", "北京");

    VoteOrchestrator.VoteRun run = orchestrator(absentModel(), providers())
        .vote(request(context));

    assertThat(run.collectedFactCount()).isEqualTo(3);
    Map<String, Object> draft = run.draft();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> daily =
        (List<Map<String, Object>>) draft.get("dailySchedules");
    assertThat(daily).allSatisfy(day -> assertThat(day.get("items")).isEqualTo(List.of()));
    @SuppressWarnings("unchecked")
    List<String> unknowns = (List<String>) draft.get("unknowns");
    assertThat(unknowns).contains("景点候选未提供", "餐饮候选与忌口信息未提供",
        "未提供经核验的官方购票入口");
    @SuppressWarnings("unchecked")
    Map<String, Object> guidance =
        (Map<String, Object>) draft.get("ticketGuidance");
    assertThat(guidance.get("stockVerified")).isEqualTo(false);
    assertThat((List<?>) guidance.get("channels")).isEmpty();
  }

  @Test
  void modelReceivesFactContentsNotOnlyIds() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "[{\"planId\":\"A\",\"title\":\"黄鹤楼与东湖两日\",\"focus\":\"BALANCED\","
            + "\"groundingFactIds\":[\"collected.card.1\"]}]");
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", "武汉");
    context.put(JudgeProviderConfiguration.COLLECTED_CARDS_KEY, sampleCards());

    VoteOrchestrator.VoteRun run = orchestrator(model, providers()).vote(request(context));

    assertThat(run.candidateSource()).isEqualTo("MODEL");
    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(model).complete(anyString(), prompt.capture());
    assertThat(prompt.getValue()).contains("子 Agent 汇集事实")
        .contains("collected.card.1").contains("黄鹤楼")
        .doesNotContain("\"type\":\"places\"");
    assertThat(run.draft().get("destination")).isEqualTo("武汉");
  }

  @Test
  void judgesScoreRealDraftContentInsteadOfOnlyFocusLabels() {
    AgentProvider ticketJudge = config.judgeTicket(rules);
    AgentProvider foodJudge = config.judgeFood(rules);
    Map<String, Object> withContent = new LinkedHashMap<>();
    withContent.put("ticketGuidance", Map.of("channels",
        List.of(Map.of("name", "官方入口", "url", "https://example.com"))));
    withContent.put("food", List.of("热干面"));
    withContent.put("unknowns", List.of());
    withContent.put("dailySchedules", List.of(Map.of("day", 1, "items", List.of("黄鹤楼"))));

    Map<String, Object> withDraftScores = scores(ticketJudge,
        scoreContext(Map.of("draft", withContent)));
    Map<String, Object> withoutDraftScores = scores(ticketJudge, scoreContext(Map.of()));
    Map<String, Object> foodWithDraft = scores(foodJudge,
        scoreContext(Map.of("draft", withContent)));
    Map<String, Object> foodWithoutDraft = scores(foodJudge, scoreContext(Map.of()));

    // 有官方入口时 reservationRisk 不给高分（余票仍未核验），没草案时退回基准分
    assertThat(withDraftScores.get("reservationRisk")).isEqualTo(3);
    assertThat(withoutDraftScores.get("reservationRisk")).isEqualTo(4);
    // 美食评委看到真实餐饮候选才给覆盖高分
    assertThat(foodWithDraft.get("mealCoverage")).isEqualTo(5);
    assertThat(foodWithoutDraft.get("mealCoverage")).isEqualTo(3);
  }

  private static AgentContext scoreContext(Map<String, Object> extra) {
    Map<String, Object> context = new LinkedHashMap<>(extra);
    context.put(JudgeProviderConfiguration.PLANS_KEY,
        List.of(Map.of("planId", "A", "focus", "COST")));
    return new AgentContext("vote-test", null, "DECISION_ADVICE", context, List.of(),
        Map.of(JudgeProviderConfiguration.HINT_PHASE, JudgeProviderConfiguration.PHASE_SCORE),
        Instant.now().plusSeconds(10));
  }

  private static Map<String, Object> scores(AgentProvider judge, AgentContext context) {
    AgentResult result = judge.invoke(context,
        new RunBudget(Instant.now().plusSeconds(10), 2, 8));
    for (Fact fact : result.facts()) {
      Object category = fact.value().get("category");
      if (PlanVoteTally.SCORE_CATEGORY.equals(category)
          && fact.value().get("scores") instanceof Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
      }
    }
    return Map.of();
  }
}
