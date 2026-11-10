package com.travelassistant.server.application.agent.vote;

import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.BuiltinServiceProvider;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Usage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 三位评委 Provider：购票、美食、停车。
 *
 * <p>本轮按用户要求「先加入投票功能，不实现其他代码」：只新增投票所需的评委适配，
 * 不改造既有购票助手 REST / 前端 / 美食 Agent，也不新建停车推荐业务功能。评委复用
 * 现有 {@code DECISION_ADVICE} 能力声明，不新增 capability 枚举，契约仍是 1.0。
 *
 * <p>两轮协议：{@code hints.votePhase=COLLECT} 时各自上报领域事实；
 * {@code SCORE} 时按自己声明的 rubric 对每套候选方案产出一张评分票。评分只依据
 * 第一轮公开事实与方案侧重标签，不调用外部 API、不编造 POI。停车评委没有专属
 * 数据源，这一点写进 notices，不伪装成已接入停车数据。
 */
@Configuration
public class JudgeProviderConfiguration {

  public static final String JUDGE_TICKET = "judge.ticket";
  public static final String JUDGE_FOOD = "judge.food";
  public static final String JUDGE_PARKING = "judge.parking";
  public static final List<String> DEFAULT_JUDGES =
      List.of(JUDGE_TICKET, JUDGE_FOOD, JUDGE_PARKING);
  public static final String PHASE_COLLECT = "COLLECT";
  public static final String PHASE_SCORE = "SCORE";
  public static final String HINT_PHASE = "votePhase";
  public static final String PLANS_KEY = "planCandidates";
  public static final String PLAN_ID = "planId";
  public static final String PLAN_FOCUS = "focus";
  /** 本轮所有子 Agent 汇集的卡片，由主 Agent 转成事实后用于整理草案。 */
  public static final String COLLECTED_CARDS_KEY = "collectedCards";
  /** 主 Agent 整理好的草案，第二轮发给全部评委；评委只按草案里真实存在的内容评分。 */
  public static final String DRAFT_KEY = "draft";
  public static final String TICKET_GUIDANCE_CATEGORY = "TICKET_GUIDANCE";
  /** 票务子 Agent 的唯一职责：给入口与方法，绝不代购。 */
  public static final String TICKET_METHOD =
      "打开官方入口，按页面指引自行预约或购票；本系统不代下单、不查余票、不处理支付与退改。";

  private static final String ADVICE = "本评委按声明的评分项提交结构化评分票";
  private static final String PARKING_NOTICE =
      "停车视角暂无专属数据源，评分基于公开行程事实，未编造停车 POI";

  private static final Map<String, Map<String, int[]>> SCORE_TABLE = Map.of(
      JUDGE_TICKET, Map.of(
          "COST", new int[]{5, 4}, "FOOD", new int[]{3, 3}, "WALK", new int[]{2, 3}),
      JUDGE_FOOD, Map.of(
          "COST", new int[]{3, 2}, "FOOD", new int[]{5, 4}, "WALK", new int[]{3, 4}),
      JUDGE_PARKING, Map.of(
          "COST", new int[]{2, 3}, "FOOD", new int[]{3, 3}, "WALK", new int[]{5, 4}));

  private static final Map<String, PlanVoteTally.Rubric> RUBRICS = rubrics();

  /** 评委声明：投票编排层与 REST 层共用同一份，避免评分项和权重出现第二份定义。 */
  public static Map<String, PlanVoteTally.Rubric> rubrics() {
    Map<String, PlanVoteTally.Rubric> out = new LinkedHashMap<>();
    out.put(JUDGE_TICKET, new PlanVoteTally.Rubric(JUDGE_TICKET,
        List.of("ticketCost", "reservationRisk"), new BigDecimal("1.2"), true));
    out.put(JUDGE_FOOD, new PlanVoteTally.Rubric(JUDGE_FOOD,
        List.of("mealCoverage", "dietaryFit"), new BigDecimal("1.0"), false));
    out.put(JUDGE_PARKING, new PlanVoteTally.Rubric(JUDGE_PARKING,
        List.of("parkingEase", "walkLoad"), new BigDecimal("0.8"), false));
    return Map.copyOf(out);
  }

