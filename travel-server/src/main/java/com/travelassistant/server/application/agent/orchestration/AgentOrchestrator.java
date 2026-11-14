package com.travelassistant.server.application.agent.orchestration;

import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentRegistry;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.HttpAgentAdapter;
import com.travelassistant.server.application.agent.spi.Resolution;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.spi.Usage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * L1 编排层：SPI 基座第一次被业务真正调用的地方。
 *
 * <p>职责只有四件：按 intent 取 {@code primary + fallback} 链、逐个 invoke、把每个响应
 * 过同一套 {@link ContractValidator}、失败切下一个。全失败时回退 {@code builtin.rules}
 * 的「只有事实、没有建议」。不做候选生成、不做评委打分、不做两轮投票——那是后续评委
 * Provider（购票、停车推荐等）就位后才接的协作模式。
 *
 * <p>边界：{@code /api/agent/plan} 仍然直连 PlanningService，本类不改它；
 * 运行期违规计数由 HttpAgentAdapter 自带的 runtimeSink 上报，这里不重复计数，
 * 也不改注册表状态。
 */
@Service
public class AgentOrchestrator {

  private static final String STATUS_OK = "OK";
  private static final String STATUS_DEGRADED = "DEGRADED";
  private static final String STATUS_REFUSED = "REFUSED";
  private static final String RULES_PROVIDER = "builtin.rules";

  private final AgentRegistry registry;
  private final ContractValidator validator;
  private final ContractRules rules;
  private final AnchorFactService anchors;
  private final SummaryComposer summaries;
  private final int maxModelCalls;
  private final int maxToolCalls;

  public AgentOrchestrator(AgentRegistry registry, ContractValidator validator,
      ContractRules rules, AnchorFactService anchors, SummaryComposer summaries,
      @Value("${travel.agent.budget.plan-max-model-calls:1}") int maxModelCalls,
      @Value("${travel.agent.budget.plan-max-tool-calls:4}") int maxToolCalls) {
    this.registry = registry;
    this.validator = validator;
    this.rules = rules;
    this.anchors = anchors;
    this.summaries = summaries;
    this.maxModelCalls = maxModelCalls;
    this.maxToolCalls = maxToolCalls;
  }

  public DispatchOutcome dispatch(DispatchRequest request) {
    String intent = request == null ? null : request.intent();
    if (intent == null || !rules.enums("capability").contains(intent)) {
      throw new IllegalArgumentException("不支持的 intent：" + intent);
    }
    Map<String, Object> context = request.context() == null ? Map.of() : request.context();
    Map<String, String> hints = request.hints() == null ? Map.of() : request.hints();

    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    List<Fact> inputFacts = anchors.build(intent, context, notices, unknown);

    long timeoutMs = rules.limitBound("timeoutMs", "default");
    Instant deadline = Instant.now().plusMillis(timeoutMs);
    AgentContext agentContext = new AgentContext("run-" + UUID.randomUUID(), null, intent,
        context, inputFacts, hints, deadline);
    RunBudget budget = new RunBudget(deadline, maxModelCalls, maxToolCalls);

    List<String> chain = chain(intent);
    List<DispatchOutcome.Attempt> attempts = new ArrayList<>();
    for (String providerId : chain) {
      Optional<AgentProvider> found = registry.find(providerId);
      if (found.isEmpty()) {
        attempts.add(new DispatchOutcome.Attempt(providerId, "SKIPPED", "NOT_FOUND", List.of()));
        continue;
      }
      AgentResult raw;
      try {
        raw = found.get().invoke(agentContext, budget);
      } catch (HttpAgentAdapter.ContractViolationException e) {
        attempts.add(new DispatchOutcome.Attempt(providerId, "REJECTED",
            "CONTRACT_VIOLATION", e.failures().stream().map(RuleFailure::code).toList()));
        continue;
      } catch (RuntimeException e) {
        // 异常文本不外泄给调用方，也不写进响应。
        attempts.add(new DispatchOutcome.Attempt(providerId, "REJECTED", "EXCEPTION", List.of()));
        continue;
      }
      AgentResult result = normalize(raw);
      List<RuleFailure> failures = validator.validateResult(
          validator.toResponseMap(providerId, result));
      if (!failures.isEmpty()) {
        attempts.add(new DispatchOutcome.Attempt(providerId, "REJECTED",
            "CONTRACT_VIOLATION", failures.stream().map(RuleFailure::code).toList()));
        continue;
      }
      if (STATUS_REFUSED.equals(result.status())) {
        attempts.add(new DispatchOutcome.Attempt(providerId, STATUS_REFUSED, "REFUSED", List.of()));
        continue;
      }
      attempts.add(new DispatchOutcome.Attempt(providerId, result.status(), "OK", List.of()));
      return success(intent, providerId, result, attempts, notices, unknown);
    }

    return exhausted(intent, chain, attempts, inputFacts, notices, unknown);
  }

