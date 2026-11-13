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
import com.travelassistant.server.application.agent.spi.Evidence;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 主 Agent 的两轮投票编排层。刻意不改动 {@code AgentOrchestrator}：那边是单轮路由与
 * 降级，本类是独立的协作链路，两者互不影响。
 *
 * <p>信息流按用户定案：**所有子 Agent 收集到的信息全量交给主 Agent → 主 Agent 整理成
 * 一份结构化草案 → 子 Agent 对草案投票**。第一轮把请求里汇集的各工具结果连同评委上报的
 * 领域事实合成 {@code allFacts}，主 Agent 拿到的是事实内容而不只是 ID；草案确定性组装，
 * 不编造景点、票价或链接。第二轮把草案发给每个评委，收齐评分票后交
 * {@link PlanVoteTally} 确定性计票。
 *
 * <p>为什么汇集事实不塞进评委的 {@code AgentContext.facts}：计票器只接受
 * {@code PLAN_SCORE} 类 facts，任何非评分票都会整票判违规；而且
 * {@code BuiltinServiceProvider} 会把输入 facts 原样回显。因此汇集事实与草案都放在
 * {@code context} 里供主 Agent / 评委读取，评委响应里只有自己生成的评分票。
 *
 * <p>候选方案「模型优先 + 规则兜底」：模型输出必须引用本轮真实存在的事实 ID，
 * 任何编造、格式错误、无密钥或调用失败都自动降级成确定性规则草案，绝不静默采信。
 */
@Service
public class VoteOrchestrator {

  private static final String INTENT = "DECISION_ADVICE";
  /** 候选方案只留三套侧重：五套并列对用户是噪音，评委也判不出更细的差异。 */
  private static final int MAX_PLANS = 3;
  /**
   * 汇集进事实摘要的卡片上限。摘要会原样拼进那次模型调用的 user 提示，每条事实按
   * {@link #FACT_DIGEST_CHARS} 截断，所以卡片数直接决定输入长度：12 张改成 8 张，
   * 提示最多省下约 1.2K 字符。第 9 张之后的卡片仍然进草案，只是不进摘要。
   */
  private static final int MAX_COLLECTED_CARDS = 8;
  /** 单条事实在摘要里的字符上限。模型只需要认出「这是哪条事实」，细节留在草案里。 */
  private static final int FACT_DIGEST_CHARS = 180;
  private static final int MAX_NAME_SCAN = 12;
  private static final Set<String> FOCUS = Set.of("COST", "FOOD", "WALK", "BALANCED");
  private static final String MODEL_SYSTEM =
      "你是旅行方案草案生成器。只返回 JSON 数组，最多 3 个元素，每个元素含 planId"
          + "（A、B、C 之一，不重复）、title、focus（COST / FOOD / WALK / BALANCED 之一）、"
          + "groundingFactIds（字符串数组）。"
          + "groundingFactIds 只能从给定的事实 ID 列表里选，不得编造。标题要反映下面汇集事实里"
          + "真实出现的地名与内容，不要输出解释、Markdown 或代码块，不要输出价格、网址或"
          + "事实之外的内容。";

  private final AgentRegistry registry;
  private final ContractValidator validator;
  private final ContractRules rules;
  private final DeepSeekClient model;
  private final ObjectMapper json;
  private final int maxModelCalls;
  private final int maxToolCalls;

  public VoteOrchestrator(AgentRegistry registry, ContractValidator validator,
      ContractRules rules, DeepSeekClient model, ObjectMapper json,
      @Value("${travel.agent.budget.vote-max-model-calls:2}") int maxModelCalls,
      @Value("${travel.agent.budget.vote-max-tool-calls:8}") int maxToolCalls) {
    this.registry = registry;
    this.validator = validator;
    this.rules = rules;
    this.model = model;
    this.json = json;
    this.maxModelCalls = maxModelCalls;
    this.maxToolCalls = maxToolCalls;
  }

  public record VoteRequest(String intent, Map<String, Object> context,
      Map<String, String> hints, List<String> judges) {
  }

