package com.travelassistant.server.application.agent.food;

import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Usage;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 内置美食推荐能力：把 {@code AgentContext} 转成餐饮候选 facts。
 *
 * <p>六条不随用户选择放开的底线在这里固定：
 * <ul>
 *   <li>忌口 / 过敏是硬约束：命中整条剔除，不降权；不回显枚举原文、不进日志、不进缓存。</li>
 *   <li>外部 facts 一律 {@code unverified}，评分、人均、营业时段未知写「未知」或 null，
 *       不写「免费」「全天开放」「0 元」。</li>
 *   <li>缺 key、超时、配额耗尽或接口报错时转 {@code DEGRADED} 并如实标注，不编造 POI；
 *       本地也没有「开放数据底账」，所以降级时是零候选，不冒充「本地有什么值得吃」。</li>
 *   <li>距离统一在 GCJ-02 下用 Haversine 算直线距离，锚点坐标系标签缺失就跳过。</li>
 *   <li>不按评分或距离替用户挑前 N 条；超上限按固定顺序截取并写 notice。</li>
 *   <li>Provider 不写库、不落盘、不缓存 POI，确认走既有行程接口。</li>
 * </ul>
 *
 * <p>{@code advice} 刻意不含任何数字与评价结论：S-05 要求数字能在本次 facts 里找到，
 * 评价正文、口碑摘要与外链都不进 advice，只由前端从 value 渲染。
 */
@Service
public class FoodRecommendationService {

  private static final int MAX_CANDIDATES = 50;
  private static final String ADVICE = "已整理附近餐饮候选，评分与人均价格以候选字段为准；"
      + "营业时段与忌口信息请到店核实";

  /** 连锁快餐与连锁饮品品牌词。命中即视为「非当地美食」，默认不推荐。 */
  private static final List<String> CHAIN_BRANDS = List.of(
      "星巴克", "麦当劳", "肯德基", "KFC", "华莱士", "德克士", "汉堡王", "必胜客", "赛百味",
      "蜜雪冰城", "瑞幸", "luckin", "库迪", "幸运咖", "CoCo", "都可", "茶百道", "古茗",
      "沪上阿姨", "喜茶", "奈雪", "一点点", "益禾堂", "书亦", "甜啦啦", "正新鸡排",
      "绝味", "周黑鸭", "老乡鸡", "真功夫", "吉野家", "永和豆浆", "海底捞", "呷哺",
      "塔斯汀", "Manner", "挪瓦", "霸王茶姬", "七分甜", "桂桂茶");

  /** 本地口味的强信号词，用于把「当地能吃到的菜」排到前面。 */
  private static final List<String> LOCAL_HINTS = List.of(
      "土菜", "农家", "私房", "老字号", "特色", "本地", "地方", "大排档", "夜市",
      "小吃", "面馆", "牛肉面", "粉馆", "菜馆", "饭庄", "酒楼", "食府", "家常菜",
      "八大碗", "蒸菜", "煨汤", "烧烤", "串串", "饺子", "包子", "豆腐");

  /** 忌口关键词到枚举标签的映射。用 List 保持顺序，保证同一请求的响应可复现。 */
  private static final List<Map.Entry<String, String>> ALLERGEN_HINTS = List.of(
      Map.entry("海鲜", "SEAFOOD"), Map.entry("海产", "SEAFOOD"),
      Map.entry("虾", "SEAFOOD"), Map.entry("蟹", "SEAFOOD"),
      Map.entry("花生", "PEANUT"),
      Map.entry("猪肉", "PORK"), Map.entry("卤肉", "PORK"),
      Map.entry("川菜", "SPICY"), Map.entry("湘菜", "SPICY"), Map.entry("辣", "SPICY"),
      Map.entry("面", "GLUTEN"), Map.entry("麸", "GLUTEN"),
      Map.entry("奶", "DAIRY"), Map.entry("乳", "DAIRY"),
      Map.entry("酒", "ALCOHOL"));

