package com.travelassistant.server.infrastructure.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 百度地图地点检索客户端，作为餐饮候选的**第二数据源**。
 *
 * <p>与高德主源的三点差异，都在这里消化掉：
 * <ul>
 *   <li>坐标是 BD-09LL，返回前统一转 GCJ-02；系数口径见 {@link CoordinateTransforms}。</li>
 *   <li>{@code scope=2} 才有 {@code detail_info} 的评分、人均、营业时段与评论数。</li>
 *   <li>本机控制台按 IP 白名单授权时不带 SN；密钥缺失或接口报错由调用方降级，
 *       不把「没接服务」说成「附近没有餐厅」。</li>
 * </ul>
 *
 * <p>AK 只出现在请求参数里，不写日志、不进异常消息；SK 不参与本客户端。
 */
@Component
public class BaiduPlaceClient {

  private static final String BASE = "https://api.map.baidu.com";
  private static final int MAX_PLACES = 20;

  private final RestTemplate restTemplate;
  private final String ak;

  /**
   * 天配额熔断（百度 {@code status=302}）：置位后到次日 00:00 本地时间前不再调用百度，
   * 既避免配额耗尽后还空打接口，也保证第二天自动恢复。
   */
  private volatile boolean dailyQuotaExhausted;
  private volatile long dailyResetAt;
  /** 并发超限冷却（百度 {@code status=401}）：默认 60 秒后重试，避免密集重试把配额打穿。 */
  private static final long CONCURRENCY_COOLDOWN_MS = 60_000L;
  private volatile long concurrencyCooldownUntil;

  @Autowired
  public BaiduPlaceClient(RestTemplateBuilder builder,
      @Value("${travel.baidu.ak:}") String ak,
      @Value("${travel.baidu.timeout-ms:4000}") long timeoutMs) {
    this(builder
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .build(), ak);
  }

  /** 供测试注入自定义 RestTemplate。 */
  public BaiduPlaceClient(RestTemplate restTemplate, String ak) {
    this.restTemplate = restTemplate;
    this.ak = ak == null ? "" : ak.strip();
  }

  public boolean configured() {
    return !ak.isEmpty();
  }

  /**
   * 配额 / 冷却是否放行：未配置、天配额耗尽（未到重置点）、并发冷却中都为 false。
   * 消费端据此决定要不要跳过百度、直接走高德主源兜底。
   */
  public boolean available() {
    return configured() && !isDailyQuotaExhausted()
        && System.currentTimeMillis() >= concurrencyCooldownUntil;
  }

  /** 天配额是否耗尽（读取时按重置点自动解除）。 */
  public boolean dailyQuotaExhausted() {
    return isDailyQuotaExhausted();
  }

  /** 是否处于并发超限短冷却中。 */
  public boolean concurrencyCooldownActive() {
    return System.currentTimeMillis() < concurrencyCooldownUntil;
  }

  /**
   * 不可用原因：null 表示可用；否则给调用方一条可直接对用户说的文案，不暴露密钥与状态码细节。
   * 与 {@link #available()} 的口径一致。
   */
  public String unavailableReason() {
    if (!configured()) {
      return "百度密钥未配置";
    }
    if (isDailyQuotaExhausted()) {
      return "百度天配额已用完，已回退高德主源";
    }
    if (concurrencyCooldownActive()) {
      return "百度并发超限，稍后重试";
    }
    return null;
  }

  /** 读取天配额熔断位，并按重置点自动解除（到次日 00:00 本地时间）。 */
  private boolean isDailyQuotaExhausted() {
    if (dailyQuotaExhausted && System.currentTimeMillis() >= dailyResetAt) {
      dailyQuotaExhausted = false;
    }
    return dailyQuotaExhausted;
  }

  /** 次日 00:00 本地时间对应的 epoch 毫秒，作为天配额熔断的重置点。 */
  private static long nextMidnightMillis() {
    return java.time.LocalDate.now(java.time.ZoneId.systemDefault()).plusDays(1)
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toEpochSecond() * 1000L;
  }