  /** primary 优先，fallback 按配置顺序，去重后再保证 builtin.rules 兜底在链尾。 */
  private List<String> chain(String intent) {
    Resolution resolution = registry.resolve(intent, null);
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (resolution.primary() != null) {
      ids.add(resolution.primary());
    }
    if (resolution.fallback() != null) {
      ids.addAll(resolution.fallback());
    }
    if (registry.find(RULES_PROVIDER).isPresent()) {
      ids.add(RULES_PROVIDER);
    }
    return List.copyOf(ids);
  }

  private DispatchOutcome success(String intent, String providerId, AgentResult result,
      List<DispatchOutcome.Attempt> attempts, List<String> anchorNotices,
      List<String> anchorUnknown) {
    boolean usedFallback = attempts.size() > 1;
    String status = usedFallback && STATUS_OK.equals(result.status())
        ? STATUS_DEGRADED : result.status();
    List<String> notices = new ArrayList<>(result.notices());
    notices.addAll(anchorNotices);
    if (usedFallback) {
      notices.add("主 Provider 未产出可用结果，已切换备用 Provider");
    }
    List<String> unknown = new ArrayList<>(result.unknownFields());
    unknown.addAll(anchorUnknown);
    // 模型只做表达：归纳句过了数字校验才顶替 Provider 的模板 advice，否则原样保留。
    String summary = summaries == null ? null : summaries.compose(intent, result);
    String advice = summary == null ? result.advice() : summary;
    AgentResult merged = new AgentResult(status, result.facts(), advice,
        cap(unknown, rules.limit("maxUnknownFields")),
        cap(notices, rules.limit("maxNotices")), result.usage());
    return new DispatchOutcome(intent, status, providerId, merged,
        List.copyOf(attempts), registry.version());
  }

  private DispatchOutcome exhausted(String intent, List<String> chain,
      List<DispatchOutcome.Attempt> attempts, List<Fact> facts,
      List<String> anchorNotices, List<String> anchorUnknown) {
    List<String> notices = new ArrayList<>(anchorNotices);
    notices.add("全部 Provider 不可用，本次只返回已核实事实，不生成建议");
    AgentResult refused = new AgentResult(STATUS_REFUSED, facts, null,
        cap(anchorUnknown, rules.limit("maxUnknownFields")),
        cap(notices, rules.limit("maxNotices")), new Usage(0L, 0));
    String providerId = chain.contains(RULES_PROVIDER) ? RULES_PROVIDER : "none";
    return new DispatchOutcome(intent, STATUS_REFUSED, providerId, refused,
        List.copyOf(attempts), registry.version());
  }

  private static AgentResult normalize(AgentResult raw) {
    if (raw == null) {
      return new AgentResult(STATUS_REFUSED, List.of(), null, List.of(), List.of(),
          new Usage(0L, 0));
    }
    return new AgentResult(raw.status(),
        raw.facts() == null ? List.of() : raw.facts(),
        raw.advice(),
        raw.unknownFields() == null ? List.of() : raw.unknownFields(),
        raw.notices() == null ? List.of() : raw.notices(),
        raw.usage() == null ? new Usage(0L, 0) : raw.usage());
  }

  /** 截断到契约上限；超出的条目不静默补回，避免响应反过来被校验器拒绝。 */
  private static List<String> cap(List<String> values, int max) {
    if (values.size() <= max) {
      return List.copyOf(values);
    }
    return List.copyOf(values.subList(0, max));
  }

  /** 供 REST 层做脱敏视图时复用，避免各处重复拼装。 */
  public Map<String, Object> contractBody(DispatchOutcome outcome) {
    Map<String, Object> body = new LinkedHashMap<>(
        validator.toResponseMap(outcome.providerId(), outcome.result()));
    return body;
  }
}
