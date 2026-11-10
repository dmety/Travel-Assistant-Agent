package com.travelassistant.server.application.agent.spi;

import com.travelassistant.server.application.agent.DecisionReport;
import com.travelassistant.server.application.agent.DecisionRequest;
import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.DraftBudgetEstimate;
import com.travelassistant.server.application.agent.DraftCheck;
import com.travelassistant.server.application.agent.DraftDay;
import com.travelassistant.server.application.agent.DraftQuestion;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.application.agent.vote.JudgeProviderConfiguration;
import com.travelassistant.server.application.ticket.TicketAssistantService;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.domain.ticket.TicketChannel;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import com.travelassistant.server.domain.location.WeatherResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 三个内置能力的委托映射：把 {@link AgentContext} 转成既有服务的请求，再把结果转回
 * {@link AgentResult}。既有 REST 端点仍直接调这三个服务，行为不变；SPI 只是新增一条调用路径。
 *
 * <p>不可用的分支一律返回 DEGRADED 或 REFUSED，不编造建议。facts 只回传输入里已带证据的事实，
 * 不把服务结果伪装成新事实；唯一新增的 fact 是票务匹配的结构化计数，证据标 unverified。
 *
 * <p>身份边界：{@link DecisionService#review} 需要真实用户名，而 {@link AgentContext} 只带
 * {@code userIdHash}。拿 hash 冒充 user 会越权查到别人的行程，因此缺少显式 user 与
 * itineraryId 时直接 REFUSED；编排层 P2 接入真实 principal 后再开放这条路径。
 */
@Configuration
public class BuiltinProviderConfiguration {

  @Bean
  public AgentProvider builtinPlanning(ContractRules rules, PlanningService planning) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.planning", "PLAN_DRAFT"),
        context -> planning(planning, context));
  }

  @Bean
  public AgentProvider builtinDecision(ContractRules rules, DecisionService decisions) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.decision", "DECISION_ADVICE"),
        context -> decision(decisions, context));
  }

  @Bean
  public AgentProvider builtinTicket(ContractRules rules, TicketAssistantService tickets) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.ticket", "TICKET_ADVICE"),
        context -> ticket(tickets, context));
  }

  /**
   * 天气子 Agent：包装既有 {@link LocationService}，让主 Agent 通过编排层调用而不是直连。
   *
   * <p>价值在于统一：票务、美食、行程、天气全部走同一条 {@code orchestrator.dispatch}，
   * 契约校验、信封形状、降级姿态一致—— Provider 查不到就 DEGRADED，绝不编造天气。
   * fact value 的字段名与 {@link WeatherResult} 的 JSON 序列化完全一致，
   * 前端天气渲染器不用为此改一行。
   */
  @Bean
  public AgentProvider builtinWeather(ContractRules rules, LocationService location) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.weather", "WEATHER_ADVICE"),
        context -> weather(location, context));
  }

  /**
   * 灵感子 Agent：从知识库挑主题条目（「有什么好玩的」「去哪逛逛」的轻量入口）。
   *
   * <p>推荐理由由条目字段确定性拼出（等级、评分、票价原文），不调模型、不编造标签；
   * 城市没收录时不直接报空——改用 {@code MapTools} 工具层做地图实时检索
   * （高德主源 + 百度第二源），候选标注「未经核验」，让「黄梅」这类区县地名
   * 不再被迫归位到地级市。两个源都查不到才如实 DEGRADED。
   */
  @Bean
  public AgentProvider builtinInspiration(ContractRules rules, AttractionStore store,
      MapTools mapTools) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.inspiration", "CONTENT_INSPIRATION"),
        context -> inspiration(store, mapTools, context));
  }

  private static AgentDescriptor descriptor(ContractRules rules, String id, String capability) {
    return new AgentDescriptor(id, "1.0.0", rules.contractVersion(), Set.of(capability),
        "BEAN", "NONE", true, AgentDescriptor.ACTIVE, null);
  }

  // ---------------------------------------------------------------- 映射

  /**
   * 信封 {@code advice} 只放无数字、无断言的摘要。
   *
   * <p>口径与美食 Provider 一致：模型正文与候选明细走 {@code CONTENT} fact，由前端按 value 渲染；
   * 信封里的 advice 一旦带上票价数字或「免费」「全天开放」这类断言，就会触发 S-03 / S-05
   * 把整条响应判为 CONTRACT_VIOLATION —— 未知信息不能写成确定值，数字必须有 facts 出处。
   */
  private static final String SAFE_ADVICE =
      "已按景区知识库给出候选与逐日草案；票价与开放时间以景区公告为准，模型措辞与明细见结构化内容。";

  private static AgentResult planning(PlanningService service, AgentContext context) {
    Map<String, Object> input = values(context);
    // 八个字段全透传：日期丢了就会一直重复问「请填写出发日期」，
    // 前端在补问卡片里点的日期等于白点。
    PlanningRequest request = new PlanningRequest(
        text(input.get("destination")), integer(input.get("days")),
        strings(input.get("preferences")), decimal(input.get("budget")),
        text(input.get("currency")), integer(input.get("travelers")),
        date(input.get("departDate")), date(input.get("returnDate")));
    PlanDraft draft = service.plan(request);
    List<String> unknown = new ArrayList<>();
    for (DraftQuestion question : draft.questions()) {
      unknown.add(question.field());
    }
    // 既没有候选也没有模型建议时才降级，避免把「空草案」报成 OK
    boolean hasContent = !draft.candidates().isEmpty()
        || (draft.advice() != null && !draft.advice().isBlank());
    return result(context, hasContent ? SAFE_ADVICE : null, unknown, draft.notices(),
        List.of(draftSummary(draft)));
  }

  /**
   * 把草案的结构化部分装成一条 {@code CONTENT} fact（与 ticket 的 match-summary 同一套路）。
   *
   * <p>前端走 {@code /api/agent/invoke} 时据此渲染候选景点与逐日安排，不必再回调
   * {@code /api/agent/plan} 二次调用模型与地图配额。字段名与 plan 接口保持一致
   * （{@code open_time} 是 Attraction 的 JSON 键），避免前端出现两套解析。
   *
   * <p>事实只搬运已查到的字段，证据统一标 {@code unverified}：票价与开放时间是原文，
   * 不等于官方核验值。
   */
  private static Fact draftSummary(PlanDraft draft) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "PLAN_DRAFT");
    value.put("destination", draft.request().destination());
    value.put("days", draft.request().days());
    value.put("advice", draft.advice());
    value.put("notices", List.copyOf(draft.notices()));
    value.put("sources", List.copyOf(draft.sources()));

    List<Map<String, Object>> questions = new ArrayList<>();
    for (DraftQuestion q : draft.questions()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("code", q.code());
      item.put("field", q.field());
      item.put("question", q.question());
      item.put("hint", q.hint());
      questions.add(item);
    }
    value.put("questions", questions);

    List<Map<String, Object>> checks = new ArrayList<>();
    for (DraftCheck c : draft.checks()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("code", c.code());
      item.put("severity", c.severity());
      item.put("field", c.field());
      item.put("message", c.message());
      item.put("suggestion", c.suggestion());
      checks.add(item);
    }
    value.put("checks", checks);

    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Attraction a : draft.candidates()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", a.id());
      item.put("name", a.name());
      item.put("city", a.city());
      item.put("level", a.level());
      item.put("rating", a.rating());
      item.put("ticket", a.ticket());
      item.put("open_time", a.openTime());
      candidates.add(item);
    }
    value.put("candidates", candidates);

    List<Map<String, Object>> days = new ArrayList<>();
    for (DraftDay day : draft.dailySchedules()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("dayIndex", day.dayIndex());
      item.put("date", day.date() == null ? null : day.date().toString());
      item.put("note", day.note());
      List<Map<String, Object>> items = new ArrayList<>();
      for (DraftDay.Item it : day.items()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("attractionId", it.attractionId());
        one.put("name", it.name());
        one.put("city", it.city());
        one.put("source", it.source());
        one.put("ticket", it.ticket());
        one.put("openTime", it.openTime());
        one.put("unknowns", List.copyOf(it.unknowns()));
        items.add(one);
      }
      item.put("items", items);
      days.add(item);
    }
    value.put("dailySchedules", days);

    DraftBudgetEstimate budget = draft.budgetEstimate();
    if (budget != null) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("currency", budget.currency());
      item.put("budgetLimit", budget.budgetLimit());
      item.put("travelers", budget.travelers());
      item.put("knownTicketSubtotal", budget.knownTicketSubtotal());
      item.put("totalForKnownTickets", budget.totalForKnownTickets());
      item.put("unknownTicketCount", budget.unknownTicketCount());
      item.put("verdict", budget.verdict());
      item.put("message", budget.message());
      value.put("budgetEstimate", item);
    }
    value.put("generatedAt", draft.generatedAt() == null ? null : draft.generatedAt().toString());

    return new Fact("builtin.planning:draft-summary", "CONTENT", value,
        new Evidence("KB", "builtin.planning", Evidence.UNVERIFIED, Instant.now(),
            "规划草案结构化摘要：候选与逐日项来自景点知识库，票价与开放时间未逐条核验"));
  }

  private static AgentResult decision(DecisionService service, AgentContext context) {
    Map<String, Object> input = values(context);
    String itineraryId = text(input.get("itineraryId"));
    String user = text(input.get("user"));
    if (itineraryId == null || itineraryId.isBlank() || user == null || user.isBlank()) {
      return AgentResult.refused(facts(context), List.of("itineraryId", "user"),
          "决策建议需要真实用户身份与行程 id，AgentContext 的 userIdHash 不能冒充 user");
    }
    Boolean includeWeather = input.get("includeWeather") instanceof Boolean b ? b : null;
    DecisionRequest request = new DecisionRequest(integer(input.get("travelers")), includeWeather);
    DecisionReport report = service.review(itineraryId, user, request);
    List<String> unknown = new ArrayList<>();
    for (DecisionReport.Risk risk : report.risks()) {
      if ("HIGH".equals(risk.severity())) {
        unknown.add(risk.code());
      }
    }
    return result(context, report.advice(), unknown, report.notices(), List.of());
  }

  private static AgentResult ticket(TicketAssistantService service, AgentContext context) {
    Map<String, Object> input = values(context);
    TicketAssistantService.Request request = new TicketAssistantService.Request(
        text(input.get("message")), null, text(input.get("itineraryId")),
        text(input.get("activityId")));
    TicketAssistantService.Result outcome = service.advise(request, text(input.get("user")));
    List<String> questions = outcome.questions() == null ? List.of() : outcome.questions();
    List<String> notices = new ArrayList<>(
        outcome.notices() == null ? List.of() : outcome.notices());
    notices.add("票价明细与渠道请使用既有购票接口，本响应只带结构化计数");
    int matches = outcome.matches() == null ? 0 : outcome.matches().size();
    Map<String, Object> value = new LinkedHashMap<>();
    // 与美食/天气/灵感同一套路：主 Agent 的工具按 category 捧 fact
    value.put("category", "TICKET");
    value.put("matchCount", matches);
    value.put("questionCount", questions.size());
    value.put("stockVerified", outcome.stockVerified());
    List<Fact> extra = new ArrayList<>();
    extra.add(new Fact("builtin.ticket:match-summary", "CONTENT", value,
        new Evidence("TICKET_RULE", "builtin.ticket", Evidence.UNVERIFIED,
            Instant.now(), "购票规则匹配结果计数")));

    // 票务子 Agent 的唯一职责是给官方入口与方法：URL 只能进 fact value，绝不能进 advice
    // （契约 S-04 禁止 advice 出现链接）。没有经核验入口就如实不给，不生成猜测链接。
    TicketAdvice guidance = outcome.channels();
    if (guidance != null && !guidance.channels().isEmpty()) {
      List<Map<String, Object>> channels = new ArrayList<>();
      for (TicketChannel channel : guidance.channels()) {
        if (!channel.usable()) {
          continue;
        }
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("name", channel.channelName());
        one.put("url", channel.officialUrl());
        one.put("attraction", channel.attractionName());
        one.put("rules", channel.rules());
        one.put("note", channel.note());
        one.put("verifiedAt", channel.verifiedAt() == null
            ? null : channel.verifiedAt().toString());
        channels.add(one);
      }
      if (!channels.isEmpty()) {
        Map<String, Object> guidanceValue = new LinkedHashMap<>();
        guidanceValue.put("category",
            JudgeProviderConfiguration.TICKET_GUIDANCE_CATEGORY);
        guidanceValue.put("method", JudgeProviderConfiguration.TICKET_METHOD);
        guidanceValue.put("channels", List.copyOf(channels));
        guidanceValue.put("stockVerified", false);
        guidanceValue.put("notice", guidance.notice());
        extra.add(new Fact("builtin.ticket:guidance", "CONTENT", guidanceValue,
            new Evidence("TICKET_RULE", "builtin.ticket", Evidence.UNVERIFIED,
                Instant.now(), "人工核验的官方购票入口快照")));
      }
    }
    String advice = questions.isEmpty()
        ? "已按当前条件整理购票建议，请核对结构化计数与既有购票接口"
        : null;
    return result(context, advice, questions, notices, extra);
  }

  // ---------------------------------------------------------------- 天气

  /** 信封 advice 不带温度数字与「晴/雨」断言：数字必须有 facts 出处，天气永远是预报。 */
  private static final String WEATHER_ADVICE =
      "已附目标城市的逐日预报，请以结构化内容为准；天气是预报不是实况，出发前请再确认。";

  private static AgentResult weather(LocationService service, AgentContext context) {
    Map<String, Object> input = values(context);
    String city = text(input.get("city"));
    if (city == null || city.isBlank()) {
      return AgentResult.refused(facts(context), List.of("city"), "查询天气需要城市名");
    }
    WeatherResult result;
    try {
      result = service.weather(city);
    } catch (RuntimeException e) {
      return new AgentResult("DEGRADED", facts(context), null, List.of("weather"),
          List.of("天气查询失败：" + brief(e.getMessage()) + "，未编造天气"), new Usage(0L, 0));
    }
    if (result.days().isEmpty()) {
      return new AgentResult("DEGRADED", facts(context), null, List.of("weather"),
          List.of("高德没有返回 " + result.city() + " 的预报，未编造天气"), new Usage(0L, 0));
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "WEATHER");
    value.put("city", result.city());
    value.put("adcode", result.adcode());
    value.put("reportTime", result.reportTime());
    value.put("source", result.source());
    value.put("queriedAt", result.queriedAt() == null ? null : result.queriedAt().toString());
    List<Map<String, Object>> days = new ArrayList<>();
    for (WeatherResult.DayWeather day : result.days()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("date", day.date());
      item.put("dayWeather", day.dayWeather());
      item.put("nightWeather", day.nightWeather());
      item.put("dayTemp", day.dayTemp());
      item.put("nightTemp", day.nightTemp());
      item.put("dayWind", day.dayWind());
      item.put("dayPower", day.dayPower());
      item.put("nightWind", day.nightWind());
      item.put("nightPower", day.nightPower());
      days.add(item);
    }
    value.put("days", days);
    Fact fact = new Fact("builtin.weather:forecast", "CONTENT", value,
        new Evidence("AMAP", "builtin.weather", Evidence.UNVERIFIED,
            Instant.now(), "高德天气预报快照，非实况"));
    return result(context, WEATHER_ADVICE, List.of(),
        List.of("天气是预报不是实况，出行前请再确认"), List.of(fact));
  }

  // ---------------------------------------------------------------- 灵感

  private static final int INSPIRATION_LIMIT = 8;
  /** 信封 advice 不带景点名与票价：候选明细全部走 CONTENT fact 由前端渲染。 */
  private static final String INSPIRATION_ADVICE =
      "已按知识库挑选主题候选，票价与开放时间以景区公告为准，明细见结构化内容。";
  /** 库外实时检索的 advice 同样无数字无断言：地图快照没有等级票价，说了就是编造。 */
  private static final String LIVE_ADVICE =
      "已按地图实时检索整理候选，等级、票价与营业信息地图未提供，请以景区官方信息为准，明细见结构化内容。";

  private static AgentResult inspiration(AttractionStore store, MapTools mapTools,
      AgentContext context) {
    Map<String, Object> input = values(context);
    String city = text(input.get("city"));
    if (city == null || city.isBlank()) {
      return AgentResult.refused(facts(context), List.of("city"), "灵感推荐需要城市名");
    }
    List<Attraction> found = store.findByCity(city);
    if (!found.isEmpty()) {
      // 等级（5A/4A）是景区评定的硬信号，先按有等级排前，再按评分降序：
      // 知识库里混着学校、图书馆这类 rating=5.0 的非景区条目，纯按评分会排到最前
      List<Attraction> picked = found.stream()
          .sorted(java.util.Comparator
              .comparing((Attraction a) -> a.level() == null || a.level().isBlank() ? 1 : 0)
              .thenComparing(a -> a.rating() == null || a.rating().isBlank()
                  ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(a.rating()),
                  java.util.Comparator.reverseOrder()))
          .limit(INSPIRATION_LIMIT)
          .toList();
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("category", "INSPIRATION");
      value.put("city", city);
      value.put("total", found.size());
      value.put("source", "KB");
      List<Map<String, Object>> items = new ArrayList<>();
      for (Attraction a : picked) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", a.name());
        item.put("city", a.city());
        item.put("level", a.level());
        item.put("rating", a.rating());
        item.put("ticket", a.ticket());
        item.put("openTime", a.openTime());
        item.put("reason", reasonOf(a));
        items.add(item);
      }
      value.put("items", items);
      Fact fact = new Fact("builtin.inspiration:picks", "CONTENT", value,
          new Evidence("KB", "builtin.inspiration", Evidence.UNVERIFIED, Instant.now(),
              "知识库主题候选快照，票价与开放时间为原文，未逐条核验"));
      return result(context, INSPIRATION_ADVICE, List.of(), List.of(), List.of(fact));
    }
    // 知识库没收录：改用地图工具层实时检索（高德主源 + 百度第二源）。
    // 等级、票价、开放时间地图关键字检索拿不到，就没有——绝不补「免费」「全天开放」。
    MapTools.ScenicResult live = mapTools.findScenicSpots(city, INSPIRATION_LIMIT);
    List<String> notices = new ArrayList<>();
    notices.add("知识库未收录「" + city + "」，已改用地图实时检索，结果未经核验；"
        + "等级、票价与开放时间以景区官方信息为准");
    notices.addAll(live.notices());
    if (live.spots().isEmpty()) {
      notices.add("未编造推荐");
      return new AgentResult("DEGRADED", facts(context), null, List.of("inspiration"),
          List.copyOf(notices), new Usage(0L, 0));
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "INSPIRATION");
    value.put("city", city);
    value.put("total", live.spots().size());
    value.put("source", "LIVE_MAP");
    List<Map<String, Object>> items = new ArrayList<>();
    boolean amapContributed = false;
    for (MapTools.ScenicSpot spot : live.spots()) {
      amapContributed = amapContributed || MapTools.SOURCE_AMAP.equals(spot.source());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", spot.name());
      item.put("type", spot.type());
      item.put("address", spot.address());
      item.put("district", spot.district());
      item.put("tel", spot.tel());
      item.put("source", spot.source());
      item.put("reason", liveReason(spot));
      items.add(item);
    }
    value.put("items", items);
    Fact fact = new Fact("builtin.inspiration:discover", "CONTENT", value,
        new Evidence(amapContributed ? "AMAP" : "EXTERNAL", "builtin.inspiration",
            Evidence.UNVERIFIED, Instant.now(),
            "地图 POI 实时检索快照（高德/百度），未经核验"));
    return result(context, LIVE_ADVICE, List.of(), notices, List.of(fact));
  }

  /** 推荐理由只由知识库字段拼出，缺字段就少说，绝不补形容词。 */
  private static String reasonOf(Attraction a) {
    StringBuilder sb = new StringBuilder();
    if (a.level() != null && !a.level().isBlank()) {
      sb.append(a.level());
    }
    if (a.rating() != null && !a.rating().isBlank()) {
      if (!sb.isEmpty()) {
        sb.append("，");
      }
      sb.append("评分 ").append(a.rating());
    }
    if (a.ticket() != null && !a.ticket().isBlank()) {
      if (!sb.isEmpty()) {
        sb.append("，");
      }
      sb.append("门票 ").append(a.ticket());
    }
    return sb.isEmpty() ? "知识库收录景点" : sb.toString();
  }

  /** 实时候选的理由只由区县与类别拼出，缺了就少说，绝不补形容词。 */
  private static String liveReason(MapTools.ScenicSpot spot) {
    StringBuilder sb = new StringBuilder();
    if (spot.district() != null && !spot.district().isBlank()) {
      sb.append(spot.district());
    }
    if (spot.type() != null && !spot.type().isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(" · ");
      }
      sb.append(spot.type());
    }
    return sb.isEmpty() ? "地图收录 POI" : sb.toString();
  }

  private static String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 120 ? message.substring(0, 120) + "…" : message;
  }

  private static AgentResult result(AgentContext context, String advice,
      List<String> unknown, List<String> notices, List<Fact> extraFacts) {
    List<Fact> facts = new ArrayList<>(facts(context));
    facts.addAll(extraFacts);
    String status = advice == null || advice.isBlank() ? "DEGRADED" : "OK";
    return new AgentResult(status, facts, advice, unknown, notices, new Usage(0L, 0));
  }

  // ---------------------------------------------------------------- 取值

  private static Map<String, Object> values(AgentContext context) {
    return context.context() == null ? Map.of() : context.context();
  }

  private static List<Fact> facts(AgentContext context) {
    return context.facts() == null ? List.of() : context.facts();
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  /** 日期按 {@code YYYY-MM-DD} 解析；格式不对按「未提供」处理，交给补问，不报 500。 */
  private static LocalDate date(Object value) {
    String s = text(value);
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(s.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Integer integer(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return result;
  }
}
