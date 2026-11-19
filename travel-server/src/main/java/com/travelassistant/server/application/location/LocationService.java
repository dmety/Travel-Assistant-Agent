package com.travelassistant.server.application.location;

import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.domain.location.PlaceSearchResult;
import com.travelassistant.server.domain.location.ParkingSearchResult;
import com.travelassistant.server.domain.location.DrivingSegmentResult;
import com.travelassistant.server.domain.location.RouteMode;
import com.travelassistant.server.domain.location.RouteResult;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 外部信息查询用例（V2 第 1 节）：地点、天气、路线。
 *
 * <p>把「查询是否可用」「结果为空怎么提示」「城市名怎么变成 adcode」收敛在一处，
 * 控制器只做协议转换。所有失败都以 {@link AmapUnavailableException} 抛出，
 * 统一映射为 503 并说明原因——外部接口不可用不能表现成「没有这个地方」。
 */
@Service
public class LocationService {

  private final AmapClient amap;

  public LocationService(AmapClient amap) {
    this.amap = amap;
  }

  /** 地点检索；结果为空时给出可操作的提示，而不是干巴巴的空数组。 */
  public PlaceSearchResult searchPlaces(String keywords, String city, int limit) {
    String kw = keywords == null ? "" : keywords.strip();
    String cityKey = (city == null || city.isBlank()) ? null : city.strip();
    List<Place> places = amap.searchPlaces(kw, cityKey, limit);
    String notice = places.isEmpty()
        ? (cityKey == null
            ? "没有找到「" + kw + "」，可以换个关键词再试"
            : "在 " + cityKey + " 没有找到「" + kw + "」，可以去掉城市限制或换个关键词再试")
        : null;
    return new PlaceSearchResult(kw, cityKey, places, AmapClient.SOURCE_PLACES, Instant.now(),
        notice);
  }

  /** 天气预报；{@code city} 可以是城市名，也可以直接是 adcode。 */
  public WeatherResult weather(String city) {
    String input = city == null ? "" : city.strip();
    if (input.isEmpty()) {
      throw new AmapUnavailableException("请提供城市名称或行政区划代码");
    }
    String adcode = isAdcode(input) ? input : amap.resolveAdcode(input);
    return amap.weather(adcode, isAdcode(input) ? null : input);
  }

  /** 路线规划。 */
  public RouteResult route(RouteMode mode, String origin, String destination, String city) {
    return amap.route(mode == null ? RouteMode.WALKING : mode, origin, destination, city);
  }

  /** 周边停车场地名检索；输出保留数据源与查询时刻，未知字段不作推断。 */
  public ParkingSearchResult parking(String destination, String city) {
    return amap.searchParkingNearby(destination, city);
  }

  /** 以地点名规划一个驾车路段，并查询终点附近停车 POI。 */
  public DrivingSegmentResult drive(String origin, String destination, String city) {
    String from = origin == null ? "" : origin.strip();
    String to = destination == null ? "" : destination.strip();
    String fromCoordinates = amap.geocode(from, city);
    String toCoordinates = amap.geocode(to, city);
    RouteResult route = amap.route(RouteMode.DRIVING, fromCoordinates, toCoordinates, city);
    ParkingSearchResult parking = amap.searchParkingNearby(to, city);
    return new DrivingSegmentResult(from, to, route, parking, Instant.now());
  }

  /** 查询能力状态，供客户端在页面上提示「未配置密钥」而不是等用户点出错。 */
  public Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    boolean configured = amap.configured();
    body.put("configured", configured);
    body.put("source", configured
        ? "高德地图 Web 服务"
        : "未配置（设置环境变量 AMAP_KEY 后启用）");
    body.put("capabilities", List.of("地点检索", "天气预报", "路线规划"));
    if (!configured) {
      body.put("reason", "未配置高德 Web 服务密钥，地点、天气与路线查询已停用；"
          + "行程、景点、预算与协作功能不受影响");
    }
    return body;
  }

  private boolean isAdcode(String value) {
    return value.chars().allMatch(Character::isDigit) && value.length() >= 6;
  }
}
