package com.travelassistant.server.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.AnchorFactService;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.orchestration.SummaryComposer;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentRegistry;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.HttpAgentAdapter;
import com.travelassistant.server.application.agent.spi.Resolution;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.spi.Usage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L1 编排层：路由、契约校验、失败切换、全失败兜底。 */
@DisplayName("L1 编排层")
class AgentOrchestratorTest {

  private static final String PRIMARY = "food.recommender";
  private static final String SECONDARY = "food.secondary";
  private static final String RULES = "builtin.rules";

  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private AgentRegistry registry;
  private AnchorFactService anchors;
  private AgentOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(AgentRegistry.class);
    anchors = mock(AnchorFactService.class);
    when(registry.version()).thenReturn("v-test");
    when(registry.resolve(eq("FOOD_RECOMMEND"), any()))
        .thenReturn(new Resolution("FOOD_RECOMMEND", PRIMARY, List.of(SECONDARY)));
    when(registry.find(SECONDARY)).thenReturn(Optional.of(
        provider(SECONDARY, context -> ok("已按备用能力整理建议"))));
    when(registry.find(RULES)).thenReturn(Optional.of(
        provider(RULES, context -> AgentResult.refused(
            context.facts(), List.of("行程建议"), "全部 Provider 不可用，本次只返回已核实事实，不生成建议"))));
    when(anchors.build(any(), any(), any(), any())).thenReturn(List.of());
    orchestrator = new AgentOrchestrator(registry, validator, rules, anchors,
        mock(SummaryComposer.class));
  }

  private DispatchRequest request() {
    return new DispatchRequest("FOOD_RECOMMEND", Map.of("city", "杭州"), Map.of());
  }

  private static AgentProvider provider(String id, Function<AgentContext, AgentResult> fn) {
    return new AgentProvider() {
      @Override
      public AgentDescriptor descriptor() {
        return new AgentDescriptor(id, "1.0.0", "1.0", Set.of("FOOD_RECOMMEND"),
            "BEAN", "NONE", true, AgentDescriptor.ACTIVE, null);
      }

      @Override
      public AgentResult invoke(AgentContext context, RunBudget budget) {
        return fn.apply(context);
      }
    };
  }

  private static AgentResult ok(String advice) {
    return new AgentResult("OK", List.of(), advice, List.of(), List.of(), new Usage(0L, 0));
  }

  @Test
  @DisplayName("primary 合规响应直接生效，响应过同一套契约校验")
  void primaryCompliantResultWins() {
    when(registry.find(PRIMARY)).thenReturn(Optional.of(
        provider(PRIMARY, context -> ok("已按已核实事实整理建议"))));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.status()).isEqualTo("OK");
    assertThat(outcome.providerId()).isEqualTo(PRIMARY);
    assertThat(outcome.attempts()).hasSize(1);
    assertThat(outcome.attempts().get(0).reason()).isEqualTo("OK");
    assertThat(validator.validateResult(orchestrator.contractBody(outcome))).isEmpty();
  }

  @Test
  @DisplayName("primary 抛契约违规异常时切下一个，并如实标 DEGRADED")
  void contractViolationFallsBackAndMarksDegraded() {
    when(registry.find(PRIMARY)).thenReturn(Optional.of(provider(PRIMARY, context -> {
      throw new HttpAgentAdapter.ContractViolationException(
          List.of(RuleFailure.block("E_SECURITY_INJECTION", "S-08", "advice", "x", "合规")));
    })));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.providerId()).isEqualTo(SECONDARY);
    assertThat(outcome.status()).isEqualTo("DEGRADED");
    assertThat(outcome.attempts()).hasSize(2);
    assertThat(outcome.attempts().get(0).reason()).isEqualTo("CONTRACT_VIOLATION");
    assertThat(outcome.attempts().get(0).codes()).contains("E_SECURITY_INJECTION");
    assertThat(outcome.result().notices()).anyMatch(n -> n.contains("切换备用"));
  }

  @Test
  @DisplayName("primary 返回非法状态被校验器拒绝，不把坏响应交给调用方")
  void invalidStatusIsDiscarded() {
    when(registry.find(PRIMARY)).thenReturn(Optional.of(provider(PRIMARY,
        context -> new AgentResult("MAYBE", List.of(), "建议", List.of(), List.of(),
            new Usage(0L, 0)))));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.providerId()).isEqualTo(SECONDARY);
    assertThat(outcome.attempts().get(0).reason()).isEqualTo("CONTRACT_VIOLATION");
    assertThat(outcome.attempts().get(0).codes()).contains("E_SCHEMA_TYPE");
  }

  @Test
  @DisplayName("primary 返回 REFUSED 时继续走 fallback")
  void refusedPrimaryContinues() {
    when(registry.find(PRIMARY)).thenReturn(Optional.of(provider(PRIMARY,
        context -> AgentResult.refused(List.of(), List.of("anchor"), "缺少锚点"))));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.providerId()).isEqualTo(SECONDARY);
    assertThat(outcome.attempts().get(0).reason()).isEqualTo("REFUSED");
  }

  @Test
  @DisplayName("全链失败时只回传输入事实，不给建议，且标 builtin.rules")
  void allFailuresReturnFactsOnly() {
    when(registry.find(PRIMARY)).thenReturn(Optional.empty());
    when(registry.find(SECONDARY)).thenReturn(Optional.empty());
    // 让兜底 Provider 也抛异常，验证编排层不外泄异常文本。
    when(registry.find(RULES)).thenReturn(Optional.of(provider(RULES, context -> {
      throw new IllegalStateException("内部栈追踪不得外泄");
    })));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.status()).isEqualTo("REFUSED");
    assertThat(outcome.providerId()).isEqualTo(RULES);
    assertThat(outcome.result().advice()).isNull();
    assertThat(outcome.attempts()).extracting(DispatchOutcome.Attempt::reason)
        .contains("NOT_FOUND", "EXCEPTION");
    assertThat(outcome.result().notices().toString()).doesNotContain("内部栈追踪");
    assertThat(validator.validateResult(orchestrator.contractBody(outcome))).isEmpty();
  }

  @Test
  @DisplayName("锚点缺失提示与 unknownFields 合并进最终响应")
  void anchorIssuesAreMerged() {
    when(anchors.build(any(), any(), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      List<String> notices = invocation.getArgument(2);
      @SuppressWarnings("unchecked")
      List<String> unknown = invocation.getArgument(3);
      notices.add("缺少可用锚点，请选择当日景点");
      unknown.add("anchor");
      return List.of();
    });
    when(registry.find(PRIMARY)).thenReturn(Optional.of(
        provider(PRIMARY, context -> ok("已按已核实事实整理建议"))));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.result().unknownFields()).contains("anchor");
    assertThat(outcome.result().notices()).anyMatch(n -> n.contains("缺少可用锚点"));
  }

  @Test
  @DisplayName("未知 intent 直接拒绝，不进注册表")
  void unknownIntentIsRejected() {
    assertThatThrownBy(() -> orchestrator.dispatch(
        new DispatchRequest("NOT_AN_INTENT", Map.of(), Map.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的 intent");
  }

  @Test
  @DisplayName("notices 超契约上限时截断，响应仍能过校验器")
  void noticesAreCapped() {
    when(anchors.build(any(), any(), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      List<String> notices = invocation.getArgument(2);
      for (int i = 0; i < 30; i++) {
        notices.add("锚点提示 " + i);
      }
      return List.of();
    });
    when(registry.find(PRIMARY)).thenReturn(Optional.of(
        provider(PRIMARY, context -> ok("已按已核实事实整理建议"))));
    DispatchOutcome outcome = orchestrator.dispatch(request());
    assertThat(outcome.result().notices()).hasSize(rules.limit("maxNotices"));
    assertThat(validator.validateResult(orchestrator.contractBody(outcome))).isEmpty();
  }
}