  @Bean
  public AgentProvider judgeTicket(ContractRules rules) {
    return judge(rules, JUDGE_TICKET);
  }

  @Bean
  public AgentProvider judgeFood(ContractRules rules) {
    return judge(rules, JUDGE_FOOD);
  }

  @Bean
  public AgentProvider judgeParking(ContractRules rules) {
    return judge(rules, JUDGE_PARKING);
  }

  private static AgentProvider judge(ContractRules rules, String providerId) {
    // 复用 DECISION_ADVICE，不新增 capability。id 以 judge. 开头，字典序晚于 builtin.*，
    // 因此不会挤掉 builtin.decision 的默认 primary，单轮路由行为不变。
    AgentDescriptor descriptor = new AgentDescriptor(providerId, "1.0.0",
        rules.contractVersion(), Set.of("DECISION_ADVICE"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null);
    return new BuiltinServiceProvider(descriptor, context -> {
      String phase = context.hints() == null
          ? "" : context.hints().getOrDefault(HINT_PHASE, "");
      return switch (phase) {
        case PHASE_COLLECT -> collect(providerId, context);
        case PHASE_SCORE -> score(providerId, context);
        default -> AgentResult.refused(List.of(), List.of("投票阶段"),
            "未指定投票阶段，评委不响应");
      };
    });
  }

  /**
   * 第一轮：上报本评委真正掌握的视角事实，factId 供第二轮票做 grounding。
   *
   * <p>不再让三位评委回同一个常量模板：票务评委声明「只给入口与方法、余票未核验」，
   * 美食评委声明餐饮覆盖视角，停车评委如实说明没有专属数据源。汇集来的子 Agent 事实
   * 由主 Agent 统一整理，评委不重复上报、也不假装自己查过 POI。
   */
  private static AgentResult collect(String providerId, AgentContext context) {
    Map<String, Object> input = context.context() == null ? Map.of() : context.context();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "VOTE_CONTEXT");
    value.put("provider", providerId);
    value.put("budgetCny", input.get("budgetCny") == null ? "未知" : input.get("budgetCny"));
    value.put("city", input.get("city") == null ? "未知" : input.get("city"));
    value.put("collected", hasCollected(input)
        ? "主 Agent 已汇集本轮子 Agent 事实" : "本轮未收到子 Agent 汇集事实");
    switch (providerId) {
      case JUDGE_TICKET -> {
        value.put("perspective", "官方购票入口与预约规则");
        value.put("ticketMethod", TICKET_METHOD);
        value.put("stockVerified", "未核验");
      }
      case JUDGE_FOOD -> value.put("perspective", "餐饮覆盖与忌口提示");
      case JUDGE_PARKING -> {
        value.put("perspective", "步行负荷与换乘");
        value.put("dataSource", "暂无专属数据源，不编造停车 POI");
      }
      default -> value.put("perspective", "行程评审");
    }
    List<Fact> facts = new ArrayList<>();
    facts.add(fact(providerId + ":context", "CONTENT", value,
        "第一轮征集的公开行程输入与本评委视角"));

    List<String> notices = new ArrayList<>();
    if (JUDGE_PARKING.equals(providerId)) {
      notices.add(PARKING_NOTICE);
    }
    return new AgentResult("OK", facts, ADVICE, List.of(), notices, new Usage(0L, 0));
  }

  private static boolean hasCollected(Map<String, Object> input) {
    return input.get(COLLECTED_CARDS_KEY) instanceof List<?> list && !list.isEmpty();
  }

