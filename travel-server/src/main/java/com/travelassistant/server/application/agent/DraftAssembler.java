package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.attraction.PlaceholderValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 草案规则装配器：把「需求 + 知识库候选」变成结构化补问、逐日草案、日期/预算检查。
 *
 * <p>这一层是**纯函数**：不查库、不调模型、不写文件、不落库。所有结论都能从入参复算，
 * 测试可以逐条断言。刻意与 {@link PlanningService} 分开，是为了让「确认保存」时
 * 复用同一套规则重新装配，避免前端展示的草案与最终落库的内容是两套逻辑。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li>不猜：没有日期就不编造日期，没有天数就补问（除非能由日期区间确定性换算）；</li>
 *   <li>不冒充已知：票价/开放时间是占位值或 null 时写进 {@code unknowns}，展示为「未知」；</li>
 *   <li>不伪造核验：只有 {@code verified} / {@code confirmed} 且能解析出金额的票价才计入预算粗估，
 *       其余一律算「未知」，结论只能是「无法判定」而不是「没超支」。</li>
 * </ul>
 */
public final class DraftAssembler {

  /** 每天最多安排几个候选：再多用户也不会逐个点，且排程没有可靠依据。 */
  public static final int MAX_PER_DAY = 2;
  /** 只用作日程分组启发式；不据此推算交通方式、耗时或可执行性。 */
  private static final double MAX_SAME_DAY_DISTANCE_METERS = 50_000;
  /** 同名记录用于日程去重时，坐标近邻阈值。 */
  private static final double SAME_DESTINATION_DISTANCE_METERS = 1_000;
  /** 字段合并的坐标证据阈值，比日程去重更严格。 */
  private static final double MERGE_DISTANCE_METERS = 100;
  /** 天数上限，防止 days=99999 生成一份几万天的响应。 */
  public static final int MAX_DAYS = 30;
  /** 候选撑不满请求天数时给出的收敛检查码；前端据此解释「为什么只有几天」。 */
  public static final String TRIMMED_CHECK = "DRAFT_DAYS_TRIMMED";

