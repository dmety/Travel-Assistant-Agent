package com.travelassistant.server.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** 周边餐饮检索：只验证参数口径与字段解析，不发真实请求。 */
@DisplayName("高德周边餐饮检索")
class AmapFoodClientTest {

  private final RestTemplate restTemplate = new RestTemplate();
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(restTemplate).build();

  private AmapClient client(String key) {
    return new AmapClient(restTemplate, key);
  }

  @Test
  @DisplayName("用 around + 餐饮类型码 + extensions=all，解析评分与人均")
  void parsesFoodPoisWithBusinessFields() {
    server.expect(requestTo(containsString("/v3/place/around")))
        .andExpect(request -> {
          String query = request.getURI().getQuery();
          assertThat(query).contains("types=050000").contains("extensions=all")
              .contains("sortrule=distance").contains("radius=1000");
        })
        .andRespond(withSuccess("""
            {"status":"1","info":"OK","infocode":"10000","count":"2","pois":[
              {"id":"B0FF1","name":"知味观","type":"餐饮服务;中餐厅",
               "location":"120.134000,30.256000","tag":"杭帮菜;老字号",
               "biz_ext":{"rating":"4.7","cost":"68.00","opentime2":"10:00-21:00"}},
              {"id":"B0FF2","name":"无名小店","location":"120.135000,30.257000",
               "biz_ext":{"rating":"[]","cost":"[]"}}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<FoodPlace> places = client("test-key").searchFoodNearby("120.13,30.259", 1000, 10);

    assertThat(places).hasSize(2);
    FoodPlace first = places.get(0);
    assertThat(first.poiId()).isEqualTo("B0FF1");
    assertThat(first.lat()).isEqualTo(30.256);
    assertThat(first.lon()).isEqualTo(120.134);
    assertThat(first.rating()).isEqualTo(4.7);
    assertThat(first.pricePerPersonCny()).isEqualTo(68.0);
    assertThat(first.openHours()).isEqualTo("10:00-21:00");
    assertThat(first.tags()).contains("杭帮菜");
    assertThat(first.source()).isEqualTo(FoodPlace.SOURCE_AMAP);
    assertThat(first.usable()).isTrue();
    // 空数组不是 0 分/0 元，必须保持「未知」。
    assertThat(places.get(1).rating()).isNull();
    assertThat(places.get(1).pricePerPersonCny()).isNull();
  }

  @Test
  @DisplayName("未配置密钥或锚点非法时明确拒绝")
  void rejectsMissingKeyAndBadAnchor() {
    assertThatThrownBy(() -> client("").searchFoodNearby("120.13,30.259", 1000, 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("AMAP_KEY");
    assertThatThrownBy(() -> client("test-key").searchFoodNearby("bad", 1000, 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("经度,纬度");
  }

  @Test
  @DisplayName("配额耗尽时保留 infocode，不解释为查无结果")
  void mapsQuotaError() {
    server.expect(requestTo(containsString("/v3/place/around")))
        .andRespond(withSuccess("""
            {"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}
            """, MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client("test-key").searchFoodNearby("120.13,30.259", 1000, 5))
        .isInstanceOf(AmapUnavailableException.class)
        .hasMessageContaining("调用量");
  }
}