  /** 第二轮：一次调用返回对所有候选方案的评分票，不按方案拆成多次调用。 */
  private static AgentResult score(String providerId, AgentContext context) {
    Map<String, Object> input = context.context() == null ? Map.of() : context.context();
    List<?> plans = input.get(PLANS_KEY) instanceof List<?> list ? list : List.of();
    Map<String, Object> draft = draftOf(input);
    PlanVoteTally.Rubric rubric = RUBRICS.get(providerId);
    List<String> ownFactIds = context.facts() == null ? List.of()
        : context.facts().stream().map(Fact::factId).toList();
    if (ownFactIds.isEmpty()) {
      ownFactIds = List.of(providerId + ":context");
    }

    List<Fact> ballots = new ArrayList<>();
    Set<String> seenPlans = new LinkedHashSet<>();
    for (Object raw : plans) {
      if (!(raw instanceof Map<?, ?> plan)) {
        continue;
      }
      String planId = String.valueOf(plan.get(PLAN_ID));
      if (planId.isBlank() || !seenPlans.add(planId)) {
        continue;
      }
      Object rawFocus = plan.get(PLAN_FOCUS);
      String focus = rawFocus == null ? "BALANCED" : String.valueOf(rawFocus);
      int[] values = draftAdjusted(providerId, focus, draft);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("category", PlanVoteTally.SCORE_CATEGORY);
      value.put(PLAN_ID, planId);
      value.put("rubric", providerId);
      value.put("scores", Map.of(rubric.criteria().get(0), values[0],
          rubric.criteria().get(1), values[1]));
      value.put("groundingFactIds", ownFactIds);
      value.put("decision", "SCORE");
      ballots.add(new Fact("score:" + providerId + ":" + planId, "CONTENT", value,
          new Evidence("EXTERNAL", providerId, Evidence.UNVERIFIED, Instant.now(),
              "评分依据为本评委第一轮上报事实与方案侧重")));
    }

    List<String> notices = new ArrayList<>();
    if (JUDGE_PARKING.equals(providerId)) {
      notices.add(PARKING_NOTICE);
    }
    return new AgentResult("OK", ballots, ADVICE, List.of(), notices, new Usage(0L, 0));
  }

  /**
   * 评委读主 Agent 的草案内容投票。只有草案里确实存在本领域的真实数据时才调整分数；
   * 没有数据就退回按侧重标签的确定性基准分——「没查到」不等于「表现差」，也不能凭空加分。
   */
  private static int[] draftAdjusted(String providerId, String focus,
      Map<String, Object> draft) {
    int[] base = SCORE_TABLE.getOrDefault(providerId, Map.of())
        .getOrDefault(focus, new int[]{3, 3});
    if (draft.isEmpty()) {
      return base;
    }
    int first = base[0];
    int second = base[1];
    switch (providerId) {
      case JUDGE_TICKET -> {
        if (!channelsOf(draft).isEmpty()) {
          first = 5;   // 草案给出官方入口，花费可自行控制
          second = 3;  // 余票未核验，预约风险不写成低风险
        }
      }
      case JUDGE_FOOD -> {
        if (!listOf(draft.get("food")).isEmpty()) {
          first = 5;   // 有真实餐饮候选
          boolean dietaryUnknown = listOf(draft.get("unknowns")).stream()
              .map(String::valueOf)
              .anyMatch(s -> s.contains("忌口") || s.contains("餐饮"));
          second = dietaryUnknown ? 2 : 4;
        }
      }
      case JUDGE_PARKING -> {
        int perDay = maxItemsPerDay(draft);
        if (perDay > 0) {
          second = perDay <= 2 ? 5 : (perDay == 3 ? 3 : 2);
        }
      }
      default -> {
      }
    }
    return new int[]{clamp(first), clamp(second)};
  }

  private static Map<String, Object> draftOf(Map<String, Object> input) {
    if (!(input.get(DRAFT_KEY) instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> {
      if (k instanceof String key) {
        out.put(key, v);
      }
    });
    return out;
  }

  private static List<?> channelsOf(Map<String, Object> draft) {
    Object guidance = draft.get("ticketGuidance");
    return guidance instanceof Map<?, ?> map
        && map.get("channels") instanceof List<?> list ? list : List.of();
  }

  private static List<?> listOf(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static int maxItemsPerDay(Map<String, Object> draft) {
    int max = 0;
    for (Object raw : listOf(draft.get("dailySchedules"))) {
      if (raw instanceof Map<?, ?> day && day.get("items") instanceof List<?> items) {
        max = Math.max(max, items.size());
      }
    }
    return max;
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(5, value));
  }

  private static Fact fact(String factId, String type, Map<String, Object> value,
      String excerpt) {
    return new Fact(factId, type, value,
        new Evidence("USER_INPUT", "vote-request", Evidence.UNVERIFIED,
            Instant.now(), excerpt));
  }
}
