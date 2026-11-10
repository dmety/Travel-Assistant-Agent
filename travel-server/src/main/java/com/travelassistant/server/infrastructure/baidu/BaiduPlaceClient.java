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
          null));
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
