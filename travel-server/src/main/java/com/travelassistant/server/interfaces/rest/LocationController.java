package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.domain.location.PlaceSearchResult;
import com.travelassistant.server.domain.location.RouteMode;
import com.travelassistant.server.domain.location.RouteResult;
import com.travelassistant.server.domain.location.WeatherResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部信息查询接口（V2 第 1 节）。
 *
 * <p>需要登录：高德接口按调用量计费，对外开放会让配额被随意消耗。
 * 未配置密钥时统一返回 503 并说明原因，客户端据此提示而不是反复重试。
 */
@RestController
@RequestMapping("/api/location")
public class LocationController {

  private final LocationService service;

  public LocationController(LocationService service) {
    this.service = service;
  }

  /** 地点检索，默认 10 条，上限 25 条。 */
  @GetMapping("/places")
  public PlaceSearchResult places(@RequestParam String keywords,
      @RequestParam(required = false) String city,
      @RequestParam(defaultValue = "10") int limit) {
    return service.searchPlaces(keywords, city, Math.min(Math.max(limit, 1), 25));
  }

  /** 天气预报；{@code city} 支持城市名或 adcode。 */
  @GetMapping("/weather")
  public WeatherResult weather(@RequestParam String city) {
    return service.weather(city);
  }

  /** 路线规划；{@code origin}/{@code destination} 为「经度,纬度」。 */
  @GetMapping("/route")
  public RouteResult route(@RequestParam(defaultValue = "WALKING") String mode,
      @RequestParam String origin,
      @RequestParam String destination,
      @RequestParam(required = false) String city) {
    return service.route(parseMode(mode), origin, destination, city);
  }

  /** 查询能力状态（是否已配置密钥）。 */
  @GetMapping("/status")
  public Map<String, Object> status() {
    return service.status();
  }

  /** 宽松解析出行方式：接受 walking / WALKING / 步行。无法识别按步行处理。 */
  private RouteMode parseMode(String raw) {
    if (raw == null) {
      return RouteMode.WALKING;
    }
    return switch (raw.strip().toUpperCase()) {
      case "DRIVING", "DRIVE", "驾车", "开车" -> RouteMode.DRIVING;
      case "TRANSIT", "BUS", "公交", "地铁" -> RouteMode.TRANSIT;
      default -> RouteMode.WALKING;
    };
  }
}
