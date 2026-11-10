package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.orchestration.AgentEnvelope;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对话 Agent 能调用的工具。
 *
 * <p>设计原则：<b>工具是唯一的事实来源</b>。模型只负责「决定查什么、怎么讲」，
 * 数字、地名、票价、餐馆全部来自这里，回给模型的也是这些结果的紧凑摘要。
 * 这样即使模型想编，也编不出一个不在知识库里的景点名。
 *
 * <p>每个工具同时返回两样东西：
 * <ul>
 *   <li>{@code summary}：给模型看的纯文本（截断到 1.2k 字符，避免把上下文撑爆）；</li>
 *   <li>{@code card}：给前端渲染的结构化数据。行程与美食直接复用 L1 编排的信封形状，
 *       前端已有的草案/餐馆渲染器不用改一行。</li>
 * </ul>
 */
@Component
public class ChatTools {
  private static final Logger log = LoggerFactory.getLogger(ChatTools.class);

  /** 回给模型的摘要上限：一个工具的结果不该比提问本身长十倍。 */
  private static final int MAX_SUMMARY = 1200;
  private static final int MAX_PLACES = 8;
  private static final int MAX_FOOD = 8;

  /** 一次工具执行的结果。{@code card} 为 null 表示这次没有可渲染的卡片。 */
  public record Outcome(boolean ok, String summary, Card card) {
  }

  /** 前端要渲染的卡片：{@code type} 决定用哪个渲染器，{@code intent} 仅行程/美食信封才有。 */
  public record Card(String type, String intent, Object data) {
  }

  private final PlaceResolver places;
  private final AttractionStore store;
  private final AgentOrchestrator orchestrator;
  private final VoteOrchestrator votes;
  private final ObjectMapper mapper;

  public ChatTools(PlaceResolver places, AttractionStore store,
      AgentOrchestrator orchestrator, VoteOrchestrator votes, ObjectMapper mapper) {
    this.places = places;
    this.store = store;
    this.orchestrator = orchestrator;
    this.votes = votes;
    this.mapper = mapper;
  }

