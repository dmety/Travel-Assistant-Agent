package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.attraction.PlaceholderValues;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 景点知识库仓库（**只读**）。
 *
 * <p>启动时加载离线构建产物 {@code attractions_index.json}，在内存中建立
 * 城市索引（含城市别名）、名称索引与 ID 索引；查询全部走内存，不触碰磁盘。
 *
 * <p><b>不覆盖原库</b>：本类没有任何写入逻辑。文件缺失、JSON 损坏或结构异常时，
 * 只把自身标记为 {@link Status#available() 不可用} 并记录原因，绝不尝试修复、
 * 重建或覆盖磁盘上的库文件——修复应由 Python 离线构建流程人工完成。
 */
@Repository
public class AttractionStore {
  private static final Logger log = LoggerFactory.getLogger(AttractionStore.class);

  /** 行政区划常见后缀，用于生成"浙江 / 浙江省"这类别名。 */
  private static final List<String> REGION_SUFFIXES = List.of(
      "维吾尔自治区", "壮族自治区", "回族自治区", "自治区", "特别行政区", "省", "市");

  /**
   * 结果排序：信息越完整越靠前，中文名优先于外文名。
   * 目的是让"杭州"这类城市查询先给出有等级/评分/开放时间的实景，
   * 而不是一堆字段全空的 OSM 英文点位。
   */
  private static final Comparator<Attraction> RICHNESS = Comparator
      .comparingInt((Attraction a) -> a.level() == null ? 1 : 0)
      .thenComparingInt(a -> a.rating() == null ? 1 : 0)
      .thenComparingInt(a -> a.openTime() == null ? 1 : 0)
      .thenComparingInt(a -> a.ticket() == null ? 1 : 0)
      .thenComparingInt(a -> isChineseName(a.name()) ? 0 : 1)
      .thenComparing(Attraction::name, Comparator.nullsLast(Comparator.naturalOrder()));

  private final ObjectMapper mapper;
  private final Path kbPath;

  /** 加载状态：不可用时 reason 说明原因。 */
  private volatile Status status = Status.disabled("尚未加载", null);

  /** id → 景点。 */
  private volatile Map<String, Attraction> idIndex = Map.of();
  /** 城市/省份名（含别名）→ 景点列表。 */
  private volatile Map<String, List<Attraction>> cityIndex = Map.of();
  /** 全量列表（已按信息丰富度排序）。 */
  private volatile List<Attraction> all = List.of();
  /** 小写名称 → 景点，用于精确名命中优先。 */
  private volatile Map<String, List<Attraction>> nameIndex = Map.of();

  public AttractionStore(ObjectMapper mapper,
      @Value("${travel.attraction-kb-path}") String kbPath) {
    this.mapper = mapper;
    this.kbPath = Paths.get(kbPath).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void load() {
    if (!Files.exists(kbPath)) {
      disable("景点知识库文件不存在：" + kbPath + "（请先运行 python tools/build_attraction_index.py）");
      return;
    }
    if (Files.isDirectory(kbPath)) {
      disable("景点知识库路径是目录而非文件：" + kbPath);
      return;
    }

    JsonNode root;
    try {
      root = mapper.readTree(kbPath.toFile());
    } catch (IOException | RuntimeException e) {
      disable("景点知识库解析失败：" + kbPath + "（" + e.getClass().getSimpleName() + ": "
          + e.getMessage() + "）");
      return;
    }

    JsonNode array = root == null ? null : root.get("attractions");
    if (array == null || !array.isArray()) {
      disable("景点知识库结构异常：缺少 attractions 数组（" + kbPath + "）");
      return;
    }

    List<Attraction> loaded = new ArrayList<>(array.size());
    int skipped = 0;
    for (JsonNode node : array) {
      try {
        Attraction raw = mapper.treeToValue(node, Attraction.class);
        Attraction cleaned = normalize(raw);
        if (cleaned != null && cleaned.name() != null && !cleaned.name().isBlank()) {
          loaded.add(cleaned);
        } else {
          skipped++;
        }
      } catch (Exception e) {
        skipped++;
      }
    }

    if (loaded.isEmpty()) {
      disable("景点知识库中没有可用记录（共 " + array.size() + " 条，全部无法解析或缺少名称）：" + kbPath);
      return;
    }

    loaded.sort(RICHNESS);
    buildIndexes(loaded);

    this.all = List.copyOf(loaded);
    this.status = new Status(true, null, loaded.size(), cityIndex.size(),
        kbPath.toString(), Instant.now().toString());
    log.info("景点知识库已加载：{} 条，{} 个城市/省份索引{}", loaded.size(), cityIndex.size(),
        skipped > 0 ? "（跳过 " + skipped + " 条异常记录）" : "");
  }

  /** 兜底规范化：占位值转 null，等级伪值清空，地址拼接垃圾清空。 */
  private Attraction normalize(Attraction a) {
    String city = PlaceholderValues.clean(a.city());
    String province = PlaceholderValues.clean(a.province());
    String address = cleanAddress(a.address(), city, province);
    String level = PlaceholderValues.clean(a.level());
    if (level != null && ("其他".equals(level) || "其它".equals(level))) {
      level = null;
    }
    List<String> sources = a.sources() == null ? List.of() : a.sources().stream()
        .map(PlaceholderValues::clean)
        .filter(s -> s != null)
        .distinct()
        .toList();

    return new Attraction(
        PlaceholderValues.clean(a.id()),
        PlaceholderValues.clean(a.name()),
        city,
        province,
        PlaceholderValues.clean(a.type()),
        level,
        PlaceholderValues.clean(a.rating()),
        PlaceholderValues.clean(a.openTime()),
        PlaceholderValues.clean(a.ticket()),
        address,
        PlaceholderValues.clean(a.tel()),
        PlaceholderValues.clean(a.website()),
        PlaceholderValues.clean(a.image()),
        PlaceholderValues.clean(a.description()),
        a.lat(),
        a.lon(),
        PlaceholderValues.clean(a.source()),
        sources,
        PlaceholderValues.clean(a.ticketStatus()),
        PlaceholderValues.clean(a.openTimeStatus()));
  }

  /** 地址等于城市/省份或其简单拼接（如"北京市北京"）时视为无效。 */
  private static String cleanAddress(String address, String city, String province) {
    String text = PlaceholderValues.clean(address);
    if (text == null) {
      return null;
    }
    LinkedHashSet<String> noise = new LinkedHashSet<>();
    if (city != null) {
      noise.add(city);
    }
    if (province != null) {
      noise.add(province);
    }
    if (city != null && province != null) {
      noise.add(city + province);
      noise.add(province + city);
    }
    return noise.contains(text) ? null : text;
  }

  private void buildIndexes(List<Attraction> items) {
    Map<String, Attraction> byId = new LinkedHashMap<>();
    Map<String, List<Attraction>> byCity = new LinkedHashMap<>();
    Map<String, List<Attraction>> byName = new LinkedHashMap<>();

    for (Attraction a : items) {
      if (a.id() != null) {
        byId.put(a.id(), a);
      }
      // 城市 + 别名（杭州 / 杭州市）
      for (String alias : cityAliases(a.city())) {
        addToIndex(byCity, alias, a);
      }
      // 省份 + 简称（浙江省 / 浙江），同时让"浙江省"也能作为查询入口
      for (String alias : regionAliases(a.province())) {
        addToIndex(byCity, alias, a);
      }
      if (a.name() != null) {
        byName.computeIfAbsent(a.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(a);
      }
    }
    this.idIndex = Map.copyOf(byId);
    this.cityIndex = Map.copyOf(byCity);
    this.nameIndex = Map.copyOf(byName);
  }

  /** 城市别名：原名、去"市"、"加市"，如 杭州 ↔ 杭州市。 */
  private static List<String> cityAliases(String city) {
    String base = PlaceholderValues.clean(city);
    if (base == null) {
      return List.of();
    }
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    keys.add(base);
    String shortName = stripRegionSuffix(base);
    keys.add(shortName);
    if (!base.endsWith("市")) {
      keys.add(shortName + "市");
    }
    return List.copyOf(keys);
  }

  /** 省份别名：原名与去后缀简称，如 浙江省 ↔ 浙江。 */
  private static List<String> regionAliases(String province) {
    String base = PlaceholderValues.clean(province);
    if (base == null) {
      return List.of();
    }
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    keys.add(base);
    keys.add(stripRegionSuffix(base));
    return List.copyOf(keys);
  }

  /** 去行政区划后缀：浙江省→浙江、北京市→北京、广西壮族自治区→广西。 */
  private static String stripRegionSuffix(String region) {
    for (String suffix : REGION_SUFFIXES) {
      if (region.endsWith(suffix) && region.length() > suffix.length()) {
        return region.substring(0, region.length() - suffix.length());
      }
    }
    return region;
  }

  private static void addToIndex(Map<String, List<Attraction>> index, String key, Attraction a) {
    if (key == null || key.isBlank()) {
      return;
    }
    List<Attraction> list = index.computeIfAbsent(key.strip(), k -> new ArrayList<>());
    // 同一景点可能因别名重复命中，按 id 去重
    boolean exists = list.stream().anyMatch(x -> x.id() != null && x.id().equals(a.id()));
    if (!exists) {
      list.add(a);
    }
  }

  private static boolean isChineseName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    return Character.UnicodeScript.of(name.charAt(0)) == Character.UnicodeScript.HAN;
  }

  private void disable(String reason) {
    this.status = Status.disabled(reason, kbPath.toString());
    this.all = List.of();
    this.idIndex = Map.of();
    this.cityIndex = Map.of();
    this.nameIndex = Map.of();
    log.warn("景点知识库不可用，查询已停用：{}", reason);
  }

  // ---- 查询（全部只读；条数截断交给服务层） ----

  public Status status() {
    return status;
  }

  public boolean available() {
    return status.available();
  }

  /** 按城市或省份（含别名）检索，返回全部命中，已按信息丰富度排序。 */
  public List<Attraction> findByCity(String city) {
    if (city == null || city.isBlank()) {
      return List.of();
    }
    List<Attraction> hit = cityIndex.get(city.strip());
    return hit == null ? List.of() : List.copyOf(hit);
  }

  /**
   * 按名称检索，三级匹配：精确名 → 名称前缀 → 名称包含。
   *
   * <p>前缀优先于包含，避免"西湖"把"东西湖区图书馆"这类非目标结果排到前面。
   * 关键词为空时返回空列表（不返回全库）。
   */
  public List<Attraction> findByName(String keyword) {
    String kw = keyword == null ? "" : keyword.strip();
    if (kw.isEmpty()) {
      return List.of();
    }
    String lower = kw.toLowerCase(Locale.ROOT);

    List<Attraction> exact = nameIndex.get(lower);
    if (exact != null && !exact.isEmpty()) {
      return List.copyOf(exact);
    }

    List<Attraction> prefix = new ArrayList<>();
    List<Attraction> contains = new ArrayList<>();
    for (Attraction a : all) {
      if (a.name() == null) {
        continue;
      }
      String name = a.name().toLowerCase(Locale.ROOT);
      if (name.startsWith(lower)) {
        prefix.add(a);
      } else if (name.contains(lower)) {
        contains.add(a);
      }
    }
    return prefix.isEmpty() ? List.copyOf(contains) : List.copyOf(prefix);
  }

  public Optional<Attraction> findById(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(idIndex.get(id.strip()));
  }

  /** 全部城市/省份名（含数量），供客户端提示可用城市。 */
  public Map<String, Integer> knownCities() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    cityIndex.forEach((k, v) -> counts.put(k, v.size()));
    return counts;
  }

  /**
   * 加载状态快照。
   *
   * @param available 是否可用于查询
   * @param reason 不可用原因；可用时为 {@code null}
   * @param count 已加载景点数
   * @param cityCount 城市/省份索引数（含别名）
   * @param path 库文件绝对路径
   * @param loadedAt 加载时间（ISO-8601），不可用时为 {@code null}
   */
  public record Status(boolean available, String reason, int count, int cityCount,
      String path, String loadedAt) {
    public static Status disabled(String reason, String path) {
      return new Status(false, reason, 0, 0, path, null);
    }
  }
}
