package com.travelassistant.server.application.agent;

import com.travelassistant.server.application.attraction.AttractionService;
import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.application.place.RatingOrder;
import com.travelassistant.server.application.ticket.TicketService;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.infrastructure.jumdata.JumdataScenicClient;
import com.travelassistant.server.infrastructure.jumdata.JumdataScenicSpot;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 规划可用的工具集（V2 第 2 节）。
 *
 * <p>这里是「Agent 能查到什么」的唯一入口。所有工具都是**确定性 Java 调用**，
 * 模型不直接读文件、不发外部请求，只能通过这里拿到已经校验过的结果——
 * 这保证了模型即使说错话，也编不出一个库里没有的景点 id。
 *
 * <p>每个工具都把失败转成 {@link ToolOutcome}，不向上抛异常：一次天气查询失败
 * 不该让整份草案生成失败，用户仍然需要看到候选景点。
 */
@Component
public class PlanningTools {
  private static final Logger log = LoggerFactory.getLogger(PlanningTools.class);

  /** 单次规划最多返回的候选数，防止把全库塞进结果。 */
  public static final int MAX_CANDIDATES = 30;

  private final AttractionService attractions;
  private final LocationService locations;
  private final TicketService tickets;
  private final MapTools maps;
  private final JumdataScenicClient jumdata;

  @Autowired
  public PlanningTools(AttractionService attractions, LocationService locations,
      TicketService tickets, MapTools maps, JumdataScenicClient jumdata) {
    this.attractions = attractions;
    this.locations = locations;
    this.tickets = tickets;
    this.maps = maps;
    this.jumdata = jumdata;
  }

  /** Compatibility constructor for direct callers that do not configure the external source. */
  public PlanningTools(AttractionService attractions, LocationService locations,
      TicketService tickets, MapTools maps) {
    this(attractions, locations, tickets, maps, null);
  }