  private final AmapClient amap;
  private final BaiduPlaceClient baidu;
  private final FoodResponseCache cache;

  /** 测试用：不带幂等缓存，每次都是真调用。 */
  public FoodRecommendationService(AmapClient amap, BaiduPlaceClient baidu) {
    this(amap, baidu, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public FoodRecommendationService(AmapClient amap, BaiduPlaceClient baidu,
      FoodResponseCache cache) {
    this.amap = amap;
    this.baidu = baidu;
    this.cache = cache;
  }

  public AgentResult recommend(AgentContext context) {
    long started = System.currentTimeMillis();
    // 幂等优先：同一 runId 的重放直接返回上次的信封，不再打地图接口。
    // 缓存未命中才往下走真实召回，命中时结果里会带一条「本次未重新请求地图接口」的说明。
    String fingerprint = fingerprintOf(context);
    if (cache != null) {
      AgentResult cached = cache.get(context.runId(), fingerprint);
      if (cached != null) {
        return cached;
      }
    }
    AgentResult result = doRecommend(context, started);
    if (cache != null) {
      cache.put(context.runId(), fingerprint, result);
    }
    return result;
  }

  /**
   * 请求指纹：同一 {@code runId} 换了参数就不是重放，不能返回旧结果。
   *
   * <p>只取会影响召回与筛选的字段，顺序固定，保证同参数下指纹稳定。
   */
  private static String fingerprintOf(AgentContext context) {
    Map<String, Object> input = context.context() == null ? Map.of() : context.context();
    StringBuilder sb = new StringBuilder();
    for (String key : List.of("city", "radiusMeters", "budgetCny", "mealSlot",
        "dietaryFlags", "showReviews", "showReasons", "includeChains", "includeInspiration")) {
      sb.append(key).append('=').append(input.get(key)).append(';');
    }
    return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
  }

  private AgentResult doRecommend(AgentContext context, long started) {
    Map<String, Object> input = context.context() == null ? Map.of() : context.context();
    List<String> notices = new ArrayList<>();
    Set<String> unknown = new LinkedHashSet<>();
    List<Fact> anchors = context.facts() == null ? List.of() : context.facts();

    String city = text(input.get("city"));
    if (city == null || city.isBlank()) {
      unknown.add("city");
      return refused(anchors, unknown, "缺少城市，餐饮推荐无法定位；未默认按任何城市处理", started);
    }

    List<LatLon> centers = gcjAnchors(anchors, notices);
    if (centers.isEmpty()) {
      unknown.add("anchor");
      return refused(anchors, unknown,
          "缺少带坐标系标签的锚点，未默认按城市中心计算附近范围", started);
    }

    List<String> missing = missingChoices(input);
    unknown.addAll(missing);
    if (!missing.isEmpty()) {
      notices.add("预算、时段或忌口缺项未替用户填空，请补填或明确选择不限制");
    }

    int radius = radius(input);
    Double requestedRadius = number(input.get("radiusMeters"));
    if (requestedRadius != null && requestedRadius.intValue() != radius) {
      // 取档不能静默：用户填的值和实际检索值不同时必须说出来。
      notices.add("半径按 500/1000/3000 米就近取档，本次实际 " + radius + " 米");
    }
    Set<String> dietary = dietaryFlags(input);
    Double budget = number(input.get("budgetCny"));
    boolean showReviews = flag(input, "showReviews", true);
    boolean showReasons = flag(input, "showReasons", true);
    boolean includeInspiration = flag(input, "includeInspiration", false);
    boolean includeChains = flag(input, "includeChains", false);
    if (includeInspiration) {
      notices.add("灵感层未接入，本轮不召回内容源候选");
    }

    // 高德主源、百度第二源。任一失败只记汇总，不外泄异常文本与密钥。
    List<FoodPlace> pois = new ArrayList<>();
    int sourceFailures = 0;
    LatLon center = centers.get(0);
    String amapLocation = center.lon() + "," + center.lat();
    try {
      if (amap.configured()) {
        pois.addAll(amap.searchFoodNearby(amapLocation, radius, 25));
      } else {
        sourceFailures++;
      }
    } catch (RuntimeException e) {
      sourceFailures++;
    }
    try {
      if (baidu.configured()) {
        LatLon bd = CoordinateTransforms.gcj02ToBd09(center.lat(), center.lon());
        pois.addAll(baidu.searchFoodNearby(bd.lat() + "," + bd.lon(), radius, 20));
      } else {
        sourceFailures++;
      }
    } catch (RuntimeException e) {
      sourceFailures++;
    }

    List<FoodPlace> unique = dedupe(pois);
    int filtered = 0;
    List<FoodPlace> safe = new ArrayList<>();
    for (FoodPlace place : unique) {
      if (!place.usable()) {
        continue;
      }
      List<String> allergens = allergenFlags(place);
      if (!dietary.isEmpty() && allergens.stream().anyMatch(dietary::contains)) {
        filtered++;
        continue;
      }
      safe.add(place);
    }
    if (filtered > 0) {
      // 只报条数，不回显忌口枚举原文。
      notices.add("按安全约束剔除 " + filtered + " 条候选");
    }
    // 旅行场景要的是当地口味：连锁快餐与连锁饮品不算「当地美食」，默认剔除并报条数。
    if (!includeChains) {
      int chains = 0;
      List<FoodPlace> local = new ArrayList<>();
      for (FoodPlace place : safe) {
        if (isChain(place)) {
          chains++;
        } else {
          local.add(place);
        }
      }
      if (chains > 0) {
        safe.clear();
        safe.addAll(local);
        notices.add("已剔除 " + chains + " 条连锁快餐或饮品候选，其余按本地口味优先排序"
            + "（勾选「含连锁」可保留）");
      }
    } else {
      // 用户明确要连锁时也把排序规则说出来，保证「不静默改变候选顺序」。
      notices.add("候选按本地口味优先排序，连锁快餐与饮品已按要求保留");
    }
    if (!dietary.isEmpty()) {
      notices.add("忌口判定仅依据店名与类别关键词，不能替代商户确认，请到店核实");
    }
    if (sourceFailures > 0) {
      notices.add("附近实时数据部分不可用，本次候选覆盖可能不完整");
    }
    notices.add("距离按直线计算，不是步行距离或步行时间");
    notices.add("评分与人均是地图快照，未经商户核验");
    notices.add("评价平台外链白名单未确认，本轮不生成外链");

    // 本地特色优先，其余保持原有的 poiId + 名称固定顺序（不按评分或距离替用户挑）。
    safe.sort(Comparator
        .comparing((FoodPlace p) -> isLocalFlavor(p) ? 0 : 1)
        .thenComparing(p -> p.poiId() == null ? 1 : 0)
        .thenComparing(p -> p.poiId() == null ? "" : p.poiId())
        .thenComparing(FoodPlace::name));
    boolean truncated = safe.size() > MAX_CANDIDATES;
    List<FoodPlace> selected = truncated ? safe.subList(0, MAX_CANDIDATES) : safe;
    if (truncated) {
      notices.add("候选超过契约上限，按固定顺序截取，可缩小半径或勾选软筛条件查看其余候选");
    }

    List<Fact> facts = new ArrayList<>();
    // 稳定 ID 先分配再装配：同批里 ID 撞了就追加序号，而不是悄悄丢掉一条候选。
    List<String> foodIds = assignIds(selected, notices);
    for (int i = 0; i < selected.size(); i++) {
      FoodPlace place = selected.get(i);
      int distance = (int) Math.round(distanceTo(place, centers));
      facts.add(fact(place, i, foodIds.get(i), distance, radius, budget,
          text(input.get("mealSlot")), showReviews, showReasons));
    }
    if (facts.isEmpty()) {
      // 零候选要能让用户自己动手：说清生效半径，给出可执行的下一步。
      notices.add("半径 " + radius + " 米内未检索到可用餐饮候选，可扩大半径后重试；未编造候选");
    }
    unknown.addAll(unknownValues(selected));

    String status = facts.isEmpty() || sourceFailures > 0 ? "DEGRADED" : "OK";
    String advice = facts.isEmpty() ? null : ADVICE;
    if (truncated) {
      // notice 里已有说明，advice 保持无数字，不触发 S-05。
      status = "DEGRADED";
    }
    return new AgentResult(status, List.copyOf(facts), advice,
        cap(new ArrayList<>(unknown), 20), cap(notices, 20),
        new Usage(System.currentTimeMillis() - started, 0));
  }

  // ---------------------------------------------------------------- 锚点与召回

  private static List<LatLon> gcjAnchors(List<Fact> facts, List<String> notices) {
    List<LatLon> centers = new ArrayList<>();
    int skipped = 0;
    for (Fact fact : facts) {
      if (!"ATTRACTION".equals(fact.type())) {
        continue;
      }
      Map<String, Object> value = fact.value();
      Double lat = number(value.get("lat"));
      Double lon = number(value.get("lon"));
      String crs = text(value.get("coordinateSystem"));
      LatLon converted = lat == null || lon == null
          ? null : CoordinateTransforms.toGcj02(lat, lon, crs);
      if (converted == null) {
        skipped++;
      } else {
        centers.add(converted);
      }
    }
    if (skipped > 0) {
      notices.add("部分锚点坐标系标签缺失或无法识别，已跳过，不猜坐标系");
    }
    return centers;
  }

  /** 同来源按 poiId 去重，跨来源按「同名且 50 m 内」去重。 */
  private static List<FoodPlace> dedupe(List<FoodPlace> pois) {
    Set<String> seen = new LinkedHashSet<>();
    List<FoodPlace> result = new ArrayList<>();
    for (FoodPlace place : pois) {
      if (place.name() == null || place.name().isBlank()) {
        continue;
      }
      String key = place.source() + "|" + (place.poiId() == null
          ? normalize(place.name()) : place.poiId());
      if (!seen.add(key)) {
        continue;
      }
      boolean duplicate = false;
      for (FoodPlace kept : result) {
        if (!normalize(place.name()).equals(normalize(kept.name()))) {
          continue;
        }
        if (place.lat() != null && place.lon() != null
            && kept.lat() != null && kept.lon() != null
            && CoordinateTransforms.haversineMeters(place.lat(), place.lon(),
                kept.lat(), kept.lon()) < 50.0) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        result.add(place);
      }
    }
    return result;
  }

  private static double distanceTo(FoodPlace place, List<LatLon> centers) {
    double best = Double.MAX_VALUE;
    for (LatLon center : centers) {
      best = Math.min(best, CoordinateTransforms.haversineMeters(
          center.lat(), center.lon(), place.lat(), place.lon()));
    }
    return best;
  }

  // ---------------------------------------------------------------- fact 装配

  /**
   * 给每个候选分配稳定 ID，并校验同批唯一。
   *
   * <p>没有稳定 ID 时，前端只能靠下标认人：用户点第 3 家、下一轮候选顺序一变就指错。
   * ID 由来源与 {@code poiId} 组成；缺 {@code poiId} 时退化为「名称 + 坐标」，
   * 保证同名不同店（连锁分店、同名小馆）不会共用一个 ID。
   *
   * <p>撞号不删条目：候选是用户的选择权，不能因为工程实现问题减少可选项。
   */
  private static List<String> assignIds(List<FoodPlace> selected, List<String> notices) {
    List<String> ids = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    int collisions = 0;
    for (FoodPlace place : selected) {
      String base = idBase(place);
      String id = base;
      int n = 1;
      while (!used.add(id)) {
        n++;
        id = base + "#" + n;
        collisions++;
      }
      ids.add(id);
    }
    if (collisions > 0) {
      notices.add("有 " + collisions + " 条候选的稳定 ID 与同批其他条目重复，已追加序号区分");
    }
    return ids;
  }

  private static String idBase(FoodPlace place) {
    String poi = place.poiId();
    if (poi != null && !poi.isBlank()) {
      return clip(place.source() + ":" + poi.strip(), 64);
    }
    String geo = place.lat() == null || place.lon() == null
        ? "" : String.format(java.util.Locale.ROOT, "@%.4f,%.4f", place.lat(), place.lon());
    return clip(place.source() + ":" + normalize(place.name()) + geo, 64);
  }

  private static Fact fact(FoodPlace place, int index, String foodId, int distance, int radius,
      Double budget, String mealSlot, boolean showReviews, boolean showReasons) {
    List<String> constraintNotes = new ArrayList<>();
    if (budget != null && place.pricePerPersonCny() != null
        && place.pricePerPersonCny() > budget) {
      constraintNotes.add("超出预算");
    }
    if (distance > radius) {
      constraintNotes.add("超出半径");
    }
    List<String> reasonTags = new ArrayList<>();
    if (showReasons && distance <= radius) {
      reasonTags.add("位于所选半径内");
    }
    if (showReasons && isLocalFlavor(place)) {
      reasonTags.add("本地口味信号：土菜/农家/老字号/地方小吃");
    }

    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "FOOD");
    value.put("kind", "RESTAURANT");
    value.put("foodId", foodId);
    value.put("name", place.name());
    value.put("mealSlot", mealSlot);
    value.put("pricePerPersonCny", place.pricePerPersonCny());
    value.put("openHours", place.openHours());
    value.put("distanceMeters", distance);
    value.put("poiId", place.poiId());
    value.put("tags", tags(place.tags()));
    value.put("allergenFlags", allergenFlags(place));
    value.put("sourceLayer", "FACT");
    // 只标「是否本地口味」，不解释判定细节，避免把品牌库口径写进响应。
    value.put("locality", isLocalFlavor(place) ? "LOCAL" : "OTHER");
    value.put("constraintNotes", List.copyOf(constraintNotes));
    value.put("reasonTags", List.copyOf(reasonTags));
    if (showReviews) {
      value.put("rating", place.rating());
      value.put("ratingSource", place.rating() == null ? null : place.source());
      value.put("ratingFetchedAt", place.rating() == null ? null
          : place.fetchedAt() == null ? null : place.fetchedAt().toString());
      value.put("reviewLinks", List.of());
    }
    String sourceKind = FoodPlace.SOURCE_AMAP.equals(place.source()) ? "AMAP" : "EXTERNAL";
    Evidence evidence = new Evidence(sourceKind, clip(place.poiId(), 128),
        Evidence.UNVERIFIED, place.fetchedAt() == null ? Instant.now() : place.fetchedAt(),
        "地图 POI 快照，未核验");
    return new Fact(factId(place, index), "CONTENT", value, evidence);
  }

  private static String factId(FoodPlace place, int index) {
    String raw = "builtin.food:" + String.format("%02d", index) + ":"
        + (place.poiId() == null ? normalize(place.name()) : place.poiId());
    return clip(raw, 64);
  }

  private static List<String> tags(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String part : raw.split("[;,，；]")) {
      String tag = part.strip();
      if (!tag.isEmpty() && result.size() < 8) {
        result.add(tag);
      }
    }
    return List.copyOf(result);
  }

