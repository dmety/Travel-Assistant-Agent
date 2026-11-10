package com.travelassistant.server.application.agent.vote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentRegistry;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 主 Agent 的两轮投票编排层。刻意不改动 {@code AgentOrchestrator}：那边是单轮路由与
 * 降级，本类是独立的协作链路，两者互不影响。
 *
 * <p>第一轮征集事实并记录 {@code providerId → ownFactIds}；主 Agent 生成 N 套候选方案，
 * 每套绑定真实 {@code factId}；第二轮把方案一次性发给每个评委，收齐评分票后交给
 * {@link PlanVoteTally} 确定性计票。评委响应统一过 {@code ContractValidator}，
 * 违规或异常只记编排层 notice，不改注册表状态、不计 Provider 违规。
 *
 * <p>候选方案「模型优先 + 规则兜底」：模型输出必须引用本轮真实存在的事实 ID，
 * 任何编造、格式错误、无密钥或调用失败都自动降级成确定性规则草案，绝不静默采信。
 */
@Service
public class VoteOrchestrator {

  private static final String INTENT = "DECISION_ADVICE";
  private static final int MAX_PLANS = 5;
  private static final Set<String> FOCUS = Set.of("COST", "FOOD", "WALK", "BALANCED");
  private static final String MODEL_SYSTEM =
      "你是旅行方案草案生成器。只返回 JSON 数组，每个元素含 planId（A 到 E 的大写字母）、"
          + "title、focus（COST / FOOD / WALK / BALANCED 之一）、groundingFactIds（字符串数组）。"
          + "groundingFactIds 只能从给定的事实 ID 列表里选，不得编造。不要输出解释、"
          + "Markdown 或代码块，不要输出价格、网址或事实之外的内容。";

  private final AgentRegistry registry;
  private final ContractValidator validator;
  private final ContractRules rules;
  private final DeepSeekClient model;
  private final ObjectMapper json;

  public VoteOrchestrator(AgentRegistry registry, ContractValidator validator,
      ContractRules rules, DeepSeekClient model, ObjectMapper json) {
    this.registry = registry;
    this.validator = validator;
    this.rules = rules;
    this.model = model;
    this.json = json;
  }

  public record VoteRequest(String intent, Map<String, Object> context,
      Map<String, String> hints, List<String> judges) {
  }

  public record VoteRun(String status, String intent, String registryVersion,
      String candidateSource, List<String> judges, List<Map<String, Object>> plans,
      PlanVoteTally.VoteOutcome outcome, List<String> notices) {
  }

