package com.travelassistant.server.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 地图工具层的口径：区县过滤、跨源去重、降级标注——Provider 拿到的必须是
 * 干净且如实的结果，任何一处口径漂移都会变成「给用户编景点」。
 */
@DisplayName("地图工具层 MapTools")
class MapToolsTest {

  private final AmapClient amap = mock(AmapClient.class);
  private final BaiduPlaceClient baidu = mock(BaiduPlaceClient.class);
  private final MapTools mapTools = new MapTools(amap, baidu);

  @Test
  @DisplayName("区县过滤：adname/地址命中目标地名的留下，外省景点剔除")
  void filtersByDistrict() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        place("五祖寺", "黄梅县五祖镇", "黄冈市", "黄梅县"),
        place("四祖寺", "黄梅县大河镇", "黄冈市", "黄梅县"),
        // 关键字命中但不在黄梅：地址里有「黄梅」字样的店名也不该放进来——按区县判
        place("某公司", "黄梅公司大厦", "泉州市", "丰泽区")));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("五祖寺", "四祖寺");
    assertThat(result.spots().get(0).source()).isEqualTo("高德");
    assertThat(result.notices()).anyMatch(n -> n.contains("百度密钥未配置"));
  }

  @Test
  @DisplayName("跨源去重：同名且 50 米内的百度候选并进高德候选，不重复")
  void dedupesAcrossSources() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(true);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        place("五祖寺", "黄梅县五祖镇", "黄冈市", "黄梅县")));
    when(baidu.searchScenicSpots("黄梅", 25)).thenReturn(List.of(
        // 同名，坐标差约 5 米 → 同一实体
        place("五祖寺", "黄梅县五祖镇", "黄冈市", "黄梅县", 30.1161, 115.9341),
        // 同名但差约 30 公里（仍在黄梅县域内）→ 不是同一实体，保留
        place("五祖寺", "黄梅县停前镇", "黄冈市", "黄梅县", 30.42, 115.95),
        // 不同名 → 保留
        place("挪步园", "黄梅县苦竹乡", "黄冈市", "黄梅县", 30.20, 115.88)));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("五祖寺", "五祖寺", "挪步园");
    assertThat(result.spots().get(0).source()).isEqualTo("高德");
    assertThat(result.spots().get(1).source()).isEqualTo("百度");
  }

  @Test
  @DisplayName("两个源都没配置：空候选加如实 notice，不编造")
  void degradesWhenUnconfigured() {
    when(amap.configured()).thenReturn(false);
    when(baidu.configured()).thenReturn(false);
    MapTools.ScenicResult result = mapTools.findScenicSpots("婺源", 8);

    assertThat(result.spots()).isEmpty();
    assertThat(result.notices()).anyMatch(n -> n.contains("高德密钥未配置"))
        .anyMatch(n -> n.contains("未编造"));
  }

  @Test
  @DisplayName("缺地名直接返回空，不发起检索")
  void refusesBlankPlace() {
    MapTools.ScenicResult result = mapTools.findScenicSpots("  ", 8);
    assertThat(result.spots()).isEmpty();
    assertThat(result.notices()).anyMatch(n -> n.contains("缺少地名"));
  }

  @Test
  @DisplayName("上限截断：候选超过 limit 按固定顺序截取并写 notice")
  void capsResults() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    java.util.List<Place> many = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      many.add(place("景点" + i, "黄梅县某镇", "黄冈市", "黄梅县"));
    }
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(many);
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).hasSize(8);
    assertThat(result.notices()).anyMatch(n -> n.contains("截取"));
  }

  @Test
  @DisplayName("类型噪音：学校、职教培训类 POI 剔除，风景名胜排在科教文化之前")
  void filtersNoiseAndRanksScenicFirst() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        namedPlace("黄梅县第一中学", "科教文化服务;学校;中学", "黄梅县"),
        namedPlace("黄梅文化广场", "风景名胜;公园广场;城市广场", "黄梅县"),
        namedPlace("五祖寺", "风景名胜;风景名胜;风景名胜", "黄梅县"),
        namedPlace("黄梅戏艺术剧院", "科教文化服务;科教文化场所", "黄梅县")));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    // 学校被剔除；风景名胜（广场、五祖寺）排在科教文化场所之前
    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("黄梅文化广场", "五祖寺", "黄梅戏艺术剧院");
  }

  private static Place place(String name, String address, String city, String district) {
    return place(name, address, city, district, 30.116, 115.934);
  }

  private static Place place(String name, String address, String city, String district,
      double lat, double lon) {
    return namedPlace(name, "风景名胜;寺庙", district, lat, lon);
  }

  private static Place namedPlace(String name, String type, String district) {
    return namedPlace(name, type, district, 30.116, 115.934);
  }

  private static Place namedPlace(String name, String type, String district,
      double lat, double lon) {
    return new Place(name, type, district, "黄冈市", district, null,
        lat, lon, "GCJ-02", null);
  }
}