  /**
   * 给模型看的工具清单（OpenAI function calling 格式）。
   *
   * <p>描述写得像给人看的说明书：「什么时候该用」比「参数是什么」更能决定模型的选择。
   */
  public static final List<Map<String, Object>> SPECS = List.of(
      fn("resolve_place", "核实一个地名属于哪座城市、是不是具体景点。地名写法不确定、"
          + "或用户说的可能是景区名时先调它，不要自己猜归属。",
          Map.of("name", str("地名原文，例如「武当山」「婺源」")), List.of("name")),
      fn("list_attractions", "按城市列出知识库里的景点，含等级、票价原文、开放时间原文。"
          + "问「有什么好玩的」「值不值得去」「门票多少」时用它。",
          Map.of("city", str("城市名，例如「武汉」"),
              "limit", Map.of("type", "integer", "description", "最多返回几条，默认 8，上限 12")),
          List.of("city")),
      fn("get_weather", "查某座城市未来几天的天气预报（高德预报，不是实况）。"
          + "问天气、气温、要不要带伞、穿什么时用它。",
          Map.of("city", str("城市名，例如「天津」"),
              "days", Map.of("type", "integer", "description", "查几天，默认 3，最多 4")),
          List.of("city")),
      fn("book_ticket", "按一句自然语言查购票建议（规则匹配景区票种、指出还缺什么条件）。"
          + "用户问「门票怎么买」「带学生证多少钱」「预订要注意什么」时用它；"
          + "它只给建议与缺口，不能真正下单。",
          Map.of("message", str("用户的购票原话，尽量保留年龄、日期、人数等条件，例如"
              + "「两个成人一个1.2米儿童下周六去黄鹤楼」")), List.of("message")),
      fn("get_inspiration", "按城市给主题灵感候选（知识库里评分较高的景点，附推荐理由）。"
          + "用户说「不知道去哪」「随便逛逛」「有什么好玩的推荐一下」但还没定具体行程时用它。",
          Map.of("city", str("城市名，例如「杭州」")), List.of("city")),
      fn("vote_plan", "让三位评委 Agent（票务/美食/停车视角）对行程方案做两轮投票："
          + "第一轮各自上报领域事实，主 Agent 生成多套侧重不同的候选，第二轮评分计票选出胜者。"
          + "用户已经给了目的地和天数、想比较不同侧重（省钱/美食/少走路）怎么安排时用它。"
          + "投票比较耗时，用户只是闲聊或问天气时不要调。",
          Map.of("city", str("目的地城市，例如「武汉」"),
              "days", Map.of("type", "integer", "description", "游玩天数"),
              "travelers", Map.of("type", "integer", "description", "同行总人数，含本人"),
              "budget", Map.of("type", "number", "description", "预算上限，单位元"),
              "preferences", Map.of("type", "array", "items", Map.of("type", "string"),
                  "description", "偏好，只能取：自然/人文/美食/亲子/摄影/购物/夜游/温泉/海岛/慢节奏")),
          List.of("city")),
      fn("find_food", "查某座城市真实的餐馆候选（按该城市一个景点的坐标找周边，含人均、评分、距离）。"
          + "问吃什么、找馆子、本地小吃时用它。",
          Map.of("city", str("城市名，例如「武汉」")), List.of("city")),
      fn("draft_itinerary", "按城市与天数生成逐日行程草案，含候选景点、步行段、预算估算与待补问题。"
          + "用户要行程、问怎么安排、说「玩几天」时用它。天数没给就先问用户，不要默认填 3。",
          Map.of("city", str("城市名，例如「武汉」"),
              "days", Map.of("type", "integer", "description", "游玩天数，1~30"),
              "travelers", Map.of("type", "integer", "description", "同行总人数，含本人"),
              "budget", Map.of("type", "number", "description", "预算上限，单位元"),
              "preferences", Map.of("type", "array", "items", Map.of("type", "string"),
                  "description", "偏好，只能取：自然/人文/美食/亲子/摄影/购物/夜游/温泉/海岛/慢节奏"),
              "departDate", str("出发日期 YYYY-MM-DD，不知道就省略")),
          List.of("city", "days")));

  /** 执行一次工具调用；任何异常都收敛成一句可读的失败摘要，不把堆栈丢给模型。 */
  public Outcome execute(String name, String rawArgs) {
    JsonNode args = read(rawArgs);
    try {
      return switch (name == null ? "" : name) {
        case "resolve_place" -> resolvePlace(args);
        case "list_attractions" -> listAttractions(args);
        case "get_weather" -> weather(args);
        case "find_food" -> food(args);
        case "draft_itinerary" -> plan(args);
        case "book_ticket" -> ticket(args);
        case "get_inspiration" -> inspiration(args);
        case "vote_plan" -> votePlan(args);
        default -> new Outcome(false, "没有名为 " + name + " 的工具，请从给定工具里选择。", null);
      };
    } catch (RuntimeException e) {
      log.warn("工具 {} 执行失败：{}", name, e.getMessage());
      return new Outcome(false, "调用 " + name + " 失败：" + brief(e.getMessage()) + "。"
          + "可以换个参数再试，或直接告诉用户这次没查到。", null);
    }
  }

  private Outcome resolvePlace(JsonNode args) {
    String name = text(args, "name");
    if (name == null) {
      return new Outcome(false, "缺少 name 参数。", null);
    }
    PlaceResolution place = places.resolve(name, List.of(name));
    if (!place.resolved()) {
      String alts = place.alternatives().isEmpty() ? ""
          : "；相近城市：" + String.join("、", place.alternatives());
      return new Outcome(false, "没认出「" + name + "」属于哪座城市" + alts
          + "。知识库只覆盖部分城市，不要臆测归属，可以说「库里没有这个地名」。", null);
    }
    String summary = "「" + name + "」→ 城市 " + place.city()
        + (place.spot() == null || place.spot().isBlank() ? "" : "，景点 " + place.spot())
        + (place.note() == null || place.note().isBlank() ? "" : "（" + place.note() + "）");
    return new Outcome(true, summary, null);
  }

