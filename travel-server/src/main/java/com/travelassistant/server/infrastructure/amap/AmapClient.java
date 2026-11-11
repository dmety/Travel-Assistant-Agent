package com.travelassistant.server.infrastructure.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.domain.location.RouteMode;
import com.travelassistant.server.domain.location.RouteResult;
import com.travelassistant.server.domain.location.WeatherResult;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 高德地图 Web 服务客户端（V2 第 1 节）。
 * 三条链路：地点检索、天气预报、路线规划。设计上刻意做了三件事：
 *
 *   未配置密钥就整体停用，返回 503 并说明原因。不能假装「没有这个地方」——
 *       那会把「没接服务」变成「现实里不存在」。</li>
 *   <li><b>结果带来源和查询时间</b>。预报和路线预估都有时效，用户需要知道数据是哪一刻的。</li>
 *   <li><b>错误码转成人话</b>。高德的 {@code infocode} 直接抛给用户没有意义，
 *       但对开发者要保留原始值，便于排查是配额、权限还是参数问题。</li>
 * </ul>
 *
 * <p>密钥只出现在请求参数里，不写日志、不进异常消息。
 */
@Component
public class AmapClient {
  private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

  /** 数据来源标识，随结果一起返回。 */
  public static final String SOURCE_PLACES = "高德地图 Web 服务 · 地点检索";
  public static final String SOURCE_WEATHER = "高德地图 Web 服务 · 天气预报";
  public static final String SOURCE_ROUTE = "高德地图 Web 服务 · 路线规划";

  private static final String BASE = "https://restapi.amap.com";
  /** 单次地点检索最多返回条数（高德 offset 上限）。 */
  private static final int MAX_PLACES = 25;
  /** 路线结果最多保留的步骤数，避免把整段导航播报塞给用户。 */
  private static final int MAX_STEPS = 8;

  private final RestTemplate restTemplate;
  private final String key;

  @Autowired
  public AmapClient(RestTemplateBuilder builder,
      @Value("${travel.amap.key:}") String key,
      @Value("${travel.amap.timeout-ms:4000}") long timeoutMs) {
    this(builder
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .build(), key);
  }

  /** 供测试注入自定义 RestTemplate（以便挂 MockRestServiceServer）。 */
  public AmapClient(RestTemplate restTemplate, String key) {
    this.restTemplate = restTemplate;
    this.key = key == null ? "" : key.strip();
  }

  /** 是否已配置密钥。未配置时所有查询方法都会抛 {@link AmapUnavailableException}。 */
  public boolean configured() {
    return !key.isEmpty();
  }

  // ---- 地点检索 ----

  /** 景点与文博馆的大类码：风景名胜 110000 + 科学文化服务 140000（博物馆、纪念馆等）。 */
  public static final String SCENIC_TYPES = "110000|140000";

  /**
   * 按关键词检索地点（不限类别）。
   *
   * @param keywords 关键词，如「西湖」
   * @param city     限定城市名或 adcode，可为空
   * @param limit    期望条数，会被夹到 1..25
   */
  public List<Place> searchPlaces(String keywords, String city, int limit) {
    return search(keywords, city, null, limit);
  }

  /**
   * 按关键词检索景点类 POI（风景名胜 + 科学文化服务），坐标 GCJ-02。
   *
   * <p>类别码过滤是刻意为之：不限类别的关键字检索会混进学校、公司、小区，
   * 灵感推荐里出现过「评分 5.0 的中学」。注意区县名不要当 {@code city} 参数传——
   * 高德该参数按市级口径解析，区县命中不稳定；由 {@code MapTools} 在结果里
   * 按 {@code adname}/地址过滤。
   *
   * 用 {@code extensions=all} 才能拿到 {@code biz_ext.rating} 地图用户评分——
   * 写死 {@code base} 的旧版本让「好评高的放前面」无从下手，也没有把评分编出来的余地。
   * 注意 {@code biz_ext.cost} 是人均消费，<b>不是门票价</b>，绝不拿来当票价展示。
   *
   * @param keywords 关键词，如「黄梅」「五祖寺」
   * @param city     限定城市名或 adcode，可为空
   * @param limit    期望条数，会被夹到 1..25
   */
  public List<Place> searchScenicSpots(String keywords, String city, int limit) {
    return search(keywords, city, SCENIC_TYPES, limit);
  }

