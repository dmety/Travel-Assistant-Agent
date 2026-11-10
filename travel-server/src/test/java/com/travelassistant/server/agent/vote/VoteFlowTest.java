package com.travelassistant.server.agent.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

/** 两轮投票的编排层测试：真实评委 Bean + 真实契约校验器 + mock 注册表与模型。 */
class VoteFlowTest {

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

  private VoteOrchestrator orchestrator(Map<String, AgentProvider> providers,
      DeepSeekClient model) {
    AgentRegistry registry = mock(AgentRegistry.class);
    when(registry.find(anyString())).thenAnswer(inv ->
        Optional.ofNullable(providers.get(inv.getArgument(0, String.class))));
    when(registry.version()).thenReturn("test-registry");
    return new VoteOrchestrator(registry, validator, rules, model, json);
  }

  private static DeepSeekClient absentModel() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(false);
    return model;
  }

  private static VoteOrchestrator.VoteRequest request(List<String> judges) {
    return new VoteOrchestrator.VoteRequest("DECISION_ADVICE",
        Map.of("city", "北京", "budgetCny", 300), Map.of(), judges);
  }

  private static AgentContext context(String phase, Map<String, Object> extra,
      List<Fact> facts) {
    Map<String, Object> context = new LinkedHashMap<>(extra);
    Map<String, String> hints = Map.of(JudgeProviderConfiguration.HINT_PHASE, phase);
    Instant deadline = Instant.now().plusSeconds(10);
    return new AgentContext("vote-test", null, "DECISION_ADVICE", context, facts,
        hints, deadline);
  }

  private static RunBudget budget() {
    return new RunBudget(Instant.now().plusSeconds(10), 2, 8);
  }

  @Test
  void twoRoundsProduceWinnerWithThreeJudges() {
    VoteOrchestrator.VoteRun run = orchestrator(providers(), absentModel())
        .vote(request(List.of()));
    assertThat(run.status()).isEqualTo("OK");
    assertThat(run.judges()).containsExactly(
        JudgeProviderConfiguration.JUDGE_TICKET,
        JudgeProviderConfiguration.JUDGE_FOOD,
        JudgeProviderConfiguration.JUDGE_PARKING);
    assertThat(run.candidateSource()).isEqualTo("RULES");
    assertThat(run.plans()).hasSize(3);
    assertThat(run.outcome().winner()).isNotBlank();
    assertThat(run.outcome().ranking()).hasSize(3);
    assertThat(run.outcome().rejections()).isEmpty();
  }

  @Test
  void collectAndScoreResponsesPassContractValidator() {
    for (Map.Entry<String, AgentProvider> entry : providers().entrySet()) {
      String id = entry.getKey();
      AgentProvider provider = entry.getValue();
      AgentResult collected = provider.invoke(
          context(JudgeProviderConfiguration.PHASE_COLLECT, Map.of(), List.of()), budget());
      assertThat(validator.validateResult(validator.toResponseMap(id, collected)))
          .as(id + " COLLECT").isEmpty();

      List<Map<String, Object>> plans = List.of(
          Map.of("planId", "A", "focus", "COST"),
          Map.of("planId", "B", "focus", "FOOD"),
          Map.of("planId", "C", "focus", "WALK"));
      AgentResult scored = provider.invoke(context(JudgeProviderConfiguration.PHASE_SCORE,
          Map.of(JudgeProviderConfiguration.PLANS_KEY, plans), collected.facts()), budget());
      assertThat(validator.validateResult(validator.toResponseMap(id, scored)))
          .as(id + " SCORE").isEmpty();
      assertThat(scored.facts()).hasSize(3);
      for (Fact ballot : scored.facts()) {
        assertThat(ballot.type()).isEqualTo("CONTENT");
        assertThat(ballot.value()).containsEntry("category", "PLAN_SCORE")
            .containsEntry("rubric", id);
        assertThat(ballot.evidence().verificationStatus()).isEqualTo("unverified");
      }
    }
  }

  @Test
  void judgesHaveIndependentViews() {
    VoteOrchestrator.VoteRun run = orchestrator(providers(), absentModel())
        .vote(request(List.of()));
    assertThat(run.outcome().homogeneous()).isFalse();
    assertThat(run.notices()).anyMatch(n -> n.contains("停车视角暂无专属数据源"));
  }

  @Test
  void modelPlansAreAcceptedWhenTheyOnlyReferenceRealFactIds() {
    // 第一轮事实 ID 是固定前缀，模型只能引用它们。
    String payload = "[{\"planId\":\"A\",\"title\":\"省钱\",\"focus\":\"COST\","
        + "\"groundingFactIds\":[\"judge.ticket:context\"]},"
        + "{\"planId\":\"B\",\"title\":\"少走路\",\"focus\":\"WALK\","
        + "\"groundingFactIds\":[\"judge.parking:context\"]}]";
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(payload);
    VoteOrchestrator.VoteRun run = orchestrator(providers(), model).vote(request(List.of()));
    assertThat(run.candidateSource()).isEqualTo("MODEL");
    assertThat(run.plans()).hasSize(2);
    assertThat(run.outcome().winner()).isNotBlank();
  }

  @Test
  void fencedModelOutputIsAccepted() {
    String payload = "```json\n[{\"planId\":\"A\",\"title\":\"省钱\",\"focus\":\"COST\","
        + "\"groundingFactIds\":[\"judge.ticket:context\"]}]\n```";
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(payload);
    assertThat(orchestrator(providers(), model).vote(request(List.of()))
        .candidateSource()).isEqualTo("MODEL");
  }

  @Test
  void fabricatedFactIdFallsBackToRules() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "[{\"planId\":\"A\",\"title\":\"编造\",\"focus\":\"COST\","
            + "\"groundingFactIds\":[\"not-a-real-fact\"]}]");
    VoteOrchestrator.VoteRun run = orchestrator(providers(), model).vote(request(List.of()));
    assertThat(run.candidateSource()).isEqualTo("RULES");
    assertThat(run.notices()).anyMatch(n -> n.contains("降级为确定性规则草案"));
  }

  @Test
  void invalidModelJsonFallsBackToRules() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn("这不是 JSON");
    assertThat(orchestrator(providers(), model).vote(request(List.of()))
        .candidateSource()).isEqualTo("RULES");
  }

  @Test
  void modelExceptionFallsBackToRules() {
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString()))
        .thenThrow(new RuntimeException("模型不可用"));
    assertThat(orchestrator(providers(), model).vote(request(List.of()))
        .candidateSource()).isEqualTo("RULES");
  }

  @Test
  void unknownIntentIsRejected() {
    assertThatThrownBy(() -> orchestrator(providers(), absentModel()).vote(
        new VoteOrchestrator.VoteRequest("FOOD_RECOMMEND", Map.of(), Map.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestedJudgesAreFilteredToRegisteredOnes() {
    VoteOrchestrator.VoteRun run = orchestrator(providers(), absentModel())
        .vote(request(List.of(JudgeProviderConfiguration.JUDGE_TICKET, "not.a.judge")));
    assertThat(run.judges()).containsExactly(JudgeProviderConfiguration.JUDGE_TICKET);
  }

  @Test
  void failingJudgeIsRecordedAndDoesNotBreakTheRun() {
    Map<String, AgentProvider> providers = providers();
    providers.put(JudgeProviderConfiguration.JUDGE_FOOD,
        mock(AgentProvider.class, inv -> {
          throw new RuntimeException("评委不可用");
        }));
    VoteOrchestrator.VoteRun run = orchestrator(providers, absentModel())
        .vote(request(List.of()));
    assertThat(run.notices()).anyMatch(n -> n.contains("第一轮不可用"));
    assertThat(run.status()).isEqualTo("OK");
  }

  @Test
  void noAvailableJudgesProducesNoWinner() {
    VoteOrchestrator.VoteRun run = orchestrator(Map.of(), absentModel())
        .vote(request(List.of()));
    assertThat(run.outcome().winner()).isNull();
    assertThat(run.status()).isEqualTo("DEGRADED");
    assertThat(run.notices()).anyMatch(n -> n.contains("没有可用评委"));
  }

  @Test
  void localVoteCodesAreNotReportedToAdmission() {
    AgentRegistry registry = mock(AgentRegistry.class);
    Map<String, AgentProvider> providers = providers();
    when(registry.find(anyString())).thenAnswer(inv ->
        Optional.ofNullable(providers.get(inv.getArgument(0, String.class))));
    when(registry.version()).thenReturn("test-registry");
    VoteOrchestrator orchestrator = new VoteOrchestrator(
        registry, validator, rules, absentModel(), json);
    VoteOrchestrator.VoteRun run = orchestrator.vote(request(List.of()));
    assertThat(run.outcome().rejections()).isEmpty();
    org.mockito.Mockito.verify(registry, org.mockito.Mockito.never()).reports();
    org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
        .admission(anyString());
  }

  @Test
  void rubricDeclarationsAreConsistentWithJudgeIds() {
    Map<String, PlanVoteTally.Rubric> rubrics = JudgeProviderConfiguration.rubrics();
    assertThat(rubrics).hasSize(3);
    assertThat(rubrics.keySet()).containsExactlyInAnyOrder(
        JudgeProviderConfiguration.JUDGE_TICKET,
        JudgeProviderConfiguration.JUDGE_FOOD,
        JudgeProviderConfiguration.JUDGE_PARKING);
    assertThat(new ArrayList<>(rubrics.values()).stream()
        .flatMap(r -> r.criteria().stream()).toList()).doesNotHaveDuplicates();
  }
}