  public VoteRun vote(VoteRequest request) {
    VoteRequest safe = request == null
        ? new VoteRequest(null, Map.of(), Map.of(), List.of()) : request;
    if (safe.intent() == null || !INTENT.equals(safe.intent())) {
      throw new IllegalArgumentException("投票只支持 DECISION_ADVICE intent");
    }

    List<String> judges = resolveJudges(safe.judges());
    List<String> notices = new ArrayList<>();
    Map<String, Object> baseContext = safe.context() == null
        ? new LinkedHashMap<>() : new LinkedHashMap<>(safe.context());
    Map<String, String> baseHints = safe.hints() == null
        ? new LinkedHashMap<>() : new LinkedHashMap<>(safe.hints());
    long timeout = rules.limitBound("timeoutMs", "default");
    Instant deadline = Instant.now().plusMillis(timeout);
    RunBudget budget = new RunBudget(deadline, 2, 8);

    // 第一轮：征集事实。每个评委只拿到公开行程输入，不接触候选方案。
    Map<String, List<Fact>> firstRoundFacts = new LinkedHashMap<>();
    Set<String> allFactIds = new LinkedHashSet<>();
    List<String> participating = new ArrayList<>();
    for (String judge : judges) {
      AgentProvider provider = registry.find(judge).orElse(null);
      if (provider == null) {
        notices.add("评委 " + judge + " 未注册，本轮不参与");
        continue;
      }
      Map<String, String> hints = new LinkedHashMap<>(baseHints);
      hints.put(JudgeProviderConfiguration.HINT_PHASE,
          JudgeProviderConfiguration.PHASE_COLLECT);
      // 不用 Map.copyOf：请求 context 允许出现 null 值，copyOf 会直接抛 NPE。
      AgentContext context = new AgentContext("vote-" + UUID.randomUUID(), null, INTENT,
          new LinkedHashMap<>(baseContext), List.of(), new LinkedHashMap<>(hints), deadline);
      try {
        AgentResult result = provider.invoke(context, budget);
        if (!validator.validateResult(validator.toResponseMap(judge, result)).isEmpty()) {
          throw new IllegalStateException("评委响应未过契约校验");
        }
        List<Fact> facts = result.facts() == null ? List.of() : result.facts();
        firstRoundFacts.put(judge, facts);
        for (Fact fact : facts) {
          allFactIds.add(fact.factId());
        }
        if (result.notices() != null) {
          notices.addAll(result.notices());
        }
        participating.add(judge);
      } catch (RuntimeException e) {
        notices.add("评委 " + judge + " 第一轮不可用，本轮不参与");
      }
    }
    if (participating.isEmpty()) {
      notices.add("没有可用评委，本次投票没有产生推荐");
    }

    // 主 Agent 生成候选方案：模型优先，任何异常都降级为确定性规则草案。
    List<Map<String, Object>> plans = modelPlans(baseContext, allFactIds, notices);
    String candidateSource = "MODEL";
    if (plans.isEmpty()) {
      plans = rulePlans(allFactIds);
      candidateSource = "RULES";
    }

    // 第二轮：一次性把全部方案发给每个评委，收评分票。
    List<PlanVoteTally.BallotBatch> batches = new ArrayList<>();
    Map<String, Object> scoreContext = new LinkedHashMap<>(baseContext);
    scoreContext.put(JudgeProviderConfiguration.PLANS_KEY, plans);
    for (String judge : participating) {
      AgentProvider provider = registry.find(judge).orElse(null);
      if (provider == null) {
        continue;
      }
      Map<String, String> hints = new LinkedHashMap<>(baseHints);
      hints.put(JudgeProviderConfiguration.HINT_PHASE,
          JudgeProviderConfiguration.PHASE_SCORE);
      AgentContext context = new AgentContext("vote-" + UUID.randomUUID(), null, INTENT,
          new LinkedHashMap<>(scoreContext), firstRoundFacts.getOrDefault(judge, List.of()),
          new LinkedHashMap<>(hints), deadline);
      try {
        AgentResult result = provider.invoke(context, budget);
        if (!validator.validateResult(validator.toResponseMap(judge, result)).isEmpty()) {
          throw new IllegalStateException("评委响应未过契约校验");
        }
        Map<String, Object> body = validator.toResponseMap(judge, result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = body.get("facts") instanceof List<?> list
            ? (List<Map<String, Object>>) list : List.of();
        Set<String> own = new LinkedHashSet<>();
        for (Fact fact : firstRoundFacts.getOrDefault(judge, List.of())) {
          own.add(fact.factId());
        }
        batches.add(new PlanVoteTally.BallotBatch(judge, facts, own));
        if (result.notices() != null) {
          notices.addAll(result.notices());
        }
      } catch (RuntimeException e) {
        notices.add("评委 " + judge + " 第二轮不可用，按未交票处理");
      }
    }

    List<String> planIds = plans.stream()
        .map(p -> String.valueOf(p.get(JudgeProviderConfiguration.PLAN_ID))).toList();
    PlanVoteTally.VoteOutcome outcome = PlanVoteTally.tally(batches, planIds,
        JudgeProviderConfiguration.rubrics());
    notices.addAll(outcome.notices());
    String status = outcome.winner() == null ? "DEGRADED" : "OK";
    if (status.equals("DEGRADED")) {
      notices.add("本轮没有产生可推荐方案，未编造结论");
    }
    return new VoteRun(status, INTENT, registry.version(), candidateSource,
        participating, plans, outcome, notices);
  }

  private List<String> resolveJudges(List<String> requested) {
    List<String> allowed = requested == null || requested.isEmpty()
        ? JudgeProviderConfiguration.DEFAULT_JUDGES : requested;
    List<String> out = new ArrayList<>();
    for (String id : allowed) {
      if (!JudgeProviderConfiguration.DEFAULT_JUDGES.contains(id)) {
        continue;
      }
      if (registry.find(id).isPresent() && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  /** 模型生成候选。任何失败都返回空列表，由调用方降级到规则草案。 */
  private List<Map<String, Object>> modelPlans(Map<String, Object> context,
      Set<String> factIds, List<String> notices) {
    if (!model.configured()) {
      notices.add("未配置模型密钥，候选方案改用确定性规则草案");
      return List.of();
    }
    String allowed = String.join(", ", factIds);
    String user = "可用事实 ID：" + allowed + "；行程输入：" + context;
    try {
      String output = model.complete(MODEL_SYSTEM, user);
      JsonNode root = json.readTree(cleanFence(output));
      if (root == null || !root.isArray()) {
        throw new IllegalStateException("模型输出不是数组");
      }
      List<Map<String, Object>> plans = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      for (JsonNode node : root) {
        String planId = node.path("planId").asText("");
        String focus = node.path("focus").asText("");
        if (!planId.matches("[A-E]") || !seen.add(planId) || !FOCUS.contains(focus)) {
          throw new IllegalStateException("模型输出的方案 ID 或侧重非法");
        }
        List<String> grounding = new ArrayList<>();
        for (JsonNode fid : node.path("groundingFactIds")) {
          String id = fid.asText("");
          if (!factIds.contains(id)) {
            throw new IllegalStateException("模型引用了不存在的事实 ID");
          }
          grounding.add(id);
        }
        plans.add(plan(planId, node.path("title").asText("候选方案"), focus, grounding));
        if (plans.size() >= MAX_PLANS) {
          break;
        }
      }
      if (plans.isEmpty()) {
        throw new IllegalStateException("模型没有生成候选方案");
      }
      return plans;
    } catch (RuntimeException | JsonProcessingException e) {
      notices.add("模型候选不可用，已降级为确定性规则草案");
      return List.of();
    }
  }

  private static String cleanFence(String output) {
    String text = output == null ? "" : output.strip();
    if (text.startsWith("```")) {
      int first = text.indexOf('\n');
      int last = text.lastIndexOf("```");
      if (first > 0 && last > first) {
        text = text.substring(first + 1, last).strip();
      }
    }
    return text;
  }

  /** 规则兜底：三套侧重不同的方案，绑定第一轮全部真实事实 ID。 */
  private static List<Map<String, Object>> rulePlans(Set<String> factIds) {
    List<String> grounding = new ArrayList<>(factIds);
    return List.of(
        plan("A", "控制花费", "COST", grounding),
        plan("B", "照顾餐饮", "FOOD", grounding),
        plan("C", "减少步行", "WALK", grounding));
  }

  private static Map<String, Object> plan(String planId, String title, String focus,
      List<String> grounding) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(JudgeProviderConfiguration.PLAN_ID, planId);
    out.put("title", title);
    out.put(JudgeProviderConfiguration.PLAN_FOCUS, focus);
    out.put("groundingFactIds", List.copyOf(grounding));
    return out;
  }
}