  /** {@code searchPlaces} 与 {@code searchScenicSpots} 的公共实现；{@code types} 为空不限类别。 */
  private List<Place> search(String keywords, String city, String types, int limit) {
    ensureConfigured();
    String kw = requireText(keywords, "请提供查询关键词");
    int size = Math.min(Math.max(limit, 1), MAX_PLACES);

    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(BASE + "/v3/place/text")
        .queryParam("key", key)
        .queryParam("keywords", kw)
        .queryParam("offset", size)
        .queryParam("page", 1)
        .queryParam("extensions", "all");
    if (types != null && !types.isBlank()) {
      builder.queryParam("types", types);
    }
    if (city != null && !city.isBlank()) {
      builder.queryParam("city", city.strip()).queryParam("citylimit", "true");
    }

    JsonNode root = get(builder.build().encode().toUri(), "地点检索");
    List<Place> places = new ArrayList<>();
    for (JsonNode poi : array(root, "pois")) {
      Double[] lngLat = parseLngLat(poi.path("location").asText(""));
      JsonNode biz = poi.path("biz_ext");
      places.add(new Place(
          text(poi, "name"),
          text(poi, "type"),
          text(poi, "address"),
          text(poi, "cityname"),
          text(poi, "adname"),
          text(poi, "tel"),
          lngLat == null ? null : lngLat[1],
          lngLat == null ? null : lngLat[0],
          "GCJ-02",
          text(poi, "distance"),
          number(biz.path("rating").asText("")),
          // place/text 的 biz_ext 不提供评价数；不拿 cost 或其它字段凑数
          null,
          // POI id 是 API 原文，用于跳高德官方详情页看真实评论；featured_reviews 实测为空，
          // Web 服务不提供评论正文，所以这里只存链接入口，不存也不编评论文本。
          text(poi, "id"),
          null));
    }
    log.debug("高德地点检索「{}」命中 {} 条", kw, places.size());
    return places;
  }

  // ---- 周边餐饮检索 ----

  /**
   * 按锚点检索附近餐饮 POI，坐标一律 GCJ-02。
   *
   * <p>与 {@link #searchPlaces} 的区别：走 {@code /v3/place/around}、餐饮大类
   * {@code types=050000}、{@code extensions=all}。只有 {@code all} 才带
   * {@code biz_ext.rating} 与 {@code biz_ext.cost}，写死 {@code base} 拿不到评分与人均。
   * 单次不翻页，避免配额翻倍；返回的 POI 只用于本次响应，不落库。
   */
  public List<FoodPlace> searchFoodNearby(String location, int radiusMeters, int limit) {
    ensureConfigured();
    String center = requireLngLat(location, "锚点坐标格式应为「经度,纬度」");
    int size = Math.min(Math.max(limit, 1), MAX_PLACES);
    int radius = Math.min(Math.max(radiusMeters, 100), 50000);

    URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/v3/place/around")
        .queryParam("key", key)
        .queryParam("location", center)
        .queryParam("types", "050000")
        .queryParam("radius", radius)
        .queryParam("sortrule", "distance")
        .queryParam("extensions", "all")
        .queryParam("offset", size)
        .queryParam("page", 1)
        .build().encode().toUri();

    JsonNode root = get(uri, "周边餐饮检索");
    Instant fetchedAt = Instant.now();
    List<FoodPlace> places = new ArrayList<>();
    for (JsonNode poi : array(root, "pois")) {
      Double[] lngLat = parseLngLat(poi.path("location").asText(""));
      JsonNode biz = poi.path("biz_ext");
      String hours = text(poi, "opentime2");
      if (hours == null) {
        hours = text(biz, "opentime2");
      }
      places.add(new FoodPlace(
          text(poi, "id"), text(poi, "name"),
          lngLat == null ? null : lngLat[1], lngLat == null ? null : lngLat[0],
          joinTags(text(poi, "type"), text(poi, "tag")),
          number(biz.path("rating").asText("")),
          number(biz.path("cost").asText("")),
          hours, null, FoodPlace.SOURCE_AMAP, fetchedAt));
    }
    return places;
  }

  /** 类别与标签合并成一个纯文本线索，供忌口关键词判定与菜系标签使用。 */
  private static String joinTags(String type, String tag) {
    if (type == null && tag == null) {
      return "";
    }
    if (tag == null) {
      return type;
    }
    if (type == null) {
      return tag;
    }
    return type + ";" + tag;
  }