  private Outcome listAttractions(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    int limit = Math.min(Math.max(number(args, "limit", MAX_PLACES), 1), 12);
    List<Attraction> found = store.findByCity(city);
    if (found.isEmpty()) {
      return new Outcome(false, "知识库里没有「" + city + "」的景点条目。不要编造景点名，"
          + "可以如实说这座城市还没收录。", null);
    }
    List<Attraction> shown = found.stream().limit(limit).toList();
    List<Map<String, Object>> rows = new ArrayList<>();
    StringBuilder sb = new StringBuilder(city + " 的景点（知识库共 " + found.size() + " 条，列出 "
        + shown.size() + " 条）：\n");
    for (Attraction a : shown) {
      StringBuilder line = new StringBuilder("- ").append(a.name());
      if (a.level() != null && !a.level().isBlank()) {
        line.append("｜").append(a.level());
      }
      if (a.ticket() != null && !a.ticket().isBlank()) {
        line.append("｜门票：").append(a.ticket());
      }
      if (a.openTime() != null && !a.openTime().isBlank()) {
        line.append("｜开放：").append(a.openTime());
      }
      sb.append(line).append('\n');
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", a.name());
      row.put("level", a.level());
      row.put("ticket", a.ticket());
      row.put("openTime", a.openTime());
      row.put("city", a.city());
      rows.add(row);
    }
    sb.append("票价与开放时间是知识库原文快照，可能过期，回答时要说明这一点。");
    return new Outcome(true, cut(sb.toString()), new Card("places", null, rows));
  }

  /**
   * 天气走编排层的 {@code builtin.weather} 子 Agent：与其余能力同一入口、同一契约校验。
   * fact value 与 {@code WeatherResult} 的 JSON 字段一致，前端天气渲染器零改动。
   */
  private Outcome weather(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    Integer days = number(args, "days", 0);
    if (days > 0) {
      context.put("days", Math.min(days, 4));
    }
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("WEATHER_ADVICE", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "WEATHER");
    if (value == null) {
      String why = outcome.result() == null || outcome.result().notices().isEmpty()
          ? "编排层没有给出原因" : outcome.result().notices().get(0);
      return new Outcome(false, city + " 的天气没查到：" + why
          + "。不要编造天气，可以如实告诉用户这次没查到。", null);
    }
    StringBuilder sb = new StringBuilder(value.get("city") + " 天气预报（发布时间 "
        + value.get("reportTime") + "，是预报不是实况）：\n");
    Object rawDays = value.get("days");
    int shown = 0;
    if (rawDays instanceof List<?> list) {
      int limit = days > 0 ? Math.min(days, 4) : list.size();
      for (Object item : list) {
        if (shown >= limit) {
          break;
        }
        if (item instanceof Map<?, ?> d) {
          sb.append("- ").append(d.get("date"))
              .append("｜白天 ").append(d.get("dayWeather")).append(' ').append(d.get("dayTemp")).append("℃")
              .append("｜夜间 ").append(d.get("nightWeather")).append(' ').append(d.get("nightTemp")).append("℃")
              .append("｜").append(d.get("dayWind")).append(' ').append(d.get("dayPower")).append(" 级\n");
          shown++;
        }
      }
    }
    return new Outcome(true, cut(sb.toString()),
        new Card("weather", null, withoutCategory(value)));
  }

