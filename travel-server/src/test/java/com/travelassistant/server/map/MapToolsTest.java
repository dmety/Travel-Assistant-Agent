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

  @Test
  @DisplayName("好评高的放前面：有评分的按评分降序，无评分的排后且仍按类型次序")
  void ranksByRealRating() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        ratedPlace("黄梅县博物馆", "科教文化服务;博物馆", "黄梅县", 4.8),
        ratedPlace("五祖寺", "风景名胜;寺庙", "黄梅县", 4.5),
        place("黄梅文化广场", "黄梅县人民路", "黄冈市", "黄梅县"),
        ratedPlace("四祖寺", "风景名胜;寺庙", "黄梅县", 4.9)));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("四祖寺", "黄梅县博物馆", "五祖寺", "黄梅文化广场");
    assertThat(result.spots().get(0).rating()).isEqualTo(4.9);
    assertThat(result.spots().get(3).rating()).isNull();
  }

  @Test
  @DisplayName("跨源合并：同一实体保留带真实评分的那条 POI 原文，不拼字段")
  void keepsRatedPoiWhenMergingSources() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(true);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        place("五祖寺", "黄梅县五祖镇", "黄冈市", "黄梅县")));
    when(baidu.searchScenicSpots("黄梅", 25)).thenReturn(List.of(
        ratedPlace("五祖寺", "风景名胜;寺庙", "黄梅县", 4.6, 30.1161, 115.9341)));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).hasSize(1);
    assertThat(result.spots().get(0).source()).isEqualTo(MapTools.SOURCE_BAIDU);
    assertThat(result.spots().get(0).rating()).isEqualTo(4.6);
  }

  @Test
  @DisplayName("评分可信度：11 条评价的 5.0 不压过 53 条评价的 4.6 与 64 条评价的 4.4")
  void ranksByRatingCredibility() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        ratedPlace("芦花庵", "风景名胜;寺庙", "黄梅县", 5.0, 11),
        ratedPlace("四祖寺", "风景名胜;寺庙", "黄梅县", 4.6, 53),
        ratedPlace("五祖寺", "风景名胜;寺庙", "黄梅县", 4.4, 64)));
    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("四祖寺", "五祖寺", "芦花庵");
    assertThat(result.spots().get(0).commentCount()).isEqualTo(53);
  }

  @Test
  @DisplayName("名称命中 + 风景类类型：华阴市的西岳华山不被区县过滤漏掉，同名医院仍剔除")
  void keepsNameHitScenicPoi() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    // 西岳华山位于渭南市华阴市：区县/城市/地址都不含「华山」，靠名称+类型保留
    when(amap.searchScenicSpots("华山", null, 25)).thenReturn(List.of(
        namedPlace("华山风景名胜区", "风景名胜;风景名胜;国家级风景名胜", "华阴市", 34.48, 110.08),
        namedPlace("华山医院", "医疗保健;综合医院", "华阴市", 34.50, 110.10)));
    MapTools.ScenicResult result = mapTools.findScenicSpots("华山", 8);

    assertThat(result.spots()).extracting(MapTools.ScenicSpot::name)
        .containsExactly("华山风景名胜区");
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

  @Test
  @DisplayName("评论入口随整条 POI 原文走：跨源合并保留评分更高的那条及其官方链接")
  void reviewUrlFollowsKeptPoi() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(true);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        linkedPlace("五祖寺", 4.4, 64, "B02C900F7B", null)));
    when(baidu.searchScenicSpots("黄梅", 25)).thenReturn(List.of(
        linkedPlace("五祖寺", 4.6, 53, "35debf292fc0c444a6da3922",
            "http://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922"
                + "&output=html&source=placeapi_v3")));

    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    assertThat(result.spots()).hasSize(1);
    // 百度这条评分可信度更高，整条记录换成百度原文，链接随之更换，不拼「高德 id + 百度评分」
    assertThat(result.spots().get(0).source()).isEqualTo("百度");
    assertThat(result.spots().get(0).reviewUrl())
        .isEqualTo("https://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922"
            + "&output=html&source=placeapi_v3");
    assertThat(result.spots().get(0).reviewSource()).isEqualTo("百度地图");
  }

  @Test
  @DisplayName("高德候选按真实 POI id 给官方详情页；没有 id 就不编网址")
  void reviewUrlForAmapAndMissing() {
    when(amap.configured()).thenReturn(true);
    when(baidu.configured()).thenReturn(false);
    when(amap.searchScenicSpots("黄梅", null, 25)).thenReturn(List.of(
        linkedPlace("五祖寺", 4.4, 64, "B02C900F7B", null),
        linkedPlace("四祖寺", 4.2, 30, null, null)));

    MapTools.ScenicResult result = mapTools.findScenicSpots("黄梅", 8);

    MapTools.ScenicSpot first = result.spots().stream()
        .filter(s -> "五祖寺".equals(s.name())).findFirst().orElseThrow();
    MapTools.ScenicSpot second = result.spots().stream()
        .filter(s -> "四祖寺".equals(s.name())).findFirst().orElseThrow();
    assertThat(first.reviewUrl()).isEqualTo("https://www.amap.com/place/B02C900F7B");
    assertThat(first.reviewSource()).isEqualTo("高德地图");
    assertThat(second.reviewUrl()).isNull();
  }

  private static Place ratedPlace(String name, String type, String district, double rating) {
    return ratedPlace(name, type, district, rating, 30.116, 115.934);
  }

  /** 带评价数的 POI：评分可信度排序用。 */
  private static Place ratedPlace(String name, String type, String district,
      double rating, int comments) {
    return new Place(name, type, district, "黄冈市", district, null,
        30.116, 115.934, "GCJ-02", null, rating, comments, null, null);
  }

  private static Place ratedPlace(String name, String type, String district,
      double rating, double lat, double lon) {
    return new Place(name, type, district, "黄冈市", district, null,
        lat, lon, "GCJ-02", null, rating, null, null, null);
  }

  private static Place namedPlace(String name, String type, String district,
      double lat, double lon) {
    return new Place(name, type, district, "黄冈市", district, null,
        lat, lon, "GCJ-02", null, null, null, null, null);
  }

  /** 带官方评论入口的 POI：高德给 id，百度给 detail_url（都是 API 原文）。 */
  private static Place linkedPlace(String name, double rating, int comments,
      String poiId, String detailUrl) {
    return new Place(name, "风景名胜;寺庙", "黄梅县", "黄冈市", "黄梅县", null,
        30.116, 115.934, "GCJ-02", null, rating, comments, poiId, detailUrl);
  }
}