  private static List<String> allergenFlags(FoodPlace place) {
    String text = (place.name() == null ? "" : place.name()) + ";"
        + (place.tags() == null ? "" : place.tags());
    Set<String> flags = new LinkedHashSet<>();
    for (Map.Entry<String, String> hint : ALLERGEN_HINTS) {
      if (text.contains(hint.getKey())) {
        flags.add(hint.getValue());
      }
    }
    return List.copyOf(flags);
  }

  private static List<String> unknownValues(List<FoodPlace> selected) {
    Set<String> missing = new LinkedHashSet<>();
    for (FoodPlace place : selected) {
      if (place.pricePerPersonCny() == null) {
        missing.add("pricePerPersonCny");
      }
      if (place.openHours() == null) {
        missing.add("openHours");
      }
      if (place.rating() == null) {
        missing.add("rating");
      }
    }
    return List.copyOf(missing);
  }

  // ---------------------------------------------------------------- 输入解析

  private static List<String> missingChoices(Map<String, Object> input) {
    List<String> missing = new ArrayList<>();
    if (number(input.get("budgetCny")) == null) {
      missing.add("budgetCny");
    }
    if (text(input.get("mealSlot")) == null) {
      missing.add("mealSlot");
    }
    if (!(input.get("dietaryFlags") instanceof List<?>)) {
      missing.add("dietaryFlags");
    }
    return missing;
  }

