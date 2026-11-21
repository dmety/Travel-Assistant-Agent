package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.domain.location.RouteMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("位置查询控制器")
class LocationControllerTest {

  private final LocationService service = mock(LocationService.class);
  private final LocationController controller = new LocationController(service);

  @Test
  @DisplayName("地图配置仅公开单独配置的浏览器 Key")
  void mapConfigHandlesMissingAndConfiguredKey() {
    ReflectionTestUtils.setField(controller, "browserMapKey", null);
    assertThat(controller.mapConfig()).containsEntry("browserKey", "").containsEntry("provider", "BAIDU");

    ReflectionTestUtils.setField(controller, "browserMapKey", "browser-key");
    assertThat(controller.mapConfig()).containsEntry("browserKey", "browser-key");
  }

  @Test
  @DisplayName("地点查询把条数限制在 1 到 25")
  void placesClampLimit() {
    controller.places("西湖", "杭州", 0);
    controller.places("西湖", "杭州", 40);

    verify(service).searchPlaces("西湖", "杭州", 1);
    verify(service).searchPlaces("西湖", "杭州", 25);
  }

  @Test
  @DisplayName("天气、停车与驾车查询委托给服务层")
  void delegatesOtherQueries() {
    controller.weather("杭州");
    controller.parking("西湖", "杭州");
    controller.drive("起点", "终点", "杭州");
    controller.status();

    verify(service).weather("杭州");
    verify(service).parking("西湖", "杭州");
    verify(service).drive("起点", "终点", "杭州");
    verify(service).status();
  }

  @Test
  @DisplayName("路线方式兼容中英文别名、大小写和未知值")
  void routeParsesModesAndDefaultsToWalking() {
    controller.route(null, "A", "B", "杭州");
    controller.route(" drive ", "A", "B", "杭州");
    controller.route("开车", "A", "B", "杭州");
    controller.route("BUS", "A", "B", "杭州");
    controller.route("地铁", "A", "B", "杭州");
    controller.route("unknown", "A", "B", "杭州");

    verify(service, org.mockito.Mockito.times(2)).route(RouteMode.DRIVING, "A", "B", "杭州");
    verify(service, org.mockito.Mockito.times(2)).route(RouteMode.TRANSIT, "A", "B", "杭州");
    verify(service, org.mockito.Mockito.times(2)).route(RouteMode.WALKING, "A", "B", "杭州");
  }
}