  /**
   * 检索锚点附近的餐饮 POI，返回坐标已换算为 GCJ-02 的临时快照。
   *
   * @param location 锚点，格式 {@code 纬度,经度}，且必须是 BD-09LL（百度接口口径）。
   *                 调用方先用 {@link CoordinateTransforms#gcj02ToBd09} 换算，直接传
   *                 GCJ-02 会整体偏移几百米
   */
  public List<FoodPlace> searchFoodNearby(String location, int radiusMeters, int limit) {
    if (!configured()) {
      throw new BaiduUnavailableException("未配置百度地图 AK（环境变量 BAIDU_AK），第二数据源停用");
    }
    if (isDailyQuotaExhausted()) {
      throw new BaiduUnavailableException("百度天配额已用完，已回退高德主源，本会话不再调用百度");
    }
    if (concurrencyCooldownActive()) {
      throw new BaiduUnavailableException("百度并发超限，稍后重试");
    }
    String[] parts = location == null ? new String[0] : location.split(",");
    if (parts.length != 2) {
      throw new BaiduUnavailableException("百度检索锚点格式应为「纬度,经度」");
    }
    double lat;
    double lon;
    try {
      lat = Double.parseDouble(parts[0].trim());
      lon = Double.parseDouble(parts[1].trim());
    } catch (NumberFormatException e) {
      throw new BaiduUnavailableException("百度检索锚点不是合法坐标");
    }
    int size = Math.min(Math.max(limit, 1), MAX_PLACES);
    int radius = Math.min(Math.max(radiusMeters, 100), 50000);

    URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/place/v2/search")
        .queryParam("query", "美食")
        .queryParam("location", lat + "," + lon)
        .queryParam("radius", radius)
        .queryParam("scope", 2)
        .queryParam("output", "json")
        .queryParam("page_size", size)
        .queryParam("page_num", 0)
        .queryParam("ak", ak)
        .build().encode().toUri();

    JsonNode root;
    try {
      root = restTemplate.getForObject(uri, JsonNode.class);
    } catch (RestClientException e) {
      throw new BaiduUnavailableException("百度地点检索请求失败", e);
    }
    if (root == null) {
      throw new BaiduUnavailableException("百度地点检索返回空响应");
    }
    int status = root.path("status").asInt(-1);
    if (status == 302) {
      // 天配额超限：熔断到次日 00:00，期间不再调用，消费端自动回退高德主源。
      dailyQuotaExhausted = true;
      dailyResetAt = nextMidnightMillis();
      throw new BaiduUnavailableException(
          "百度天配额已用完（status 302），已回退高德主源，次日 00:00 后自动恢复");
    }
    if (status == 401) {
      // 并发超限：短冷却后重试，避免密集重试把配额打穿。
      concurrencyCooldownUntil = System.currentTimeMillis() + CONCURRENCY_COOLDOWN_MS;
      throw new BaiduUnavailableException("百度并发超限（status 401），已进入 60 秒冷却");
    }
    if (status != 0) {
      throw new BaiduUnavailableException(
          "百度地点检索返回错误，状态码 " + status + "（" + root.path("message").asText("") + "）");
    }
    JsonNode results = root.path("results");
    if (!results.isArray()) {
      return List.of();
    }
    Instant fetchedAt = Instant.now();
    List<FoodPlace> places = new ArrayList<>();
    for (JsonNode poi : results) {
      JsonNode position = poi.path("location");
      Double bdLat = number(position.path("lat").asText(""));
      Double bdLon = number(position.path("lng").asText(""));
      Double gcjLat = null;
      Double gcjLon = null;
      if (bdLat != null && bdLon != null) {
        LatLon converted = CoordinateTransforms.bd09ToGcj02(bdLat, bdLon);
        gcjLat = converted.lat();
        gcjLon = converted.lon();
      }
      JsonNode detail = poi.path("detail_info");
      String tag = text(detail, "tag");
      Integer comments = integer(detail.path("comment_num").asText(""));
      places.add(new FoodPlace(
          text(poi, "uid"), text(poi, "name"), gcjLat, gcjLon,
          tag == null ? "" : tag,
          number(detail.path("overall_rating").asText("")),
          number(detail.path("price").asText("")),
          text(detail, "shop_hours"), comments,
          FoodPlace.SOURCE_BAIDU, fetchedAt));
    }
    return places;
  }

  // ---- 景点检索（地图工具层第二数据源） ----