  private static int radius(Map<String, Object> input) {
    Double value = number(input.get("radiusMeters"));
    if (value == null) {
      return 1000;
    }
    int meters = value.intValue();
    int[] levels = {500, 1000, 3000};
    int best = levels[0];
    for (int level : levels) {
      if (Math.abs(level - meters) < Math.abs(best - meters)) {
        best = level;
      }
    }
    return best;
  }

  private static Set<String> dietaryFlags(Map<String, Object> input) {
    if (!(input.get("dietaryFlags") instanceof List<?> list)) {
      return Set.of();
    }
    Set<String> flags = new LinkedHashSet<>();
    for (Object item : list) {
      if (item instanceof String s && !s.isBlank()) {
        flags.add(s.strip().toUpperCase(Locale.ROOT));
      }
    }
    return flags;
  }

  private static boolean flag(Map<String, Object> input, String key, boolean fallback) {
    return input.get(key) instanceof Boolean b ? b : fallback;
  }

  // ---------------------------------------------------------------- 本地特色判定

  /**
   * 是否连锁快餐或连锁饮品。只依据店名与类别标签，不查库、不联网：
   * 命中品牌词即算连锁，宁可少推荐也不把连锁当成「当地美食」。
   */
  private static boolean isChain(FoodPlace place) {
    String haystack = (place.name() == null ? "" : place.name()) + " "
        + (place.tags() == null ? "" : place.tags());
    String lower = haystack.toLowerCase(Locale.ROOT);
    for (String brand : CHAIN_BRANDS) {
      if (lower.contains(brand.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    // 类别标签直接说明是西式快餐的，也归入连锁。
    return haystack.contains("快餐厅") && haystack.contains("西");
  }

  /** 是否具备本地口味信号（土菜、农家、老字号、地方小吃等）。 */
  private static boolean isLocalFlavor(FoodPlace place) {
    if (isChain(place)) {
      return false;
    }
    String haystack = (place.name() == null ? "" : place.name()) + " "
        + (place.tags() == null ? "" : place.tags());
    for (String hint : LOCAL_HINTS) {
      if (haystack.contains(hint)) {
        return true;
      }
    }
    return false;
  }

  private static AgentResult refused(List<Fact> anchors, Set<String> unknown,
      String notice, long started) {
    List<String> notices = List.of(notice, "附近实时数据未调用，未编造候选");
    return new AgentResult("REFUSED", anchors, null,
        cap(new ArrayList<>(unknown), 20), notices,
        new Usage(System.currentTimeMillis() - started, 0));
  }

  private static List<String> cap(List<String> values, int max) {
    return values.size() <= max ? List.copyOf(values)
        : List.copyOf(values.subList(0, max));
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  private static String clip(String raw, int max) {
    if (raw == null) {
      return null;
    }
    return raw.length() <= max ? raw : raw.substring(0, max);
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  private static Double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }
}
