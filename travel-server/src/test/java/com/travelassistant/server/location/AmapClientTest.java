package com.travelassistant.server.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.domain.location.RouteMode;
import com.travelassistant.server.domain.location.RouteResult;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 高德客户端的解析与错误映射（V2 第 1 节）。
 *
 * <p>全部用 MockRestServiceServer 拦截，不发出真实请求：外部接口的可用性不该决定
 * 测试能不能跑。重点是**错误怎么说**——密钥失效、配额用尽、参数不合法要能区分开。
 */
@DisplayName("V2 高德客户端")
class AmapClientTest {

  private final RestTemplate restTemplate = new RestTemplate();
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(restTemplate).build();

  private AmapClient client(String key) {
    return new AmapClient(restTemplate, key);
  }

  @Test
  @DisplayName("未配置密钥：明确拒绝，并说明哪些功能受影响")
  void unconfiguredIsRejected() {
    AmapClient client = client("");
    assertThat(client.configured()).isFalse();
    assertThatThrownBy(() -> client.searchPlaces("西湖", "杭州", 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("AMAP_KEY")
        .hasMessageContaining("行程、景点、预算与协作功能不受影响");
  }

  @Test
  @DisplayName("地点检索：解析名称、地址、坐标并标注坐标系")
  void parsesPlaceSearch() {
    server.expect(requestTo(containsString("/v3/place/text")))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"1","pois":[
              {"id":"B023B0","name":"西湖","type":"风景名胜","address":"龙井路1号",
               "location":"120.130000,30.259000","tel":"0571-1234","cityname":"杭州市",
               "adname":"西湖区","distance":"0"}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<Place> places = client("test-key").searchPlaces("西湖", "杭州", 5);

    assertThat(places).hasSize(1);
    Place place = places.get(0);
    assertThat(place.name()).isEqualTo("西湖");
    assertThat(place.address()).isEqualTo("龙井路1号");
    assertThat(place.lon()).isEqualTo(120.13);
    assertThat(place.lat()).isEqualTo(30.259);
    assertThat(place.coordinateSystem()).isEqualTo("GCJ-02");
  }

  @Test
  @DisplayName("景点检索改用 extensions=all：拿到地图真实评分，人均消费不冒充票价")
  void parsesScenicRating() {
    server.expect(request -> assertThat(request.getURI().toString())
            .contains("/v3/place/text").contains("extensions=all"))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"2","pois":[
              {"id":"B001","name":"五祖寺","type":"风景名胜;寺庙","address":"黄梅县五祖镇",
               "location":"115.934000,30.116000","cityname":"黄冈市","adname":"黄梅县",
               "biz_ext":{"rating":"4.7","cost":"120"}},
              {"id":"B002","name":"四祖寺","type":"风景名胜;寺庙","address":"黄梅县大河镇",
               "location":"115.912000,30.152000","cityname":"黄冈市","adname":"黄梅县",
               "biz_ext":{"rating":"[]","cost":"[]"}}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<Place> places = client("test-key").searchScenicSpots("黄梅", null, 8);

    assertThat(places).hasSize(2);
    assertThat(places.get(0).rating()).isEqualTo(4.7);
    // biz_ext.cost 是人均消费，Place 里没有票价字段，不会被误当门票展示
    assertThat(places.get(1).rating()).isNull();
    assertThat(places.get(0).commentCount()).isNull();
  }

  @Test
  @DisplayName("高德返回的错误码翻成人话，同时保留 infocode")
  void mapsErrorCodeToHumanReadable() {
    server.expect(requestTo(containsString("/v3/place/text")))
        .andRespond(withSuccess("""
            {"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}
            """, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("test-key").searchPlaces("西湖", null, 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("调用量")
        .hasMessageContaining("10003");
  }

  @Test
  @DisplayName("坐标格式不合法：直接拒绝，不把错误坐标带给高德")
  void rejectsMalformedCoordinate() {
    assertThatThrownBy(() -> client("test-key").route(RouteMode.WALKING, "abc", "120.1,30.2", null))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("经度,纬度");

    assertThatThrownBy(() -> client("test-key").route(RouteMode.WALKING, "120.1,30.2", "999,30.2", null))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("经度,纬度");
  }

  @Test
  @DisplayName("公交路线必须给城市，否则明确报错")
  void transitRequiresCity() {
    assertThatThrownBy(() -> client("test-key")
        .route(RouteMode.TRANSIT, "120.1,30.2", "120.2,30.3", null))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("城市");
  }

  @Test
  @DisplayName("路线：解析距离、耗时与主要步骤，并给出人性化文案")
  void parsesRoute() {
    server.expect(requestTo(containsString("/v3/direction/walking")))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","route":{
              "paths":[{"distance":"1600","duration":"1200","steps":[
                {"instruction":"向东步行 300 米","road":"北山街","distance":"300","duration":"240"},
                {"instruction":"到达目的地","road":"","distance":"0","duration":"0"}]}]}}
            """, MediaType.APPLICATION_JSON));

    RouteResult route = client("test-key")
        .route(RouteMode.WALKING, "120.1,30.2", "120.2,30.3", null);

    assertThat(route.distanceMeters()).isEqualTo(1600);
    assertThat(route.durationSeconds()).isEqualTo(1200);
    assertThat(route.distanceText()).isEqualTo("1.6 公里");
    assertThat(route.durationText()).isEqualTo("20 分钟");
    assertThat(route.steps()).hasSize(2);
    assertThat(route.steps().get(0).instruction()).contains("向东步行");
  }

  @Test
  @DisplayName("天气：解析逐日预报并保留发布时间")
  void parsesWeather() {
    server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"1","forecasts":[
              {"city":"杭州市","adcode":"330100","province":"浙江",
               "reporttime":"2026-11-04 18:00:00","casts":[
                 {"date":"2026-11-05","dayweather":"多云","nightweather":"晴",
                  "daytemp":"22","nighttemp":"14","daywind":"东北","daypower":"1-3",
                  "nightwind":"东北","nightpower":"1-3"}]}]}
            """, MediaType.APPLICATION_JSON));

    WeatherResult weather = client("test-key").weather("330100", "杭州");

    assertThat(weather.city()).isEqualTo("杭州");
    assertThat(weather.reportTime()).isEqualTo("2026-11-04 18:00:00");
    assertThat(weather.days()).hasSize(1);
    assertThat(weather.days().get(0).dayWeather()).isEqualTo("多云");
    assertThat(weather.days().get(0).dayTemp()).isEqualTo("22");
  }

  @Test
  @DisplayName("天气接口返回空预报：报错而不是返回空列表")
  void emptyWeatherIsAnError() {
    server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"0","forecasts":[]}
            """, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("test-key").weather("999999", "不存在"))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("行政区划代码");
  }

  @Test
  @DisplayName("地理编码解析城市名到 adcode")
  void resolvesAdcode() {
    server.expect(requestTo(containsString("/v3/geocode/geo")))
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"1","geocodes":[
              {"formatted_address":"浙江省杭州市","province":"浙江省","city":"杭州市",
               "adcode":"330100","location":"120.209947,30.245853"}]}
            """, MediaType.APPLICATION_JSON));

    assertThat(client("test-key").resolveAdcode("杭州")).isEqualTo("330100");
  }

  @Test
  @DisplayName("HTTP 5xx：说明服务不可用，而不是抛原始异常给用户")
  void handlesServerError() {
    server.expect(requestTo(containsString("/v3/place/text")))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
            .withServerError().body("bad gateway"));

    assertThatThrownBy(() -> client("test-key").searchPlaces("西湖", null, 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("请求失败");
  }

  @Test
  @DisplayName("响应里的中文按 UTF-8 解析，不出现乱码")
  void decodesUtf8() {
    server.expect(requestTo(containsString("/v3/place/text")))
        .andRespond(withSuccess("""
            {"status":"1","pois":[{"name":"雷峰塔","address":"南山路15号","location":"120.14,30.23"}]}
            """, new MediaType("application", "json", StandardCharsets.UTF_8)));

    assertThat(client("test-key").searchPlaces("雷峰塔", null, 1).get(0).name())
        .isEqualTo("雷峰塔");
  }
}