  /**
   * 按区县/地名检索景点类 POI（{@code tag=景点}），坐标 BD-09LL 统一转 GCJ-02。
   *
   * <p>与餐饮检索同一套边界：{@code region} 支持区县名（百度口径比高德宽松），
   * {@code scope=2} 才带 {@code detail_info.tag}；AK 只进请求参数，不写日志。
   * 返回的 {@link Place} 供 {@code MapTools} 做跨源去重，调用方还需按区县名过滤，
   * 因为 {@code query} 命中的 POI 不一定都在目标区县内。
   *
   * @param region 区县或城市名，如「黄梅」「婺源」
   */
  public List<Place> searchScenicSpots(String region, int limit) {
    if (!configured()) {
      throw new BaiduUnavailableException("未配置百度地图 AK（环境变量 BAIDU_AK），第二数据源停用");
    }
    if (isDailyQuotaExhausted()) {
      throw new BaiduUnavailableException("百度天配额已用完，已回退高德主源，本会话不再调用百度");
    }
    if (concurrencyCooldownActive()) {
      throw new BaiduUnavailableException("百度并发超限，稍后重试");
    }
    if (region == null || region.isBlank()) {
      throw new BaiduUnavailableException("百度景点检索需要区县或城市名");
    }
    String area = region.strip();
    int size = Math.min(Math.max(limit, 1), MAX_PLACES);

    URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/place/v2/search")
        .queryParam("query", area)
        .queryParam("tag", "景点")
        .queryParam("region", area)
        .queryParam("scope", 2)
        .queryParam("output", "json")
        .queryParam("page_size", size)
        .queryParam("page_num", 0)
        .queryParam("ak", ak)
        .build().encode().toUri();

    JsonNode root;
    try {
      root = restTemplate.getForObject(uri, JsonNode.class);
    } catch (RestClientException e) {
      throw new BaiduUnavailableException("百度景点检索请求失败", e);
    }
    if (root == null) {
      throw new BaiduUnavailableException("百度景点检索返回空响应");
    }
    int status = root.path("status").asInt(-1);
    if (status == 302) {
      dailyQuotaExhausted = true;
      dailyResetAt = nextMidnightMillis();
      throw new BaiduUnavailableException(
          "百度天配额已用完（status 302），已回退高德主源，次日 00:00 后自动恢复");
    }
    if (status == 401) {
      concurrencyCooldownUntil = System.currentTimeMillis() + CONCURRENCY_COOLDOWN_MS;
      throw new BaiduUnavailableException("百度并发超限（status 401），已进入 60 秒冷却");
    }
    if (status != 0) {
      throw new BaiduUnavailableException(
          "百度景点检索返回错误，状态码 " + status + "（" + root.path("message").asText("") + "）");
    }
    JsonNode results = root.path("results");
    if (!results.isArray()) {
      return List.of();
    }
    List<Place> places = new ArrayList<>();
    for (JsonNode poi : results) {
      JsonNode position = poi.path("location");
      Double bdLat = number(position.path("lat").asText(""));
      Double bdLon = number(position.path("lng").asText(""));
      Double gcjLat = null;
      Double gcjLon = null;
      if (bdLat != null && bdLon != null) {
        LatLon converted = CoordinateTransforms.bd09ToGcj02(bdLat, bdLon);
        gcjLat = converted.lat();
        gcjLon = converted.lon();
      }
      JsonNode detail = poi.path("detail_info");
      places.add(new Place(
          text(poi, "name"),
          text(detail, "tag"),
          text(poi, "address"),
          text(poi, "city"),
          text(poi, "area"),
          null,
          gcjLat, gcjLon,
          gcjLat == null ? null : "GCJ-02",
          null,
          // scope=2 的 detail_info.overall_rating 是地图用户评分，
          // comment_num 是评价数（评分可信度，不是好评数）；price 是人均消费，不是门票价。
          number(detail.path("overall_rating").asText("")),
          integer(detail.path("comment_num").asText("")),
          // uid 与 detail_url 都是 API 原文；后者实测会 302 到 map.baidu.com/poi 的 UGC 评论页，
          // 是官方给出的评论入口。Web 服务不返回评论正文，绝不内联或编造。
          text(poi, "uid"),
          text(detail, "detail_url")));
    }
    return places;
  }

  private static String text(JsonNode parent, String field) {
    JsonNode node = parent.path(field);
    if (node.isMissingNode() || node.isNull() || node.isArray()) {
      return null;
    }
    String value = node.asText("");
    return value.isBlank() ? null : value;
  }

  private static Double number(String raw) {
    if (raw == null || raw.isBlank() || "[]".equals(raw)) {
      return null;
    }
    try {
      return Double.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer integer(String raw) {
    Double value = number(raw);
    return value == null ? null : value.intValue();
  }
}