  /**
   * 按城市检索候选景点。
   *
   * <p>先取足量结果再排序，而不是直接用库里出现的前几条：知识库按名称排列，
   * 直接截断会得到「世界公园、世界花卉大观园」这类字母序靠前的普通景点，
   * 把故宫、颐和园挤掉。排序口径是「等级 → 评分 → 名称」，
   * 只是把更可能值得去的地方排前面，不代表推荐结论。
   */
  public ToolOutcome<List<Attraction>> searchByCity(String city, int limit) {
    return searchByCity(city, limit, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
  }

  public ToolOutcome<List<Attraction>> searchByCity(String city, int limit, java.time.LocalDate travelDate) {
    List<Attraction> combined = new java.util.ArrayList<>();
    if (jumdata != null && jumdata.configured()) {
      try {
        String province = provinceScope(city);
        List<Attraction> live = (province == null
            ? jumdata.search(city, city, limit)
            : jumdata.searchInProvince(city, province, limit)).stream()
            .map(spot -> spot.toAttraction(travelDate)).toList();
        if (province == null) {
          String targetProvince = null;
          try {
            List<Attraction> region = attractions.byCity(city, 1).items();
            if (!region.isEmpty()) {
              targetProvince = region.get(0).province();
            }
          } catch (RuntimeException ignored) {
            // If the local index cannot identify the target province, retain API city matching.
          }
          String expectedProvince = targetProvince;
          live = live.stream().filter(a -> !addressConflictsWithCity(a, city, expectedProvince)).toList();
        }
        if (!live.isEmpty()) {
          combined.addAll(live);
        }
        log.info("聚美智数未返回城市景点，回退本地景点库：{}", city);
      } catch (RuntimeException e) {
        log.info("聚美智数景点查询不可用，回退本地景点库：{} ({})", city, e.getMessage());
      }
    }
    try {
      List<Attraction> all = attractions.byCity(city, AttractionService.MAX_LIMIT).items();
      combined.addAll(all);
      List<Attraction> ranked = rank(combined).stream().limit(limit).toList();
      return ToolOutcome.ok(ranked);
    } catch (RuntimeException e) {
      return combined.isEmpty() ? ToolOutcome.failed("景点检索不可用：" + e.getMessage())
          : ToolOutcome.ok(rank(combined).stream().limit(limit).toList());
    }
  }

  /** 按名称/关键词检索候选景点（同样排序后截断）。 */
  public ToolOutcome<List<Attraction>> searchByName(String keyword, int limit) {
    if (jumdata != null && jumdata.configured()) {
      try {
        List<Attraction> live = jumdata.search(keyword, null, limit).stream()
            .map(JumdataScenicSpot::toAttraction).toList();
        if (!live.isEmpty()) {
          return ToolOutcome.ok(rank(live).stream().limit(limit).toList());
        }
      } catch (RuntimeException e) {
        log.info("聚美智数景点关键词查询不可用，回退本地景点库：{} ({})", keyword, e.getMessage());
      }
    }
    try {
      List<Attraction> all = attractions.byName(keyword, AttractionService.MAX_LIMIT).items();
      return ToolOutcome.ok(rank(all).stream().limit(limit).toList());
    } catch (RuntimeException e) {
      return ToolOutcome.failed("景点检索不可用：" + e.getMessage());
    }
  }

  /**
   * 地名未收录时改用地图实时检索该地名本地的真实景点。
   *
   * <p>返回值转换成 {@link Attraction}：等级/票价/开放时间地图不提供，一律 null，
   * 展示层按「未知」处理，不编造；id 用 {@code map:} 前缀与知识库条目区分。
   */
  public ToolOutcome<List<Attraction>> searchLive(String place, int limit) {
    try {
      MapTools.ScenicResult result = maps.findScenicSpots(place, limit);
      List<Attraction> converted = result.spots().stream()
          .limit(limit)
          .map(s -> new Attraction("map:" + s.source() + ":" + (s.poiId() == null ? java.util.UUID.nameUUIDFromBytes((place + "|" + s.name() + "|" + s.lat() + "|" + s.lon()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString() : s.poiId()), s.name(), place, null, s.type(),
              null, s.rating()==null?null:s.rating().toString(), null, null, s.address(), s.tel(), null, null, null,
              s.lat(), s.lon(), s.source(), List.of(s.source()), null, null,
              s.coordinateSystem(),java.time.Instant.now().toString(),null,
              s.commentCount()==null?java.util.Map.of():java.util.Map.of("commentCount",List.of(new Attraction.FieldEvidence(
                  s.commentCount().toString(),s.source(),s.poiId(),java.time.Instant.now().toString(),null,"provider_returned")))))
          .toList();
      return ToolOutcome.ok(converted);
    } catch (RuntimeException e) {
      return ToolOutcome.failed("实时景点检索不可用：" + e.getMessage());
    }
  }

  /** 等级分：5A 3 分、4A 2 分、3A 1 分，其余 0 分。 */
  private int levelScore(Attraction attraction) {
    String level = attraction.level();
    if (level == null) {
      return 0;
    }
    if (level.contains("5A")) {
      return 3;
    }
    if (level.contains("4A")) {
      return 2;
    }
    if (level.contains("3A")) {
      return 1;
    }
    return 0;
  }

  /** 评分转数值，无法解析按 -1 处理（排在有效评分之后）。 */
  private double ratingValue(String rating) {
    if (rating == null || rating.isBlank()) {
      return -1;
    }
    try {
      return Double.parseDouble(rating.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private List<Attraction> rank(List<Attraction> items) {
    return items.stream()
        .filter(attraction -> !RatingOrder.isNonTourism(attraction))
        .sorted(java.util.Comparator
            .comparingInt(this::levelScore).reversed()
            .thenComparing(java.util.Comparator
                .comparingDouble((Attraction a) -> ratingValue(a.rating())).reversed())
            .thenComparing(a -> a.name() == null ? "" : a.name()))
        .toList();
  }

  private String provinceScope(String region) {
    try {
      List<Attraction> localMatches = attractions.byCity(region, 1).items();
      if (!localMatches.isEmpty()) {
        Attraction first = localMatches.get(0);
        if (sameRegion(region, first.province()) && !sameRegion(region, first.city())) {
          return first.province();
        }
      }
    } catch (RuntimeException ignored) {
      // Keep the ordinary city query path when the local index cannot classify the name.
    }
    return null;
  }

  private static boolean sameRegion(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return stripRegionSuffix(left).equals(stripRegionSuffix(right));
  }

  private static String stripRegionSuffix(String region) {
    String normalized = region.strip().replaceAll("[\\s　]+", "");
    for (String suffix : List.of("特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区",
        "自治区", "自治州", "地区", "省", "市", "县", "区")) {
      if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
        return normalized.substring(0, normalized.length() - suffix.length());
      }
    }
    return normalized;
  }

  private boolean addressConflictsWithCity(Attraction attraction, String targetCity,
      String targetProvince) {
    String address = attraction.address();
    if (address == null || address.isBlank()) {
      return false;
    }
    try {
      for (String known : attractions.knownPlaces().keySet()) {
        if (known == null || known.length() < 2 || !address.contains(known)
            || sameRegion(known, targetCity) || sameRegion(known, targetProvince)) {
          continue;
        }
        List<Attraction> matches = attractions.byCity(known, 1).items();
        if (matches.isEmpty()) {
          continue;
        }
        Attraction region = matches.get(0);
        boolean provinceAlias = sameRegion(known, region.province())
            && !sameRegion(known, region.city());
        if (provinceAlias) {
          if (targetProvince != null && !sameRegion(region.province(), targetProvince)) {
            return true;
          }
        } else {
          return true;
        }
      }
    } catch (RuntimeException e) {
      log.debug("无法校验景点地址城市范围：{}", e.getMessage());
    }
    return false;
  }

  /** 目的地天气预报。未配置高德密钥时返回失败原因，不抛异常。 */
  public ToolOutcome<WeatherResult> weather(String city) {
    try {
      return ToolOutcome.ok(locations.weather(city));
    } catch (AmapUnavailableException e) {
      return ToolOutcome.failed(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("天气查询异常：{}", e.getMessage());
      return ToolOutcome.failed("天气查询失败：" + e.getMessage());
    }
  }

  /** 景点购票辅助。 */
  public ToolOutcome<TicketAdvice> ticket(String name, String city) {
    try {
      return ToolOutcome.ok(tickets.lookup(null, name, city));
    } catch (RuntimeException e) {
      return ToolOutcome.failed("购票信息查询失败：" + e.getMessage());
    }
  }

  /** 高德是否已配置，供调用方提前决定要不要查天气。 */
  public boolean locationEnabled() {
    return Boolean.TRUE.equals(locations.status().get("configured"));
  }
}
