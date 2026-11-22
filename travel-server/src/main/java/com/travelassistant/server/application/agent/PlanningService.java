package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 规划编排（V2 第 2 节）：由 Java 决定调用哪些工具，模型只负责组织语言。
 *
 * <p>为什么不把「调哪个工具」交给模型：本项目要求每条建议都能追溯到来源。
 * 如果由模型自由选择工具，就会出现「没查就答」；由 Java 按需求固定走
 * 景点 → 天气 → 票务 三条链路，每一条的输入输出都经过校验，模型只拿到这些事实。
 *
 * <p>降级策略是显式的：
 * <ul>
 *   <li>景点库不可用 → 结果里说明原因，候选为空，但请求本身仍是 200；</li>
 *   <li>高德未配置 → 不查天气，notices 里说明「未接入实时天气」；</li>
 *   <li>模型未配置或调用失败 → {@code advice} 为空，notices 里说明原因，
 *       用户仍然拿到带来源的候选清单。</li>
 * </ul>
 * 换句话说，最差的情况也不是「什么都给不了」。
 *
 * <p>本服务**不保存任何东西**。需求要求用户确认后才落到正式方案，
 * 保存必须由用户另行调用行程接口完成。
 */
@Service
public class PlanningService {
  private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

  /** 为几个候选查购票信息：太多了没有意义，用户也不会一个个点。 */
  private static final int TICKET_LOOKUPS = 3;

  private final PlanningTools tools;
  private final DeepSeekClient model;
  private final TravelGuideComposer guideComposer;

