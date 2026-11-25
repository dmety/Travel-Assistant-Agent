package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.map.MapTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 地图景点图层：坐标系统一到 BD-09、缺坐标跳过、空地名拒绝。 */
@DisplayName("地图景点图层 ScenicMapController")
class ScenicMapControllerTest {

  private static MapTools.ScenicSpot spot(String name, Double lat, Double lon, String crs) {
    return new MapTools.ScenicSpot(name, "风景名胜", "黄石市西塞山区", "西塞山区", null,
        lat, lon, crs, "百度", 4.5, 120, null, "百度地图");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(ResponseEntity<Object> res) {
    return (Map<String, Object>) res.getBody();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(ResponseEntity<Object> res) {
    return (List<Map<String, Object>>) body(res).get("items");
  }

  @Test
  @DisplayName("GCJ-02 坐标换算为 BD-09，并显式标注坐标系")
  void convertsToBd09() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots("黄石", 12)).thenReturn(new MapTools.ScenicResult("黄石",
        List.of(spot("西塞山风景区", 30.207, 115.107, "GCJ-02")), List.of()));

    ResponseEntity<Object> res = new ScenicMapController(maps).scenic("黄石", 12);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body(res)).containsEntry("place", "黄石").containsEntry("coordinateSystem", "BD-09");
    List<Map<String, Object>> list = items(res);
    assertThat(list).hasSize(1);
    // 百度 BD-09 相对 GCJ-02 有几百米量级偏移，值必然变化但仍应落在原地附近
    double lat = (Double) list.get(0).get("lat");
    double lon = (Double) list.get(0).get("lon");
    assertThat(lat).isBetween(30.19, 30.22);
    assertThat(lon).isBetween(115.09, 115.12);
    assertThat(list.get(0)).containsEntry("name", "西塞山风景区").containsEntry("source", "百度");
  }

  @Test
  @DisplayName("缺坐标的候选保留文字、坐标为 null，并给出跳过计数")
  void keepsTextWhenCoordinatesMissing() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots("黄石", 12)).thenReturn(new MapTools.ScenicResult("黄石",
        List.of(spot("无名景点", null, null, null)), List.of("百度超时")));

    ResponseEntity<Object> res = new ScenicMapController(maps).scenic("黄石", 12);

    Map<String, Object> item = items(res).get(0);
    assertThat(item).containsEntry("name", "无名景点").containsEntry("lat", null);
    assertThat(body(res)).containsEntry("skippedWithoutCoordinates", 1);
    assertThat((List<String>) body(res).get("notices")).containsExactly("百度超时");
  }

  @Test
  @DisplayName("坐标系标签无法识别时不猜，坐标置空")
  void unknownCoordinateSystemIsSkipped() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots("黄石", 12)).thenReturn(new MapTools.ScenicResult("黄石",
        List.of(spot("标签缺失", 30.2, 115.1, "LOCAL-GRID")), List.of()));

    ResponseEntity<Object> res = new ScenicMapController(maps).scenic("黄石", 12);

    assertThat(items(res).get(0)).containsEntry("lat", null);
    assertThat(body(res)).containsEntry("skippedWithoutCoordinates", 1);
  }

  @Test
  @DisplayName("双源同名景点只留一条，避免同处叠两个钉")
  void deduplicatesSameNameAcrossSources() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots("黄石", 12)).thenReturn(new MapTools.ScenicResult("黄石",
        List.of(spot("东方山风景区", 30.2078, 114.9498, "GCJ-02"),
            spot("东方山风景区", 30.2103, 114.9568, "GCJ-02"),
            spot("未苏湾", 30.2195, 115.0320, "GCJ-02")), List.of()));

    ResponseEntity<Object> res = new ScenicMapController(maps).scenic("黄石", 12);

    assertThat(items(res)).hasSize(2);
    assertThat(items(res)).extracting(m -> m.get("name")).containsExactly("东方山风景区", "未苏湾");
  }

  @Test
  @DisplayName("缺少地名返回 400，不去打地图配额")
  void rejectsBlankPlace() {
    MapTools maps = mock(MapTools.class);
    ResponseEntity<Object> res = new ScenicMapController(maps).scenic("  ", 12);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(res)).containsEntry("error", "请提供地名：place");
  }

  @Test
  @DisplayName("limit 被夹到 1..12，越界不放大检索量")
  void clampsLimit() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots("黄石", 12)).thenReturn(new MapTools.ScenicResult("黄石", List.of(), List.of()));
    when(maps.findScenicSpots("黄石", 1)).thenReturn(new MapTools.ScenicResult("黄石", List.of(), List.of()));

    assertThat(new ScenicMapController(maps).scenic("黄石", 99).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(new ScenicMapController(maps).scenic("黄石", 0).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