  private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)");
  private static final String RETRY = "POST /api/agent/plan";

  private DraftAssembler() {
  }

  /**
   * 一次装配的结果。
   *
   * @param questions      需要用户补全的字段
   * @param dailySchedules 按天草案；无法生成天数时为空列表
   * @param checks         日期与预算检查结论
   * @param budgetEstimate 预算粗估（含统计范围与局限）
   * @param notices        需要并入草案 notices 的补充说明
   */
  public record Draft(
      List<DraftQuestion> questions,
      List<DraftDay> dailySchedules,
      List<DraftCheck> checks,
      DraftBudgetEstimate budgetEstimate,
      List<String> notices) {
  }

  public static Draft assemble(PlanningRequest request, List<Attraction> candidates) {
    List<Attraction> safe = candidates == null ? List.of() : candidates;
    List<Attraction> eligible = safe.stream().filter(java.util.Objects::nonNull)
        .filter(attraction -> !excluded(request, attraction)).toList();
    List<Attraction> unique = uniqueForSchedule(eligible);
    List<DraftQuestion> questions = new ArrayList<>();
    List<DraftCheck> checks = new ArrayList<>();
    List<String> notices = new ArrayList<>();

    collectQuestions(request, questions, checks, notices);

    int plannedDays = request.effectiveDays() == null ? 0
        : Math.min(request.effectiveDays(), MAX_DAYS);
    int usable = Math.min(unique.size(), plannedDays * dailyLimit(request));
    List<Attraction> selected = unique.subList(0, Math.max(usable, 0));
    boolean crowded = needsPacking(selected, plannedDays);
    List<DraftDay> daily = arrange(request, selected);
    if (crowded) {
      notices.add("有些候选点距离较远，因计划天数达到每日容量上限而排在同一天；交通衔接待确认");
    }
    collectDateChecks(request, plannedDays, checks);
    if (daily.isEmpty()) {
      checks.add(new DraftCheck("NO_DAILY_SCHEDULE", "INFO", "date",
          "天数或候选景点不足，本次未生成逐日草案", "补齐游玩天数与目的地后重新生成草案"));
    }
    if (selected.isEmpty() && !unique.isEmpty() && plannedDays == 0) {
      notices.add("候选景点未进入逐日草案：请先填写游玩天数");
    }
    if (selected.size() < unique.size() && plannedDays > 0) {
      notices.add("有 " + (unique.size() - selected.size())
          + " 个候选景点未纳入本轮预算粗估：受计划天数与每日候选上限限制");
    }
    int scheduledItems = daily.stream().mapToInt(day -> day.items().size()).sum();
    if (scheduledItems < selected.size()) {
      notices.add("有 " + (selected.size() - scheduledItems)
          + " 个候选景点未进入逐日草案：坐标缺失或距离较远时不强行安排在同一天；预算粗估仍按候选保守计入");
    }
    if (daily.size() < plannedDays) {
      int trimmed = plannedDays - daily.size();
      // 编排层收敛：宁可少给几天，也不把「暂无候选」重复刷成十行。差额只说一次。
      checks.add(new DraftCheck(TRIMMED_CHECK, "INFO", "date",
          "本轮可查景点 " + safe.size() + " 个，只排出 " + daily.size() + " 天逐日草案；"
              + "其余 " + trimmed + " 天未列空天，不硬凑行程",
          "把天数缩短到 " + daily.size() + " 天，或补充周边城市后再排"));
      notices.add("可查景点只够排 " + daily.size() + " 天，其余 " + trimmed
          + " 天没有可查景点，逐日草案没有硬凑空天；可缩短天数或加周边城市");
    }

    DraftBudgetEstimate estimate = estimate(request, selected, checks);
    return new Draft(List.copyOf(questions), List.copyOf(daily), List.copyOf(checks),
        estimate, List.copyOf(notices));
  }

  /**
   * 按候选顺序把景点分配到每一天，最多 {@link #MAX_PER_DAY} 个/天，**只输出排得到
   * 候选的天**。
   *
   * <p>等分前先经 {@link DraftRouteOptimizer} 按点位间直线距离就近重排，避免
   * 「跑老远再跑回来」；重排只改顺序，不引入新的推荐判断、不生成游览时刻或耗时，
   * 坐标缺失时自动降级为 {@code PlanningTools} 原有的「等级 → 评分 → 名称」顺序。
   * 日期由出发日期顺序推算，超出返回日期的那些天
   * 只留 dayIndex、不给 date，避免把行程写到旅行区间之外。
   *
   * <p>编排层收敛口径：用户要 10 天、本地只查到 4 个景点时，只给 2 天有内容的草案，
   * 剩下 8 天由 {@link #assemble} 汇总成一条 {@link #TRIMMED_CHECK} 说明，不再生成
   * 八行一模一样的「暂无候选」。日期缺失、区间倒置这类全局提示也只在补问 / checks 里
   * 出现一次，不逐天重复。确认保存走同一个方法，因此正式行程里也不会写进空天。
   */
  public static List<DraftDay> arrange(PlanningRequest request, List<Attraction> selected) {
    List<Attraction> safe = uniqueForSchedule(selected == null ? List.of() : selected).stream()
        .filter(a -> !incompatibleClimbing(request,a)).toList();
    Integer effective = request.effectiveDays();
    if (effective == null || effective <= 0 || safe.isEmpty()) {
      return List.of();
    }
    // 展示与确认保存都走这里，重排一次即可保证「看到什么就存什么」。
    List<Attraction> ordered = DraftRouteOptimizer.optimize(safe);
    // Keep mandatory candidates ahead of optional ones after route optimization.
    if (request.details() != null) {
      ordered = new ArrayList<>(ordered);
      ordered.sort(java.util.Comparator.<Attraction>comparingInt(a -> assignedDay(request,a)!=null?0:1).thenComparingInt(a -> request.details().mustVisit().stream()
          .anyMatch(name -> PlanningService.matchesMustVisit(name, a)) ? 0 : 1));
    }
    int allowedDays = Math.min(effective, MAX_DAYS);
    List<List<Attraction>> groups;
    boolean constrained = limitedMobility(request) || request.details()!=null && request.details().origin()!=null || request.details() != null && (!request.details().mustVisit().isEmpty()
        || request.details().maxAttractionsPerDay() != null || !request.details().visitDays().isEmpty())
        || ordered.stream().anyMatch(a -> java.util.stream.IntStream.range(1, allowedDays + 1)
            .anyMatch(day -> OpeningHours.evaluate(a.openTime(), dateForDay(request, day)).status() == OpeningHours.Status.CLOSED));
    if (!constrained) {
      groups = groupByDay(ordered);
      if (groups.size() > allowedDays) groups = ordered.size() >= allowedDays * MAX_PER_DAY
          ? packByCapacity(ordered, allowedDays) : List.copyOf(groups.subList(0, allowedDays));
    } else {
      groups = new ArrayList<>();
      for (int day = 0; day < allowedDays; day++) groups.add(new ArrayList<>());
      for (Attraction attraction : ordered) {
        boolean placed = false;
        Integer assigned=assignedDay(request,attraction);
        for (int day = 0; day < allowedDays; day++) {
          if(assigned!=null && day+1!=assigned) continue;
          if (groups.get(day).size() >= scheduleLimit(request, day + 1)) continue;
          if (OpeningHours.evaluate(attraction.openTime(), dateForDay(request, day + 1)).status() == OpeningHours.Status.CLOSED) continue;
          groups.get(day).add(attraction);
          placed = true;
          break;
        }
        if (!placed && assigned==null && java.util.stream.IntStream.range(1, allowedDays + 1).allMatch(day ->
            OpeningHours.evaluate(attraction.openTime(), dateForDay(request, day)).status() == OpeningHours.Status.CLOSED)) {
          for (var group : groups) if (group.size() < scheduleLimit(request, groups.indexOf(group) + 1)) { group.add(attraction); break; }
        }
      }
    }
    LocalDate depart = request.departDate();
    String baseNote = "游览时刻待确认，景点信息来自本地知识库快照";

    List<DraftDay> days = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      List<DraftDay.Item> items = new ArrayList<>();
      for (Attraction attraction : groups.get(i)) {
        items.add(itemOf(attraction));
      }
      if (items.isEmpty()) {
        continue;  // 不生成「暂无候选」空行
      }

      LocalDate date = dateForDay(request, i + 1);
      String note = "第 " + (i + 1) + " 天；首站按景点候选排序；"
          + selectionReason(groups.get(i).getFirst()) + "；" + baseNote;
      if (depart != null && date == null) {
        note = "第 " + (i + 1) + " 天超出出发/返回区间，未分配具体日期";
      }
      days.add(new DraftDay(i + 1, date, note, items));
    }
    return List.copyOf(days);
  }

  private static Integer assignedDay(PlanningRequest request,Attraction attraction) {
    if(request.details()==null) return null;
    return request.details().visitDays().entrySet().stream()
        .filter(entry -> PlanningService.matchesMustVisit(entry.getKey(),attraction)).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
  }

  static boolean avoidsClimbing(PlanningRequest request) {
    String mobility=request.details()==null?"":String.valueOf(request.details().mobility());
    String preferences=mobility+String.join("、",request.preferences());
    return preferences.contains("不爬山") || preferences.contains("不登山") || preferences.contains("不爬坡");
  }

  private static boolean incompatibleClimbing(PlanningRequest request,Attraction attraction) {
    if(!avoidsClimbing(request)) return false;
    String evidence=String.valueOf(attraction.type())+"；"+String.valueOf(attraction.description());
    if(evidence.contains("无需爬山") || evidence.contains("无需登山")) return false;
    return java.util.regex.Pattern.compile("登山|爬山|山岳|山峰|攀登").matcher(evidence).find();
  }

  private static boolean limitedMobility(PlanningRequest request) {
    var d=request.details();
    return d!=null && (d.seniors()!=null && d.seniors()>0 || d.mobility()!=null && !d.mobility().equals("无"))
        || request.preferences().stream().anyMatch(p -> p.contains("少走") || p.contains("慢节奏"));
  }

  private static int scheduleLimit(PlanningRequest request, int day) {
    int limit=dailyLimit(request);
    // Reserve arrival/departure capacity; a maximum is not a target to fill.
    boolean intercity=request.details()!=null && request.details().origin()!=null;
    return limitedMobility(request) || intercity && (day==1 || day==request.effectiveDays()) ? 1 : limit;
  }

  static int dailyLimit(PlanningRequest request) {
    Integer limit = request.details() == null ? null : request.details().maxAttractionsPerDay();
    return limit == null ? MAX_PER_DAY : Math.max(1, Math.min(MAX_PER_DAY, limit));
  }

  /** 逐日日期的唯一口径：按出发日顺推；有明确返回日时不得越过返回日。 */
  public static LocalDate dateForDay(PlanningRequest request, int dayIndex) {
    if (request == null || request.departDate() == null || dayIndex < 1) return null;
    LocalDate date = request.departDate().plusDays(dayIndex - 1L);
    LocalDate ret = request.returnDate();
    if (ret != null && (ret.isBefore(request.departDate()) || date.isAfter(ret))) return null;
    return date;
  }

  /** 把知识库记录转成可追溯的草案条目。 */
  public static DraftDay.Item itemOf(Attraction attraction) {
    String ticket = PlaceholderValues.clean(attraction.ticket());
    String openTime = PlaceholderValues.clean(attraction.openTime());
    String source = attraction.sourceText();
    List<String> unknowns = new ArrayList<>();
    if (ticket == null) {
      unknowns.add("票价未知");
    }
    if (openTime == null) {
      unknowns.add("开放时间未知");
    }
    if (source == null || source.isBlank()) {
      source = "未标注";
      unknowns.add("来源未标注");
    }
    if (attraction.id() == null || attraction.id().isBlank()) {
      unknowns.add("景点ID缺失");
    }
    String note = "attractionId=" + attraction.id()
        + "；来源=" + source
        + "；票价=" + PlaceholderValues.display(ticket)
        + "；开放时间=" + PlaceholderValues.display(openTime)
        + "；未知项=" + (unknowns.isEmpty() ? "无" : String.join("、", unknowns));
    return new DraftDay.Item(attraction.id(), attraction.name(), attraction.city(), source,
        ticket, openTime, unknowns, note);
  }

  /** 坐标转换到 GCJ-02；坐标或坐标系缺失、无效时不参与距离判断。 */
  public static com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon coordinate(
      Attraction attraction) {
    if (attraction == null || attraction.lat() == null || attraction.lon() == null
        || !Double.isFinite(attraction.lat()) || !Double.isFinite(attraction.lon())
        || Math.abs(attraction.lat()) > 90 || Math.abs(attraction.lon()) > 180
        || attraction.coordinateSystem() == null || attraction.coordinateSystem().isBlank()) {
      return null;
    }
    return com.travelassistant.server.infrastructure.geo.CoordinateTransforms.toGcj02(
        attraction.lat(), attraction.lon(), attraction.coordinateSystem());
  }

  /** 与其它来源合并前去掉空项，并按目的地身份去重。 */
  public static List<Attraction> uniqueAttractions(List<Attraction> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<Attraction> unique = new ArrayList<>();
    for (Attraction attraction : candidates) {
      if (attraction == null || attraction.name() == null || attraction.name().isBlank()) {
        continue;
      }
      if (unique.stream().noneMatch(existing -> sameDestination(existing, attraction))) {
        unique.add(attraction);
      }
    }
    return List.copyOf(unique);
  }

  /** 日程级去重；无坐标时同城同名只占一个日程项，不因此融合两条记录的字段或来源。 */
  private static List<Attraction> uniqueForSchedule(List<Attraction> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<Attraction> unique = new ArrayList<>();
    for (Attraction attraction : candidates) {
      if (attraction == null || attraction.name() == null || attraction.name().isBlank()) {
        continue;
      }
      if (unique.stream().noneMatch(existing -> sameScheduledDestination(existing, attraction))) {
        unique.add(attraction);
      }
    }
    return List.copyOf(unique);
  }

  private static boolean sameScheduledDestination(Attraction a, Attraction b) {
    if (a.id() != null && a.id().equals(b.id())) {
      return true;
    }
    if (!sameNameAndArea(a, b)) {
      return false;
    }
    var first = coordinate(a);
    var second = coordinate(b);
    if (first == null && second == null) {
      return true;
    }
    if (first == null || second == null) {
      return sameNonBlankAddress(a, b);
    }
    return distanceMeters(first, second) <= SAME_DESTINATION_DISTANCE_METERS;
  }

  private static List<List<Attraction>> groupByDay(List<Attraction> ordered) {
    List<List<Attraction>> groups = new ArrayList<>();
    for (Attraction attraction : ordered) {
      if (groups.isEmpty()) {
        groups.add(new ArrayList<>(List.of(attraction)));
        continue;
      }
      List<Attraction> current = groups.getLast();
      if (current.size() < MAX_PER_DAY && canShareDay(current.getFirst(), attraction)) {
        current.add(attraction);
      } else {
        groups.add(new ArrayList<>(List.of(attraction)));
      }
    }
    return groups;
  }

  private static List<List<Attraction>> packByCapacity(List<Attraction> ordered, int allowedDays) {
    int usable = Math.min(ordered.size(), allowedDays * MAX_PER_DAY);
    List<List<Attraction>> packed = new ArrayList<>();
    for (int from = 0; from < usable; from += MAX_PER_DAY) {
      packed.add(List.copyOf(ordered.subList(from, Math.min(from + MAX_PER_DAY, usable))));
    }
    return List.copyOf(packed);
  }

  private static boolean needsPacking(List<Attraction> selected, int allowedDays) {
    if (allowedDays <= 0 || selected.isEmpty()) {
      return false;
    }
    List<Attraction> ordered = DraftRouteOptimizer.optimize(selected);
    return groupByDay(ordered).size() > allowedDays
        && ordered.size() >= allowedDays * MAX_PER_DAY;
  }

  private static boolean canShareDay(Attraction first, Attraction second) {
    var a = coordinate(first);
    var b = coordinate(second);
    return a != null && b != null && distanceMeters(a, b) <= MAX_SAME_DAY_DISTANCE_METERS;
  }

  private static boolean sameNameAndArea(Attraction a, Attraction b) {
    String cityA = normalizeCity(a.city());
    String cityB = normalizeCity(b.city());
    String provinceA = normalize(a.province());
    String provinceB = normalize(b.province());
    return !cityA.isEmpty() && cityA.equals(cityB)
        && normalize(a.name()).equals(normalize(b.name()))
        && (provinceA.isEmpty() || provinceB.isEmpty() || provinceA.equals(provinceB));
  }

  private static boolean sameNonBlankAddress(Attraction a, Attraction b) {
    String first = normalize(a.address());
    String second = normalize(b.address());
    return !first.isEmpty() && first.equals(second);
  }

  private static double distanceMeters(
      com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon a,
      com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon b) {
    return com.travelassistant.server.infrastructure.geo.CoordinateTransforms.haversineMeters(
        a.lat(), a.lon(), b.lat(), b.lon());
  }

  /** 相同城市中的同一景点只保留一个，坐标别名需有近距离证据。 */
  public static boolean sameDestination(Attraction a, Attraction b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.id() != null && a.id().equals(b.id())) {
      return true;
    }
    if (!sameNameAndArea(a, b)) {
      return false;
    }
    var first = coordinate(a);
    var second = coordinate(b);
    if (first != null && second != null) {
      return distanceMeters(first, second) <= MERGE_DISTANCE_METERS;
    }
    return sameNonBlankAddress(a, b);
  }

  /** 按用户明确填写的排除景点 ID 或名称过滤，不做模糊推荐推断。 */
  public static boolean excluded(PlanningRequest request, Attraction attraction) {
    if (request == null || attraction == null || request.details() == null) {
      return false;
    }
    return request.details().excludedAttractionIds().stream()
        .anyMatch(value -> value.equals(attraction.id())
            || normalize(value).equals(normalize(attraction.name()))
            || normalize(attraction.name()).startsWith(normalize(value) + "景区"));
  }

  /** 用可见等级、评分与评价量解释排序依据；不把评价量说成实时客流。 */
  public static String selectionReason(Attraction attraction) {
    String level = PlaceholderValues.clean(attraction.level());
    String rating = PlaceholderValues.clean(attraction.rating());
    String comments = attraction.evidenceFor("commentCount").stream()
        .map(Attraction.FieldEvidence::value).findFirst().orElse(null);
    String popularity = comments == null
        ? "评价量/热度未返回，热门程度未核验"
        : "接口评价量=" + comments + "，来源=" + attraction.fieldSource("commentCount")
            + "；评价量不等于实时客流";
    if (level != null) {
      return "匹配必去点和偏好后按可见评分/等级排序；等级「" + level + "」，来源「"
          + attraction.fieldSource("level") + "」；评分="
          + (rating == null ? "未返回" : rating) + "；" + popularity;
    }
    if (rating != null) {
      return "等级未返回，按可见评分「" + rating + "」排序，字段来源「"
          + attraction.fieldSource("rating") + "」；" + popularity;
    }
    return "来自目的地景点检索结果；等级、评分、热度/评论量均未返回，无法核验其热门程度";
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[\\s　、，。·•\\-—_（）()【】\\[\\]]+", "");
  }

  private static String normalizeCity(String raw) {
    String city = normalize(raw);
    for (String suffix : List.of("特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区",
        "自治区", "自治州", "地区", "市", "县")) {
      if (city.endsWith(suffix) && city.length() > suffix.length()) {
        return city.substring(0, city.length() - suffix.length());
      }
    }
    return city;
  }

  // ---- 补问与检查规则 ----

  private static void collectQuestions(PlanningRequest request, List<DraftQuestion> questions,
      List<DraftCheck> checks, List<String> notices) {
    if (request.departDate() == null) {
      questions.add(question("MISSING_DEPART_DATE", "departDate", "请填写出发日期",
          "YYYY-MM-DD，例如 2026-11-20"));
    }
    if (request.returnDate() == null) {
      questions.add(question("MISSING_RETURN_DATE", "returnDate", "请填写返回日期",
          "YYYY-MM-DD，不得早于出发日期"));
    }
    if (request.days() == null || request.days() <= 0) {
      if (request.dateRangeDays() == null) {
        questions.add(question("MISSING_DAYS", "days", "请填写计划游玩天数",
            "1~30 的整数；也可以改为填写出发与返回日期，由系统换算"));
      } else {
        notices.add("未提供游玩天数，按出发/返回日期推算为 " + request.dateRangeDays() + " 天");
      }
    } else if (request.days() > MAX_DAYS) {
      checks.add(new DraftCheck("DAYS_TOO_LARGE", "MEDIUM", "date",
          "游玩天数 " + request.days() + " 超过上限 " + MAX_DAYS + "，逐日草案只生成前 "
              + MAX_DAYS + " 天", "把天数调整到 " + MAX_DAYS + " 天以内"));
    }
    if (request.travelers() == null) {
      questions.add(question("MISSING_TRAVELERS", "travelers", "请填写同行人数",
          "正整数，用于计算人均费用与门票合计"));
    } else if (request.travelers() <= 0) {
      checks.add(new DraftCheck("INVALID_TRAVELERS", "HIGH", "budget",
          "同行人数为 " + request.travelers() + "，无法计算费用分摊", "填写大于 0 的人数"));
    }
    if (request.budget() == null) {
      questions.add(question("MISSING_BUDGET", "budget", "请填写预算上限",
          "数字，单位由 currency 决定（默认 CNY）"));
    } else if (request.budget().signum() <= 0) {
      checks.add(new DraftCheck("INVALID_BUDGET", "HIGH", "budget",
          "预算上限为 " + request.budget().toPlainString() + "，不是有效的上限",
          "填写大于 0 的预算，或留空表示暂不核对"));
    }
  }

  private static DraftQuestion question(String code, String field, String text, String hint) {
    return new DraftQuestion(code, field, text, hint, RETRY);
  }

  private static void collectDateChecks(PlanningRequest request, int plannedDays,
      List<DraftCheck> checks) {
    LocalDate depart = request.departDate();
    LocalDate ret = request.returnDate();
    if (depart == null || ret == null) {
      checks.add(new DraftCheck("DATE_INCOMPLETE", "INFO", "date",
          "出发日期或返回日期缺失，逐日草案不含具体日历日期",
          "补全 departDate 与 returnDate 后重新生成草案"));
      return;
    }
    if (ret.isBefore(depart)) {
      checks.add(new DraftCheck("RETURN_BEFORE_DEPART", "HIGH", "date",
          "返回日期 " + ret + " 早于出发日期 " + depart, "改正日期后再确认保存，否则行程接口会拒绝"));
      return;
    }
    if (depart.isBefore(LocalDate.now())) {
      checks.add(new DraftCheck("DEPART_IN_PAST", "MEDIUM", "date",
          "出发日期 " + depart + " 早于今天，请确认是否写错了年份", "核对出发日期"));
    }
    Integer span = request.dateRangeDays();
    if (span != null && request.days() != null && request.days() > 0
        && !request.days().equals(span)) {
      checks.add(new DraftCheck("DAYS_DATE_MISMATCH", "MEDIUM", "date",
          "游玩天数 " + request.days() + " 与日期区间（含首尾 " + span + " 天）不一致",
          "统一 days 与日期区间，逐日草案以 days 为准"));
    }
    if (span != null && plannedDays > span) {
      checks.add(new DraftCheck("DRAFT_DAY_OUT_OF_RANGE", "HIGH", "date",
          "逐日草案共 " + plannedDays + " 天，超过日期区间 " + span + " 天，超出的天没有具体日期",
          "缩短 days 或把 returnDate 往后调"));
    }
  }

  public static DraftBudgetEstimate estimate(PlanningRequest request,
      List<Attraction> selected, List<DraftCheck> checks) {
    BigDecimal subtotal = BigDecimal.ZERO;
    int unknown = 0;
    for (Attraction attraction : selected) {
      BigDecimal known = knownTicket(attraction);
      if (known == null) {
        unknown++;
      } else {
        subtotal = subtotal.add(known);
      }
    }
    boolean travelersMissing = request.travelers() == null || request.travelers() <= 0;
    int travelers = travelersMissing ? 1 : request.travelers();
    BigDecimal total = subtotal.multiply(BigDecimal.valueOf(travelers));
    BigDecimal budget = request.budget();
    String currency = request.currencyOrDefault();

    String verdict;
    String message;
    if (budget == null) {
      verdict = "NO_BUDGET";
      message = "未提供预算上限，无法核对是否超支";
      checks.add(new DraftCheck("NO_BUDGET", "INFO", "budget", message, "填写 budget 后重新生成草案"));
    } else if (budget.signum() <= 0) {
      verdict = "INVALID_BUDGET";
      message = "预算上限不是正数，无法核对是否超支";
    } else if (total.compareTo(budget) > 0) {
      verdict = "EXCEEDS_LIMIT";
      message = "仅按已核验门票计算就已超预算：小计 " + total.toPlainString() + " " + currency
          + " > 上限 " + budget.toPlainString() + " " + currency;
      checks.add(new DraftCheck("BUDGET_EXCEEDS_LIMIT", "HIGH", "budget", message,
          "提高预算、减少景点，或核实票价后再确认保存"));
    } else if (unknown > 0 || travelersMissing) {
      verdict = "CANNOT_DETERMINE";
      message = "已核验门票小计 " + total.toPlainString() + " " + currency
          + " 未超过上限，但仍有 " + unknown + " 个景点票价未经官方核验"
          + (travelersMissing ? "、同行人数未提供" : "")
          + "，无法判定总花费是否超支";
      if (unknown > 0) {
        checks.add(new DraftCheck("UNKNOWN_TICKET_PRICE", "MEDIUM", "budget",
            "有 " + unknown + " 个景点票价未知或未核验，未计入预算粗估",
            "核实票价后再判断；未知不等于免费"));
      }
    } else {
      verdict = "WITHIN_KNOWN_SCOPE";
      message = "已核验门票小计 " + total.toPlainString() + " " + currency
          + " 未超过上限（仅门票口径）";
    }
    String scope = "统计范围：仅已核验且能解析出金额的门票，按 " + travelers + " 人计；"
        + (travelersMissing ? "人数未提供，按 1 人保守估算；" : "")
        + "不含交通、住宿、餐饮，未知票价未计入。";
    return new DraftBudgetEstimate(currency, budget, request.travelers(), subtotal, total,
        unknown, verdict, message + "。" + scope);
  }

  /** 只有状态可信且金额可解析的票价才参与估算；「免费」按 0 计。 */
  private static BigDecimal knownTicket(Attraction attraction) {
    String status = attraction.ticketStatus();
    boolean trusted = status != null
        && ("verified".equalsIgnoreCase(status) || "confirmed".equalsIgnoreCase(status));
    String ticket = PlaceholderValues.clean(attraction.ticket());
    if (!trusted || ticket == null) {
      return null;
    }
    if (ticket.contains("免费")) {
      return BigDecimal.ZERO;
    }
    Matcher matcher = AMOUNT.matcher(ticket);
    if (!matcher.find()) {
      return null;
    }
    try {
      return new BigDecimal(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