  /** {@code draft} 是主 Agent 汇总全部子 Agent 事实后整理的草案；旧构造器保留给既有调用方。 */
  public record VoteRun(String status, String intent, String registryVersion,
      String candidateSource, List<String> judges, List<Map<String, Object>> plans,
      PlanVoteTally.VoteOutcome outcome, List<String> notices,
      Map<String, Object> draft, int collectedFactCount) {

    public VoteRun(String status, String intent, String registryVersion,
        String candidateSource, List<String> judges, List<Map<String, Object>> plans,
        PlanVoteTally.VoteOutcome outcome, List<String> notices) {
      this(status, intent, registryVersion, candidateSource, judges, plans, outcome,
          notices, Map.of(), 0);
    }
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
    RunBudget budget = new RunBudget(deadline, maxModelCalls, maxToolCalls);

    // 汇集阶段：本轮所有子 Agent / 工具的结果先转成事实，全部留给主 Agent。
    List<Fact> collected = collectedFacts(baseContext);
    if (!collected.isEmpty()) {
      notices.add("主 Agent 已汇集 " + collected.size() + " 条子 Agent 事实，用于整理草案");
    }

    // 第一轮：评委上报各自的领域视角事实，不接触候选方案与草案。
    Map<String, List<Fact>> firstRoundFacts = new LinkedHashMap<>();
    List<Fact> allFacts = new ArrayList<>(collected);
    Set<String> seenFactIds = new LinkedHashSet<>();
    Set<String> allFactIds = new LinkedHashSet<>();
    for (Fact fact : collected) {
      if (seenFactIds.add(fact.factId())) {
        allFactIds.add(fact.factId());
      }
    }
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
          if (seenFactIds.add(fact.factId())) {
            allFacts.add(fact);
            allFactIds.add(fact.factId());
          }
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

    // 主 Agent 整理：全部事实 → 一份结构化草案；模型只负责候选侧重，草案本体确定性组装。
    Map<String, Object> draft = buildDraft(publicContext(baseContext), allFacts, allFactIds);
    String factDigest = digest(allFacts);
    List<Map<String, Object>> plans = modelPlans(publicContext(baseContext), allFactIds,
        factDigest, notices);
    String candidateSource = "MODEL";
    if (plans.isEmpty()) {
      plans = rulePlans(allFactIds);
      candidateSource = "RULES";
    }

    // 第二轮：把主 Agent 的草案与汇集摘要发给每个评委，收评分票。
    List<PlanVoteTally.BallotBatch> batches = new ArrayList<>();
    Map<String, Object> scoreContext = new LinkedHashMap<>(publicContext(baseContext));
    scoreContext.put(JudgeProviderConfiguration.PLANS_KEY, plans);
    scoreContext.put(JudgeProviderConfiguration.DRAFT_KEY, draft);
    scoreContext.put("collectedFactDigest", factDigest);
    for (String judge : participating) {
      AgentProvider provider = registry.find(judge).orElse(null);
      if (provider == null) {
        continue;
      }
      Map<String, String> hints = new LinkedHashMap<>(baseHints);
      hints.put(JudgeProviderConfiguration.HINT_PHASE,
          JudgeProviderConfiguration.PHASE_SCORE);
      // facts 只放该评委第一轮上报的事实：计票器要求评分票 grounding 属于本评委。
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
        participating, plans, outcome, notices, draft, allFacts.size());
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

  /** 把本轮各工具 / 子 Agent 的卡片全量转成事实；只用于编排层内部，不进 Provider 响应。 */
  private static List<Fact> collectedFacts(Map<String, Object> context) {
    if (!(context.get(JudgeProviderConfiguration.COLLECTED_CARDS_KEY)
        instanceof List<?> cards)) {
      return List.of();
    }
    List<Fact> out = new ArrayList<>();
    int index = 0;
    for (Object raw : cards) {
      if (!(raw instanceof Map<?, ?> card) || index >= MAX_COLLECTED_CARDS) {
        continue;
      }
      index++;
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("category", "COLLECTED");
      value.put("cardType", String.valueOf(card.get("type")));
      value.put("intent", card.get("intent"));
      value.put("data", compact(card.get("data")));
      out.add(new Fact("collected.card." + index, "CONTENT", value,
          new Evidence("EXTERNAL", "chat-tools", Evidence.UNVERIFIED, Instant.now(),
              "本轮子 Agent 工具结果")));
    }
    return out;
  }

  /** 从公开上下文里摘掉原始卡片，避免把大段 JSON 直接塞进模型提示词。 */
  private static Map<String, Object> publicContext(Map<String, Object> context) {
    Map<String, Object> out = new LinkedHashMap<>(context);
    out.remove(JudgeProviderConfiguration.COLLECTED_CARDS_KEY);
    return out;
  }

  /** 主 Agent 给模型看的事实摘要：带内容，不只是 ID。 */
  private static String digest(List<Fact> facts) {
    StringBuilder sb = new StringBuilder();
    for (Fact fact : facts) {
      Map<String, Object> value = fact.value() == null ? Map.of() : fact.value();
      sb.append("- ").append(fact.factId()).append(" | ")
          .append(value.getOrDefault("category", "未知")).append(" | ")
          .append(brief(value.containsKey("data")
              ? describe(value.get("data")) : String.valueOf(value), FACT_DIGEST_CHARS))
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * 主 Agent 整理草案：只把各子 Agent 事实里真实出现的名字归位，缺哪类就写「未提供」，
   * 不补常识、不编景点、不生成链接。
   */
  private static Map<String, Object> buildDraft(Map<String, Object> context,
      List<Fact> facts, Set<String> factIds) {
    List<String> spots = new ArrayList<>();
    List<String> food = new ArrayList<>();
    List<String> weather = new ArrayList<>();
    List<Map<String, Object>> channels = new ArrayList<>();
    for (Fact fact : facts) {
      Map<String, Object> value = fact.value() == null ? Map.of() : fact.value();
      String category = String.valueOf(value.getOrDefault("category", ""));
      String cardType = String.valueOf(value.getOrDefault("cardType", ""));
      String intent = String.valueOf(value.getOrDefault("intent", ""));
      Object data = value.get("data");
      if ("VOTE_CONTEXT".equals(category) || "vote".equals(cardType)) {
        continue;  // 评委自述视角与上一轮投票卡片不是行程素材
      }
      if (JudgeProviderConfiguration.TICKET_GUIDANCE_CATEGORY.equals(category)
          || "ticket".equals(cardType) || "TICKET_ADVICE".equals(intent)
          || hasGuidance(data)) {
        collectChannels(data, channels);
      } else if ("FOOD".equals(category) || "food".equals(cardType)
          || "FOOD_RECOMMEND".equals(intent)) {
        addDistinct(food, namesOf(data));
      } else if ("WEATHER".equals(category) || "weather".equals(cardType)) {
        addDistinct(weather, namesOf(data));
      } else {
        addDistinct(spots, namesOf(data));
      }
    }

    int requestedDays = context.get("days") instanceof Number number
        ? Math.max(1, Math.min(5, number.intValue())) : 3;
    // 与 /api/agent/plan 同一收敛口径：只排有事实支撑的天，不把「暂无候选」重复刷屏。
    int perDay = 3;
    int supported = (spots.size() + perDay - 1) / perDay;
    int renderedDays = Math.min(requestedDays, supported);
    int trimmedDays = requestedDays - renderedDays;
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int day = 1; day <= renderedDays; day++) {
      List<String> items = new ArrayList<>();
      for (int i = (day - 1) * perDay; i < Math.min(day * perDay, spots.size()); i++) {
        items.add(spots.get(i));
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("day", day);
      row.put("items", List.copyOf(items));
      row.put("note", "来自子 Agent 汇集事实");
      daily.add(row);
    }

    List<String> unknowns = new ArrayList<>();
    if (spots.isEmpty()) {
      unknowns.add("景点候选未提供");
    }
    if (trimmedDays > 0) {
      unknowns.add("其余 " + trimmedDays + " 天没有可查景点，未硬凑空天；"
          + "可缩短天数或补充周边城市");
    }
    if (food.isEmpty()) {
      unknowns.add("餐饮候选与忌口信息未提供");
    }
    if (weather.isEmpty()) {
      unknowns.add("天气信息未提供");
    }
    if (channels.isEmpty()) {
      unknowns.add("未提供经核验的官方购票入口");
    }

    Map<String, Object> guidance = new LinkedHashMap<>();
    guidance.put("method", JudgeProviderConfiguration.TICKET_METHOD);
    guidance.put("channels", List.copyOf(channels));
    guidance.put("stockVerified", false);
    guidance.put("note", channels.isEmpty()
        ? "未找到经核验的官方购票入口，请自行进入景区官方网站或官方公众号，本系统不生成猜测链接"
        : "余票未核验，购票前以官方页面显示为准");

    Map<String, Object> dayScope = new LinkedHashMap<>();
    dayScope.put("requestedDays", requestedDays);
    dayScope.put("renderedDays", renderedDays);
    dayScope.put("trimmedDays", trimmedDays);

    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("destination", context.getOrDefault("city", "未提供"));
    draft.put("days", requestedDays);
    draft.put("dayScope", dayScope);
    draft.put("dailySchedules", List.copyOf(daily));
    draft.put("food", List.copyOf(food));
    draft.put("weather", List.copyOf(weather));
    draft.put("ticketGuidance", guidance);
    draft.put("unknowns", List.copyOf(unknowns));
    draft.put("groundingFactIds", List.copyOf(factIds));
    draft.put("source", "MAIN_AGENT");
    draft.put("note", "由主 Agent 汇总本轮全部子 Agent 事实整理，未核验信息以景区官方信息为准");
    return draft;
  }

  private static boolean hasGuidance(Object data) {
    return data instanceof Map<?, ?> map && map.get("ticketGuidance") instanceof Map<?, ?>;
  }

  private static void collectChannels(Object data, List<Map<String, Object>> out) {
    Object raw = data;
    if (data instanceof Map<?, ?> map) {
      if (map.containsKey("channels")) {
        raw = map.get("channels");
      } else if (map.get("ticketGuidance") instanceof Map<?, ?> guidance) {
        raw = guidance.get("channels");
      }
    }
    if (!(raw instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> channel)) {
        continue;
      }
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("name", firstText(channel, "name", "channelName"));
      one.put("url", firstText(channel, "url", "officialUrl"));
      one.put("note", firstText(channel, "note", "method"));
      if (one.get("url") != null && out.size() < 8) {
        out.add(one);
      }
    }
  }

  private static String firstText(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).isBlank()) {
        return String.valueOf(value);
      }
    }
    return null;
  }

  /** 从工具结果里递归取真实出现的名字，深度有限，不生成任何新名称。 */
  private static List<String> namesOf(Object data) {
    List<String> out = new ArrayList<>();
    collectNames(data, out, 0);
    return out;
  }

  private static void collectNames(Object data, List<String> out, int depth) {
    if (depth > 2 || out.size() >= MAX_NAME_SCAN) {
      return;
    }
    if (data instanceof String text && !text.isBlank()) {
      out.add(brief(text, 60));
    } else if (data instanceof List<?> list) {
      for (Object item : list) {
        collectNames(item, out, depth + 1);
      }
    } else if (data instanceof Map<?, ?> map) {
      Object name = map.get("name");
      if (name != null && !String.valueOf(name).isBlank()) {
        out.add(brief(String.valueOf(name), 60));
      } else {
        for (Object item : map.values()) {
          collectNames(item, out, depth + 1);
        }
      }
    }
  }

  private static void addDistinct(List<String> target, List<String> names) {
    for (String name : names) {
      if (!target.contains(name) && target.size() < MAX_NAME_SCAN) {
        target.add(name);
      }
    }
  }

  private static String describe(Object data) {
    if (data == null) {
      return "无";
    }
    if (data instanceof String text) {
      return text;
    }
    List<String> names = namesOf(data);
    if (!names.isEmpty()) {
      return String.join("、", names);
    }
    return brief(String.valueOf(data), 200);
  }

  private static Object compact(Object data) {
    if (data instanceof List<?> list && list.size() > MAX_COLLECTED_CARDS) {
      return List.copyOf(list.subList(0, MAX_COLLECTED_CARDS));
    }
    if (data instanceof String text && text.length() > 800) {
      return text.substring(0, 800);
    }
    return data;
  }

  private static String brief(String text, int max) {
    String safe = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    return safe.length() <= max ? safe : safe.substring(0, max) + "…";
  }

  /** 模型生成候选。任何失败都返回空列表，由调用方降级到规则草案。 */
  private List<Map<String, Object>> modelPlans(Map<String, Object> context,
      Set<String> factIds, String factDigest, List<String> notices) {
    if (!model.configured()) {
      notices.add("未配置模型密钥，候选方案改用确定性规则草案");
      return List.of();
    }
    String allowed = String.join(", ", factIds);
    String user = "可用事实 ID：" + allowed + "\n子 Agent 汇集事实：\n" + factDigest
        + "行程输入：" + context;
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
        if (!planId.matches("[A-C]") || !seen.add(planId) || !FOCUS.contains(focus)) {
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