  /** 空串、空数组、非数值一律转 null，不把缺失值写成 0。 */
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

  public Instant now() {
    return Instant.now();
  }

  // ---- 天气 ----

  /**
   * 把城市名解析成行政区划代码（adcode）。
   *
   * <p>高德天气接口只认 adcode，传「杭州」会返回空数组。这里走地理编码接口解析，
   * 解析不到就明确报错，而不是拿一个猜的代码去查天气。
   */
  public String resolveAdcode(String city) {
    ensureConfigured();
    String name = requireText(city, "请提供城市名称");

    URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/v3/geocode/geo")
        .queryParam("key", key)
        .queryParam("address", name)
        .build().encode().toUri();

    JsonNode root = get(uri, "地理编码");
    List<JsonNode> geocodes = array(root, "geocodes");
    if (geocodes.isEmpty()) {
      throw new AmapUnavailableException("高德地理编码未能识别城市「" + name + "」，请换用完整城市名");
    }
    String adcode = geocodes.get(0).path("adcode").asText("");
    if (adcode.isBlank()) {
      throw new AmapUnavailableException("城市「" + name + "」没有返回行政区划代码");
    }
    return adcode;
  }

  /**
   * 查询指定城市（adcode）的天气预报。
   *
   * <p>高德天气接口按 adcode 查询，传城市名会返回空结果；调用方需先解析 adcode。
   */
  public WeatherResult weather(String adcode, String cityName) {
    ensureConfigured();
    String code = requireText(adcode, "请提供行政区划代码（adcode）");

    URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/v3/weather/weatherInfo")
        .queryParam("key", key)
        .queryParam("city", code)
        .queryParam("extensions", "all")
        .build().encode().toUri();

    JsonNode root = get(uri, "天气预报");
    List<JsonNode> forecasts = array(root, "forecasts");
    if (forecasts.isEmpty()) {
      throw new AmapUnavailableException(
          "高德天气接口没有返回该城市的预报，请确认行政区划代码是否正确：" + code);
    }
    JsonNode forecast = forecasts.get(0);
    List<WeatherResult.DayWeather> days = new ArrayList<>();
    for (JsonNode cast : array(forecast, "casts")) {
      days.add(new WeatherResult.DayWeather(
          text(cast, "date"),
          text(cast, "dayweather"),
          text(cast, "nightweather"),
          text(cast, "daytemp"),
          text(cast, "nighttemp"),
          text(cast, "daywind"),
          text(cast, "daypower"),
          text(cast, "nightwind"),
          text(cast, "nightpower")));
    }
    String name = cityName != null && !cityName.isBlank()
        ? cityName.strip()
        : text(forecast, "city");
    return new WeatherResult(name, code, text(forecast, "reporttime"), days, SOURCE_WEATHER,
        Instant.now());
  }

  // ---- 路线 ----

  /**
   * 规划两点之间的路线。
   *
   * @param origin      起点，格式 {@code 经度,纬度}（GCJ-02）
   * @param destination 终点，格式同上
   * @param mode        出行方式
   * @param city        公交规划必填的城市名或 adcode
   */
  public RouteResult route(RouteMode mode, String origin, String destination, String city) {
    ensureConfigured();
    String from = requireLngLat(origin, "起点坐标格式应为「经度,纬度」");
    String to = requireLngLat(destination, "终点坐标格式应为「经度,纬度」");

    String path = switch (mode) {
      case WALKING -> "/v3/direction/walking";
      case DRIVING -> "/v3/direction/driving";
      case TRANSIT -> "/v3/direction/transit/integrated";
    };
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(BASE + path)
        .queryParam("key", key)
        .queryParam("origin", from)
        .queryParam("destination", to)
        .queryParam("extensions", "base");
    if (mode == RouteMode.TRANSIT) {
      String cityCode = requireText(city, "公交路线需要提供城市名或行政区划代码");
      builder.queryParam("city", cityCode);
      builder.queryParam("cityd", cityCode);
    }

    JsonNode root = get(builder.build().encode().toUri(), "路线规划");
    JsonNode route = root.path("route");
    JsonNode paths = route.path("paths");
    if (!paths.isArray() || paths.isEmpty()) {
      throw new AmapUnavailableException(
          "高德路线规划没有返回可用方案（" + mode.label() + "），可能是两点之间不支持该方式");
    }
    JsonNode first = paths.get(0);
    int distance = first.path("distance").asInt(0);
    int duration = first.path("duration").asInt(0);

    List<RouteResult.Step> steps = new ArrayList<>();
    for (JsonNode step : array(first, "steps")) {
      if (steps.size() >= MAX_STEPS) {
        break;
      }
      steps.add(new RouteResult.Step(
          text(step, "instruction"),
          text(step, "road"),
          step.path("distance").asInt(0),
          step.path("duration").asInt(0)));
    }

    return new RouteResult(mode, from, to, distance, duration,
        humanDistance(distance), humanDuration(duration), steps, SOURCE_ROUTE, Instant.now());
  }

