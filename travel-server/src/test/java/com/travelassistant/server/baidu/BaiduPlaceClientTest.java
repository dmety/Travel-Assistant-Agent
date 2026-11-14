package com.travelassistant.server.baidu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import com.travelassistant.server.infrastructure.baidu.BaiduUnavailableException;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** 百度第二源：重点验证 BD-09LL 转 GCJ-02 与 scope=2 详情字段解析。 */
@DisplayName("百度餐饮 POI 客户端")
class BaiduPlaceClientTest {

  private final RestTemplate restTemplate = new RestTemplate();
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(restTemplate).build();

  private BaiduPlaceClient client(String ak) {
    return new BaiduPlaceClient(restTemplate, ak);
  }

  @Test
  @DisplayName("BD-09LL 坐标换算成 GCJ-02，评分/人均/时段/评论数都带出")
  void convertsBd09AndParsesDetail() {
    server.expect(requestTo(containsString("/place/v2/search")))
        .andExpect(request -> {
          String query = request.getURI().getQuery();
          assertThat(query).contains("scope=2").contains("query=").contains("radius=1000");
        })
        .andRespond(withSuccess("""
            {"status":0,"message":"ok","results":[
              {"uid":"baidu-1","name":"泰丰楼","location":{"lat":39.915,"lng":116.404},
               "detail_info":{"tag":"鲁菜;老字号","overall_rating":"4.5",
                 "price":"120","shop_hours":"11:00-22:00","comment_num":"326"}},
              {"uid":"baidu-2","name":"缺详情","location":{"lat":39.916,"lng":116.405},
               "detail_info":{"overall_rating":"[]","price":"[]"}}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<FoodPlace> places = client("test-ak").searchFoodNearby("39.9087,116.3975", 1000, 10);

    assertThat(places).hasSize(2);
    FoodPlace first = places.get(0);
    assertThat(first.source()).isEqualTo(FoodPlace.SOURCE_BAIDU);
    assertThat(first.name()).isEqualTo("泰丰楼");
    assertThat(first.lat()).isCloseTo(39.908656740, Offset.offset(1e-7));
    assertThat(first.lon()).isCloseTo(116.397627291, Offset.offset(1e-7));
    assertThat(first.rating()).isEqualTo(4.5);
    assertThat(first.pricePerPersonCny()).isEqualTo(120.0);
    assertThat(first.openHours()).isEqualTo("11:00-22:00");
    assertThat(first.commentCount()).isEqualTo(326);
    assertThat(first.tags()).isEqualTo("鲁菜;老字号");
    assertThat(places.get(1).rating()).isNull();
    assertThat(places.get(1).commentCount()).isNull();
  }

  @Test
  @DisplayName("景点检索带出 overall_rating 真实评分，人均 price 不当票价、无评分保持 null")
  void parsesScenicRating() {
    server.expect(requestTo(containsString("/place/v2/search")))
        .andExpect(request -> assertThat(request.getURI().getQuery())
            .contains("scope=2").contains("tag="))
        .andRespond(withSuccess("""
            {"status":0,"message":"ok","results":[
              {"name":"五祖寺","uid":"35debf292fc0c444a6da3922",
               "location":{"lat":30.116,"lng":115.934},"city":"黄冈市",
               "area":"黄梅县","detail_info":{"tag":"风景名胜;寺庙","overall_rating":"4.6",
                 "comment_num":"53","price":"80",
                 "detail_url":"http://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922&output=html&source=placeapi_v3"}},
              {"name":"四祖寺","location":{"lat":30.152,"lng":115.912},"city":"黄冈市",
               "area":"黄梅县","detail_info":{"tag":"风景名胜","overall_rating":"[]",
                 "comment_num":"[]"}}
            ]}
            """, MediaType.APPLICATION_JSON));

    List<Place> places = client("test-ak").searchScenicSpots("黄梅", 8);

    assertThat(places).hasSize(2);
    assertThat(places.get(0).rating()).isEqualTo(4.6);
    assertThat(places.get(0).commentCount()).isEqualTo(53);
    assertThat(places.get(0).coordinateSystem()).isEqualTo("GCJ-02");
    assertThat(places.get(1).rating()).isNull();
    assertThat(places.get(1).commentCount()).isNull();
    // uid 与 detail_url 都是 API 原文：后者是官方 POI 详情/评论页，前端不按景区名猜网址
    assertThat(places.get(0).poiId()).isEqualTo("35debf292fc0c444a6da3922");
    assertThat(places.get(0).detailUrl())
        .isEqualTo("http://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922"
            + "&output=html&source=placeapi_v3");
    assertThat(places.get(1).detailUrl()).isNull();
  }

  @Test
  @DisplayName("未配置 AK、锚点非法、接口报错都明确拒绝，不返回空结果冒充无候选")
  void rejectsUnavailableStates() {
    assertThat(client("").configured()).isFalse();
    assertThatThrownBy(() -> client("").searchFoodNearby("39.9,116.4", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("BAIDU_AK");
    assertThatThrownBy(() -> client("test-ak").searchFoodNearby("bad", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("纬度,经度");
    assertThatThrownBy(() -> client("test-ak").searchFoodNearby("a,b", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class);
    server.expect(requestTo(containsString("/place/v2/search")))
        .andRespond(withSuccess("{\"status\":211,\"message\":\"APP SN校验失败\"}",
            MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client("test-ak").searchFoodNearby("39.9,116.4", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("211");
  }

  @Test
  @DisplayName("results 缺失时返回空列表，不抛异常")
  void missingResultsIsEmpty() {
    server.expect(requestTo(containsString("/place/v2/search")))
        .andRespond(withSuccess("{\"status\":0,\"message\":\"ok\"}", MediaType.APPLICATION_JSON));
    assertThat(client("test-ak").searchFoodNearby("39.9,116.4", 1000, 5)).isEmpty();
  }

  @Test
  @DisplayName("天配额耗尽（302）熔断：首次抛异常并置位，后续调用直接短路不再打百度")
  void dailyQuotaExhaustedOn302ThenShortCircuits() {
    BaiduPlaceClient client = client("test-ak");
    server.expect(requestTo(containsString("/place/v2/search")))
        .andRespond(withSuccess("{\"status\":302,\"message\":\"天配额超限\"}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.searchFoodNearby("39.9,116.4", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("配额");
    assertThat(client.dailyQuotaExhausted()).isTrue();
    // 熔断后不再发起 HTTP 请求（方法在打百度前短路），直接回退高德主源
    assertThatThrownBy(() -> client.searchFoodNearby("39.9,116.4", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("回退高德");
    assertThat(client.available()).isFalse();
  }

  @Test
  @DisplayName("并发超限（401）进入 60 秒冷却，期间 available 为 false")
  void concurrencyCooldownOn401() {
    BaiduPlaceClient client = client("test-ak");
    server.expect(requestTo(containsString("/place/v2/search")))
        .andRespond(withSuccess("{\"status\":401,\"message\":\"并发超限\"}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.searchFoodNearby("39.9,116.4", 1000, 5))
        .isInstanceOf(BaiduUnavailableException.class)
        .hasMessageContaining("并发");
    assertThat(client.concurrencyCooldownActive()).isTrue();
    assertThat(client.available()).isFalse();
  }

  @Test
  @DisplayName("正常配置且无配额问题时 available 为 true，未配置时为 false")
  void availabilityReflectsConfigAndQuota() {
    assertThat(client("test-ak").available()).isTrue();
    assertThat(client("").available()).isFalse();
  }
}