  public PlanningService(PlanningTools tools, DeepSeekClient model) {
    this(tools, model, new TravelGuideComposer(null));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PlanningService(PlanningTools tools, DeepSeekClient model, TravelGuideComposer guideComposer) {
    this.guideComposer = guideComposer;
    this.tools = tools;
    this.model = model;
  }

  /** 生成规划草案（不落库）。 */
  public PlanDraft plan(PlanningRequest request) {
    return plan(request, true);
  }

  /**
   * 只装配事实，不调用模型。
   *
   * <p>「确认保存」要按同一套规则重新装配一遍载荷，此时调模型润色既浪费又有失败风险，
   * 因此单独留一个无模型入口。两种入口生成的 questions / dailySchedules / checks 完全一致。
   */
  public PlanDraft planFacts(PlanningRequest request) {
    return plan(request, false);
  }

  private PlanDraft plan(PlanningRequest request, boolean withAdvice) {
    if (request == null || request.destination() == null || request.destination().isBlank()) {
      throw new InvalidPlanningRequestException("请填写目的地");
    }
    String city = request.destination().strip();

    List<String> notices = new ArrayList<>();
    Set<String> sources = new LinkedHashSet<>();

    // 1. 候选景点
    List<Attraction> candidates = new ArrayList<>();
    ToolOutcome<List<Attraction>> byCity = tools.searchByCity(city, PlanningTools.MAX_CANDIDATES,
        request.departDate() == null ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : request.departDate());
    if (byCity.ok()) {
      candidates.addAll(byCity.value());
      if (!candidates.isEmpty()) {
        candidates.stream()
            .map(Attraction::sourceText)
            .filter(source -> source != null && !source.isBlank())
            .forEach(sources::add);
        if (sources.isEmpty()) {
          sources.add("本地景点知识库");
        }
      }
    } else {
      notices.add(byCity.error());
    }

    // 城市查不到时改用地图实时检索该地名本地的真实景点。
    // 早期版本曾用偏好关键词全库模糊匹配兜底，结果「人文」会命中全国任何带这俩字的
    // 名字（桂林/兰州/南宁的文化宫），候选全跑到省外——已废弃，宁可实时检索也不凑数。
    { // Always recall map candidates; sparse commercial coverage must not hide map results.
      ToolOutcome<List<Attraction>> live = tools.searchLive(city, PlanningTools.MAX_CANDIDATES);
      if (live.ok() && !live.value().isEmpty()) {
        candidates.addAll(live.value());
        sources.add("地图实时检索");
        notices.add("候选来自地图实时检索，票价与开放时间未知，以景区官方信息为准");
      } else if (!live.ok()) {
        notices.add(live.error());
      }
    }

    if (request.details() != null) {
      int lookups = 0;
      List<Attraction> mandatory = new ArrayList<>();
      List<Attraction> regionalCandidates = List.copyOf(candidates);
      for (String name : request.details().mustVisit()) {
        if (++lookups > 4) { notices.add("必去点查询上限4个，剩余点待逐个核对"); break; }
        ToolOutcome<List<Attraction>> found = tools.searchByName(name, 10);
        if (found.ok()) found.value().stream().filter(a -> name.equals(a.name()))
            .filter(a -> regionalCandidates.stream().anyMatch(c -> java.util.Objects.equals(c.city(), a.city()))
                || city.equals(a.city()) || city.equals(a.province()))
            .findFirst().ifPresent(mandatory::add);
      }
      candidates.addAll(0, mandatory);
    }
    candidates = mergeEntities(candidates);
    candidates.removeIf(a -> DraftAssembler.excluded(request,a));
    candidates = new ArrayList<>(DraftAssembler.uniqueAttractions(candidates));
    candidates.sort(java.util.Comparator.<Attraction>comparingInt(a -> request.details() != null && request.details().mustVisit().contains(a.name()) ? 0 : 1)
        .thenComparing(java.util.Comparator.comparingInt((Attraction a) -> preferenceMatches(request, a)).reversed())
        .thenComparing(java.util.Comparator.comparingLong((Attraction a) -> request.preferences().stream().anyMatch(p -> p.contains("热门")) ? reviewCount(a) : 0).reversed())
        .thenComparing(com.travelassistant.server.application.place.RatingOrder.ATTRACTION));
    if (candidates.isEmpty()) {
      notices.add("没查到「" + city + "」的景点，可以换个地名或补充偏好关键词再试");
    }

    // 2. 天气（仅在高德可用时）
    WeatherResult weather = null;
    boolean wantsWeather = request.details() != null && Boolean.TRUE.equals(request.details().includeWeather());
    if (wantsWeather && tools.locationEnabled()) {
      ToolOutcome<WeatherResult> outcome = tools.weather(city);
      if (outcome.ok()) {
        weather = outcome.value();
        sources.add(outcome.value().source());
      } else {
        notices.add(outcome.error());
      }
    } else if (wantsWeather) {
      notices.add("未接入实时天气（未配置高德密钥），本草案不含天气信息");
    }

    // 3. 候选景点的购票入口
    List<TicketAdvice> ticketAdvice = new ArrayList<>();
    for (Attraction candidate : candidates.subList(0, Math.min(TICKET_LOOKUPS, candidates.size()))) {
      ToolOutcome<TicketAdvice> outcome = tools.ticket(candidate.name(), candidate.city());
      if (outcome.ok()) {
        ticketAdvice.add(outcome.value());
        if (!outcome.value().channels().isEmpty()) {
          sources.add("本系统官方购票入口配置");
        }
      }
    }

    // 4. 需求完整度提示
    if (request.days() == null || request.days() <= 0) {
      notices.add("未提供游玩天数，暂不生成按天安排");
    }
    if (request.budget() == null) {
      notices.add("未提供预算上限，无法核对是否超支");
    }
    if (request.travelers() == null) {
      notices.add("未提供同行人数，人均费用无法计算");
    }

    // 4b. 结构化补问、逐日草案、日期与预算检查（纯规则，与模型无关）
    DraftAssembler.Draft assembled = DraftAssembler.assemble(request, candidates);
    notices.addAll(assembled.notices());

    TravelGuide guide = guideComposer.compose(request, candidates, assembled.dailySchedules());
    // The body is rendered from the same guide as cards and costs; no independent model schedule.
    java.util.Set<String> unplaced = guide.days().stream().flatMap(day -> day.slots().stream())
        .filter(slot -> slot.status().equals("UNPLACED")).map(TravelGuide.Slot::id).collect(java.util.stream.Collectors.toSet());
    if (!unplaced.isEmpty()) {
      List<DraftDay> actual = assembled.dailySchedules().stream().map(day -> new DraftDay(day.dayIndex(), day.date(), day.note(),
          day.items().stream().filter(item -> !unplaced.contains("D" + day.dayIndex() + "-visit-" + item.attractionId())).toList())).toList();
      java.util.Set<String> included = actual.stream().flatMap(day -> day.items().stream()).map(DraftDay.Item::attractionId).collect(java.util.stream.Collectors.toSet());
      assembled = new DraftAssembler.Draft(assembled.questions(), actual, assembled.checks(),
          DraftAssembler.estimate(request, candidates.stream().filter(a -> included.contains(a.id())).toList(), new ArrayList<>()), assembled.notices());
    }
    String advice = guide.render();
    if (weather != null && request.departDate() != null) {
      java.util.Set<java.time.LocalDate> covered = weather.days().stream()
          .map(d -> java.time.LocalDate.parse(d.date())).collect(java.util.stream.Collectors.toSet());
      for (TravelGuide.Day day : guide.days()) if (day.date() != null && !covered.contains(day.date())) {
        notices.add(day.date() + " 不在本次天气预报覆盖范围内，不能据此判断雨天或出行适宜性");
      }
    }

    if (wantsWeather) {
      StringBuilder forecast = new StringBuilder("\n天气预报（非实况）：");
      if (weather == null) forecast.append("查询未成功，不能判断旅行天气");
      else {
        forecast.append(weather.city()).append("；来源 ").append(weather.source()).append("；发布 ").append(weather.reportTime());
        for (var day : weather.days()) {
          boolean travelDay = guide.days().stream().anyMatch(d -> d.date() != null && d.date().toString().equals(day.date()));
          if (travelDay || request.departDate() == null) forecast.append("\n").append(day.date()).append(" ").append(day.dayWeather()).append(" ").append(day.dayTemp()).append("℃ / 夜间 ").append(day.nightWeather()).append(" ").append(day.nightTemp()).append("℃").append(travelDay ? "" : "（仅近期预报，旅行日期未给）");
        }
      }
      advice += forecast.toString();
      for (String notice : notices) if (notice.contains("预报覆盖")) advice += "\n" + notice;
    }
    return new PlanDraft(request, candidates, weather, ticketAdvice, advice,
        notices, List.copyOf(sources), assembled.questions(), assembled.dailySchedules(),
        assembled.checks(), assembled.budgetEstimate(), Instant.now(), guide);
  }

  private static int preferenceMatches(PlanningRequest request, Attraction a) {
    // Only classify supplied types. Family facilities, accessibility and indoor safety cannot be inferred.
    String type = a.type() == null ? "" : a.type();
    int count = 0;
    for (String preference : request.preferences()) {
      if (type.contains(preference) || a.name().contains(preference)) count++;
      else if (preference.equals("人文") && type.matches(".*(博物|文物|历史|纪念).*")) count++;
      else if (preference.equals("自然") && type.matches(".*(自然|风景|公园).*")) count++;
    }
    return count;
  }

  public static List<Attraction> mergeEntities(List<Attraction> candidates) {
    List<Attraction> result = new ArrayList<>();
    for (Attraction incoming : candidates) {
      int index = -1;
      for (int i=0; i<result.size(); i++) if (sameEntity(result.get(i), incoming)) { index=i; break; }
      if (index < 0) { result.add(incoming); continue; }
      Attraction old = result.get(index);
      java.util.Map<String, List<Attraction.FieldEvidence>> evidence = new java.util.LinkedHashMap<>();
      for (String field : List.of("ticket", "openTime", "level", "rating", "commentCount", "address", "coordinates", "type", "province", "description", "tel", "website", "image")) {
        List<Attraction.FieldEvidence> values = new ArrayList<>(old.evidenceFor(field));
        for (var e : incoming.evidenceFor(field)) if (!values.contains(e)) values.add(e);
        if (!values.isEmpty()) evidence.put(field, List.copyOf(values));
      }
      boolean oldCoordinates = DraftAssembler.coordinate(old) != null;
      Attraction coord = oldCoordinates ? old : incoming;
      java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>(old.sources()); sources.addAll(incoming.sources());
      String ticket = choose(old.ticket(), incoming.ticket());
      String opening = choose(old.openTime(), incoming.openTime());
      String ticketStatus = conflict(old.ticket(),incoming.ticket()) ? "conflict" : clean(old.ticket()) != null ? old.ticketStatus() : incoming.ticketStatus();
      String openingStatus = conflict(old.openTime(),incoming.openTime()) ? "conflict" : clean(old.openTime()) != null ? old.openTimeStatus() : incoming.openTimeStatus();
      result.set(index, new Attraction(old.id(),old.name(),old.city(),choose(old.province(),incoming.province()),
          choose(old.type(),incoming.type()),choose(old.level(),incoming.level()),choose(old.rating(),incoming.rating()),
          opening,ticket,choose(old.address(),incoming.address()),choose(old.tel(),incoming.tel()),choose(old.website(),incoming.website()),
          choose(old.image(),incoming.image()),choose(old.description(),incoming.description()),coord.lat(),coord.lon(),old.source(),List.copyOf(sources),
          ticketStatus,openingStatus,coord.coordinateSystem(),old.retrievedAt(),old.informationUpdatedAt(),evidence));
    }
    return result;
  }
  private static String clean(String value) { return com.travelassistant.server.domain.attraction.PlaceholderValues.clean(value); }
  private static String choose(String first, String second) { return clean(first) != null ? first : second; }
  private static boolean conflict(String first, String second) { return clean(first) != null && clean(second) != null && !first.strip().equals(second.strip()); }
  private static boolean sameEntity(Attraction a, Attraction b) {
    if (a.id()!=null && a.id().equals(b.id()) && java.util.Objects.equals(a.source(),b.source())) return true;
    return DraftAssembler.sameDestination(a,b);
  }

  private static long reviewCount(Attraction a) {
    return a.evidenceFor("commentCount").stream().mapToLong(e -> {
      try { return Math.max(0,Long.parseLong(e.value())); } catch (RuntimeException ignored) { return 0; }
    }).max().orElse(0);
  }

  /** 模型是否可用，供状态接口展示。 */
  public boolean modelConfigured() {
    return model.configured();
  }

  private String systemPrompt() {
    return """
        你是旅行助手的行程规划角色。规则：
        1. 只能使用用户消息中提供的景点、天气和购票数据，不得补充任何未提供的景点、价格或开放时间。
        2. 信息缺失就说缺失。票价、开放时间未知时写「未知」，不要估算。
        3. 不输出任何网址。购票只需说明「以官方页面为准」。
        4. 不承诺余票、不承诺具体到达时刻，路线耗时只是预估。
        5. 用中文，分点回答，控制在 400 字以内。先给结论，再说依据。
        6. 结尾用一句话提醒用户：确认后再保存到正式方案。
        """;
  }

  private String userPrompt(PlanningRequest request, List<Attraction> candidates,
      WeatherResult weather) {
    StringBuilder sb = new StringBuilder();
    sb.append("需求：\n");
    sb.append("- 目的地：").append(request.destination().strip()).append('\n');
    sb.append("- 天数：").append(request.days() == null ? "未提供" : request.days() + " 天").append('\n');
    sb.append("- 出发日期：").append(request.departDate() == null
        ? "未提供" : request.departDate()).append('\n');
    sb.append("- 返回日期：").append(request.returnDate() == null
        ? "未提供" : request.returnDate()).append('\n');
    sb.append("- 人数：").append(request.travelers() == null ? "未提供" : request.travelers()).append('\n');
    sb.append("- 预算：").append(request.budget() == null
        ? "未提供" : request.budget().toPlainString() + " " + request.currencyOrDefault()).append('\n');
    sb.append("- 偏好：").append(request.preferences().isEmpty()
        ? "未提供" : String.join("、", request.preferences())).append('\n');

    sb.append("\n候选景点（只能从这里选）：\n");
    if (candidates.isEmpty()) {
      sb.append("- 无\n");
    } else {
      for (Attraction a : candidates) {
        sb.append("- ").append(a.name());
        if (a.city() != null) {
          sb.append("（").append(a.city()).append("）");
        }
        if (a.type() != null) {
          sb.append(" 类型：").append(a.type());
        }
        sb.append(" 票价：").append(a.ticket() == null ? "未知" : a.ticket());
        sb.append(" 开放时间：").append(a.openTime() == null ? "未知" : a.openTime());
        sb.append('\n');
      }
    }

    sb.append("\n天气预报：");
    if (weather == null) {
      sb.append("未获取\n");
    } else {
      sb.append(weather.city()).append("（").append(weather.reportTime()).append(" 发布）\n");
      weather.days().forEach(d -> sb.append("- ").append(d.date())
          .append(' ').append(d.dayWeather())
          .append(' ').append(d.dayTemp()).append("°C")
          .append(' ').append(d.dayWind()).append(d.dayPower()).append('\n'));
    }

    sb.append("\n请基于以上数据给出一份可执行的安排建议。");
    return sb.toString();
  }
}