  // ---- 内部 ----

  private void ensureConfigured() {
    if (!configured()) {
      throw new AmapUnavailableException(
          "未配置高德 Web 服务密钥（环境变量 AMAP_KEY），地点、天气与路线查询已停用；"
              + "行程、景点、预算与协作功能不受影响");
    }
  }

  /** 发起 GET 并做统一的错误码翻译。 */
  private JsonNode get(URI uri, String what) {
    JsonNode root;
    try {
      root = restTemplate.getForObject(uri, JsonNode.class);
    } catch (RestClientException e) {
      throw new AmapUnavailableException(
          "高德" + what + "请求失败：" + brief(e.getMessage()), e);
    }
    if (root == null) {
      throw new AmapUnavailableException("高德" + what + "返回空响应");
    }
    String status = root.path("status").asText("");
    if (!"1".equals(status)) {
      String infocode = root.path("infocode").asText("");
      String info = root.path("info").asText("");
      throw new AmapUnavailableException(
          "高德" + what + "返回错误：" + explain(infocode, info) + "（infocode " + infocode + "）");
    }
    return root;
  }

  /** 把常见 infocode 翻成可操作的中文；未覆盖的保留原始 info。 */
  private String explain(String infocode, String info) {
    return switch (infocode) {
      case "10001", "10009" -> "密钥无效或与绑定平台不符，请检查 AMAP_KEY";
      case "10002", "10010", "10016", "10019", "10041" -> "当前密钥没有该接口权限，请在高德控制台开通";
      case "10003", "10012", "10017", "10044" -> "当日调用量已达上限，请稍后重试或调整配额";
      case "10004" -> "请求过于频繁，请降低调用频率后重试";
      case "10005", "10006", "10029" -> "IP 或域名不在白名单内，请检查高德控制台的限制设置";
      case "10013", "10014", "10015" -> "该服务未开通或已过期";
      case "20000", "20001", "20002" -> "请求参数不合法，请检查关键词、坐标与城市";
      default -> info == null || info.isBlank() ? "未知错误" : info;
    };
  }

  private List<JsonNode> array(JsonNode parent, String field) {
    JsonNode node = parent.path(field);
    if (!node.isArray()) {
      return List.of();
    }
    List<JsonNode> list = new ArrayList<>();
    node.forEach(list::add);
    return list;
  }

  /** 高德在某些情况下把字段返回成空数组（如无电话时 {@code []}），统一转成 null。 */
  private String text(JsonNode parent, String field) {
    JsonNode node = parent.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return node.isEmpty() ? null : node.get(0).asText(null);
    }
    String value = node.asText("");
    return value.isBlank() ? null : value;
  }

  /** 解析「经度,纬度」，非法返回 null（不猜坐标）。 */
  private Double[] parseLngLat(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String[] parts = raw.split(",");
    if (parts.length != 2) {
      return null;
    }
    try {
      double lng = Double.parseDouble(parts[0].trim());
      double lat = Double.parseDouble(parts[1].trim());
      if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
        return null;
      }
      return new Double[] {lng, lat};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new AmapUnavailableException(message);
    }
    return value.strip();
  }

  private String requireLngLat(String value, String message) {
    if (parseLngLat(value) == null) {
      throw new AmapUnavailableException(message + "，实际为「" + value + "」");
    }
    return value.strip();
  }

  private String humanDistance(int meters) {
    if (meters < 1000) {
      return meters + " 米";
    }
    return String.format("%.1f 公里", meters / 1000.0);
  }

  private String humanDuration(int seconds) {
    if (seconds < 60) {
      return seconds + " 秒";
    }
    int minutes = seconds / 60;
    if (minutes < 60) {
      return minutes + " 分钟";
    }
    return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
  }

  private String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 160 ? message.substring(0, 160) + "…" : message;
  }
}