  private Outcome food(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    String anchorId = anchorOf(city);
    if (anchorId == null) {
      return new Outcome(false, "「" + city + "」的知识库里没有带坐标的景点，附近餐馆无法定位。"
          + "不要编餐厅，如实说明查不到即可。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    context.put("attractionIds", List.of(anchorId));
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("FOOD_RECOMMEND", context, Map.of()));
    return envelopeOutcome(outcome, "FOOD_RECOMMEND", namesOf(outcome, "FOOD", "name"));
  }

  private Outcome plan(JsonNode args) {
    String city = text(args, "city");
    Integer days = number(args, "days", 0) > 0 ? number(args, "days", 0) : null;
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    if (days == null) {
      return new Outcome(false, "缺少 days 参数。先问用户玩几天，不要默认成 3 天。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("destination", city);
    context.put("days", days);
    Integer travelers = number(args, "travelers", 0) > 0 ? number(args, "travelers", 0) : null;
    if (travelers != null) {
      context.put("travelers", travelers);
    }
    double budget = decimal(args, "budget", 0);
    if (budget > 0) {
      context.put("budget", budget);
    }
    String depart = text(args, "departDate");
    if (depart != null) {
      context.put("departDate", depart);
    }
    List<String> prefs = strings(args, "preferences");
    if (!prefs.isEmpty()) {
      context.put("preferences", prefs);
    }
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("PLAN_DRAFT", context, Map.of()));
    return envelopeOutcome(outcome, "PLAN_DRAFT", namesOf(outcome, "PLAN_DRAFT", "destination"));
  }

  /**
   * 票务子 Agent：一句话原话直接交给 {@code builtin.ticket}。缺什么条件由 Provider 的
   * questions 说清，工具摘要原样转述，不替用户猜年龄与学生资格。
   *
   * <p>注意 {@code builtin.ticket} 在 questions 非空时按契约返回 DEGRADED（advice 为空），
   * 但 fact 与 notices 里的购票规则仍然有效——工具层有结构化计数就视为可用，
   * 并把规则提示带给模型，否则「旺季、学生优惠」这些真实信息就被状态码埋没了。
   */
  private Outcome ticket(JsonNode args) {
    String message = text(args, "message");
    if (message == null) {
      return new Outcome(false, "缺少 message 参数。把用户的购票原话传过来。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("message", message);
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("TICKET_ADVICE", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "TICKET");
    if (value == null) {
      return new Outcome(false, "购票建议没拿到：" + firstNotice(outcome) + "。如实告诉用户，不要编票价。", null);
    }
    StringBuilder sb = new StringBuilder("购票建议（provider=" + outcome.providerId() + "）：");
    if (value.get("matchCount") instanceof Number n) {
      sb.append("匹配到 ").append(n.intValue()).append(" 条票务规则；");
    }
    if (value.get("stockVerified") instanceof Boolean b) {
      sb.append("库存").append(b ? "已核验" : "未核验").append("；");
    }
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    List<String> rules = notices.stream()
        .filter(s -> s != null && !s.contains("票价明细与渠道")).limit(4).toList();
    if (!rules.isEmpty()) {
      sb.append("规则提示：").append(String.join("；", rules)).append("。");
    }
    if (value.get("questionCount") instanceof Number q && q.intValue() > 0) {
      sb.append("还有 ").append(q.intValue()).append(" 个条件要问用户（按上面缺的问题问），"
          + "不要猜年龄或资格；票价明细与渠道要用既有购票接口。");
    } else {
      sb.append("条件齐了。票价明细与渠道要用既有购票接口，本工具只给建议。");
    }
    boolean ok = "OK".equals(outcome.status()) || "DEGRADED".equals(outcome.status());
    return new Outcome(ok, cut(sb.toString()), new Card("envelope", "TICKET_ADVICE",
        AgentEnvelope.view(outcome, orchestrator)));
  }

  /** 灵感子 Agent：{@code builtin.inspiration} 按知识库评分挑主题候选。 */
  private Outcome inspiration(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("CONTENT_INSPIRATION", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "INSPIRATION");
    if (value == null) {
      return new Outcome(false, city + " 的灵感候选没拿到：" + firstNotice(outcome)
          + "。知识库可能没收录这座城市，如实说明。", null);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    if (value.get("items") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", m.get("name"));
          row.put("level", m.get("level"));
          row.put("rating", m.get("rating"));
          row.put("ticket", m.get("ticket"));
          row.put("openTime", m.get("openTime"));
          row.put("city", m.get("city"));
          row.put("reason", m.get("reason"));
          rows.add(row);
        }
      }
    }
    Object total = value.get("total");
    String summary = city + " 的灵感候选（知识库共 " + total + " 条，挑了 " + rows.size()
        + " 条）：" + namesFromRows(rows) + "。票价与开放时间是知识库原文快照，可能过期。";
    return new Outcome(true, cut(summary), new Card("places", null, rows));
  }

  /**
   * 投票编排：主 Agent 把行程输入交给 {@link VoteOrchestrator}，三位评委 Agent 两轮投票。
   * 这是「多 Agent 协作」的直接入口——模型不需要知道评委是谁，只看到谁胜出、为什么。
   */
  private Outcome votePlan(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    Integer days = number(args, "days", 0) > 0 ? number(args, "days", 0) : null;
    if (days != null) {
      context.put("days", days);
    }
    Integer travelers = number(args, "travelers", 0) > 0 ? number(args, "travelers", 0) : null;
    if (travelers != null) {
      context.put("travelers", travelers);
    }
    double budget = decimal(args, "budget", 0);
    if (budget > 0) {
      context.put("budget", budget);
      // 评委第一轮的公开事实用的是 budgetCny 字段（字符串）
      context.put("budgetCny", java.math.BigDecimal.valueOf(budget)
          .stripTrailingZeros().toPlainString() + " 元");
    }
    List<String> prefs = strings(args, "preferences");
    if (!prefs.isEmpty()) {
      context.put("preferences", prefs);
    }
    VoteOrchestrator.VoteRun run = votes.vote(
        new VoteOrchestrator.VoteRequest("DECISION_ADVICE", context, Map.of(), List.of()));
    boolean ok = "OK".equals(run.status());
    StringBuilder sb = new StringBuilder("两轮投票完成（评委：" + String.join("、", run.judges())
        + "，候选来自 " + run.candidateSource() + "）：");
    PlanVoteTally.VoteOutcome tally = run.outcome();
    if (tally != null && tally.winner() != null) {
      sb.append("胜出方案 ").append(tally.winner());
      if (tally.ranking() != null && !tally.ranking().isEmpty()) {
        PlanVoteTally.Ranking top = tally.ranking().get(0);
        sb.append("（加权 ").append(top.score()).append("，").append(top.voterCount())
            .append(" 位评委有效票）");
      }
    } else {
      sb.append("没有产生可推荐方案——").append(String.join("；", run.notices()));
    }
    // 投票没产生胜者时 winner 是 null：Map.of 不收 null 值，改用 LinkedHashMap
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("plans", run.plans() == null ? List.of() : run.plans());
    card.put("winner", tally == null ? null : tally.winner());
    card.put("ranking", tally == null ? List.of() : tally.ranking());
    card.put("notices", run.notices() == null ? List.of() : run.notices());
    return new Outcome(ok, cut(sb.toString()), new Card("vote", "DECISION_ADVICE", card));
  }

  private static String namesFromRows(List<Map<String, Object>> rows) {
    List<String> names = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object name = row.get("name");
      if (name != null && !String.valueOf(name).isBlank()) {
        names.add(String.valueOf(name));
      }
    }
    return names.isEmpty() ? "无" : String.join("、", names.stream().limit(MAX_FOOD).toList());
  }

  /** 取第一个声明了目标类别的 CONTENT fact value；没有返回 null。 */
  private Map<String, Object> firstFactOf(DispatchOutcome outcome, String category) {
    List<Object> facts = factsOf(outcome, category);
    return facts.isEmpty() ? null
        : (facts.get(0) instanceof Map<?, ?> map ? asMutable(map) : null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMutable(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  /** 天气卡片不需要内部类别标记，去掉再发给前端。 */
  private static Map<String, Object> withoutCategory(Map<String, Object> value) {
    Map<String, Object> out = new LinkedHashMap<>(value);
    out.remove("category");
    return out;
  }

  /**
   * 行程与美食都复用 L1 编排：同一个 Provider、同一套契约校验、同一种信封。
   * 这样对话 Agent 拿到的结论与直接调 {@code /api/agent/invoke} 完全一致，
   * 不会出现「对话里说 A、接口说 B」。
   */
  private Outcome envelopeOutcome(DispatchOutcome outcome, String intent, String highlight) {
    Map<String, Object> envelope = AgentEnvelope.view(outcome, orchestrator);
    boolean ok = "OK".equals(outcome.status()) || "DEGRADED".equals(outcome.status());
    String summary = ok
        ? intent + " 结果（provider=" + outcome.providerId() + "）：" + highlight
            + summaryTail(outcome)
        : intent + " 没有拿到结果：" + firstNotice(outcome);
    return new Outcome(ok, cut(summary), new Card("envelope", intent, envelope));
  }

  /** 从 fact 里捧出人可读的一小段：景点名、餐馆名或草案的每日安排。 */
  private String namesOf(DispatchOutcome outcome, String category, String field) {
    List<String> names = new ArrayList<>();
    for (Object value : factsOf(outcome, category)) {
      if (value instanceof Map<?, ?> map) {
        Object v = map.get(field);
        if (v != null && !String.valueOf(v).isBlank()) {
          names.add(String.valueOf(v));
        }
      }
    }
    return names.isEmpty() ? "无" : String.join("、", names.stream().limit(MAX_FOOD).toList());
  }

  private String summaryTail(DispatchOutcome outcome) {
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    return notices.isEmpty() ? "" : "；提示：" + String.join("；", notices.stream().limit(2).toList());
  }

  private String firstNotice(DispatchOutcome outcome) {
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    return notices.isEmpty() ? "编排层没有给出原因" : notices.get(0);
  }

  private List<Object> factsOf(DispatchOutcome outcome, String category) {
    List<Object> out = new ArrayList<>();
    if (outcome == null || outcome.result() == null || outcome.result().facts() == null) {
      return out;
    }
    outcome.result().facts().forEach(fact -> {
      Object value = fact.value();
      if (value instanceof Map<?, ?> map && category.equals(String.valueOf(map.get("category")))) {
        out.add(value);
      }
    });
    return out;
  }

  /** 取该城市第一个带坐标系的景点当锚点；没有就不查，绝不按城市中心兜底。 */
  private String anchorOf(String city) {
    for (Attraction a : store.findByCity(city)) {
      if (a.lat() != null && a.lon() != null && a.coordinateSystem() != null
          && !a.coordinateSystem().isBlank()) {
        return a.id();
      }
    }
    return null;
  }

  private JsonNode read(String raw) {
    if (raw == null || raw.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      JsonNode node = mapper.readTree(raw);
      return node == null || node.isNull() ? mapper.createObjectNode() : node;
    } catch (Exception e) {
      // 模型偶尔给一段不合法 JSON：当作没给参数，由各工具自己报缺参
      return mapper.createObjectNode();
    }
  }

  private static String text(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    String s = node.asText("").strip();
    return s.isEmpty() ? null : s;
  }

  private static int number(JsonNode args, String field, int fallback) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (node.isNumber()) {
      return node.asInt();
    }
    try {
      return Integer.parseInt(node.asText("").strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double decimal(JsonNode args, String field, double fallback) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (node.isNumber()) {
      return node.asDouble();
    }
    try {
      return Double.parseDouble(node.asText("").strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static List<String> strings(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(item -> {
        String s = item.asText("").strip();
        if (!s.isEmpty()) {
          out.add(s);
        }
      });
    } else {
      String s = node.asText("").strip();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }

  private static String cut(String text) {
    return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY) + "…" : text;
  }

  private String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 120 ? message.substring(0, 120) + "…" : message;
  }

  private static Map<String, Object> fn(String name, String description,
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("type", "object");
    parameters.put("properties", properties);
    parameters.put("required", required);
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", name);
    function.put("description", description);
    function.put("parameters", parameters);
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("type", "function");
    spec.put("function", function);
    return spec;
  }

  private static Map<String, Object> str(String description) {
    return Map.of("type", "string", "description", description);
  }
}
