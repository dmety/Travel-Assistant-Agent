package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地图景点图层：{@code GET /api/map/scenic?place=黄石&limit=12}。
 *
 * <p>存在的理由：planner 的实时地图原来只画行程草案的编号点，用户看不到
 * 「这附近有哪些景点」。对话里的 {@link MapTools#findScenicSpots} 已经有双源
 * 检索（高德 + 百度，带去重与噪音过滤），但只服务模型工具调用，页面拿不到。
 * 本接口把同一份结果整理成图层数据，前端直接画标注。
 *
 * <p>坐标系：底图是百度 BMapGL（BD-09），而高德返回 GCJ-02、百度返回 BD-09。
 * 这里统一换算成 BD-09 后下发，并显式标注 {@code coordinateSystem}；
 * 换算出坐标系的点只保留名称等文字字段、坐标为 null，由前端跳过标注——
 * 不猜坐标系，避免把景点标到几百米外的河里。
 */
@RestController
public class ScenicMapController {

  /** 与 {@link MapTools} 的硬顶一致；这里只做入口校验，真正的夹取在工具层。 */
  private static final int MAX_LIMIT = 12;

  private final MapTools maps;

  public ScenicMapController(MapTools maps) {
    this.maps = maps;
  }

  @GetMapping("/api/map/scenic")
  public ResponseEntity<Object> scenic(
      @RequestParam(required = false) String place,
      @RequestParam(defaultValue = "12") int limit) {
    String name = place == null ? "" : place.strip();
    if (name.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "请提供地名：place"));
    }
    MapTools.ScenicResult result = maps.findScenicSpots(name, Math.min(Math.max(limit, 1), MAX_LIMIT));
    // 双源同名景点坐标差常在两三百米（超过 MapTools 的 50 米去重阈值），
    // 直接下发会在同一处叠两个钉。图层按名称去重、保留排名靠前的那条。
    Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
    int withoutCoordinates = 0;
    for (MapTools.ScenicSpot spot : result.spots()) {
      String key = spot.name() == null ? "" : spot.name().strip();
      if (unique.containsKey(key)) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", spot.name());
      item.put("type", spot.type());
      item.put("address", spot.address());
      item.put("rating", spot.rating());
      item.put("commentCount", spot.commentCount());
      item.put("source", spot.source());
      CoordinateTransforms.LatLon bd09 = toBd09(spot.lat(), spot.lon(), spot.coordinateSystem());
      if (bd09 == null) {
        withoutCoordinates++;
        item.put("lat", null);
        item.put("lon", null);
      } else {
        item.put("lat", bd09.lat());
        item.put("lon", bd09.lon());
      }
      unique.put(key, item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("place", result.place());
    body.put("coordinateSystem", "BD-09");
    body.put("items", new ArrayList<>(unique.values()));
    body.put("notices", result.notices());
    if (withoutCoordinates > 0) {
      body.put("skippedWithoutCoordinates", withoutCoordinates);
    }
    return ResponseEntity.ok(body);
  }

  /** 任意来源坐标系 → BD-09；缺坐标或标签无法识别时返回 null，交由调用方跳过。 */
  private static CoordinateTransforms.LatLon toBd09(Double lat, Double lon, String coordinateSystem) {
    if (lat == null || lon == null) {
      return null;
    }
    CoordinateTransforms.LatLon gcj02 = CoordinateTransforms.toGcj02(lat, lon, coordinateSystem);
    if (gcj02 == null) {
      return null;
    }
    return CoordinateTransforms.gcj02ToBd09(gcj02.lat(), gcj02.lon());
  }
}
