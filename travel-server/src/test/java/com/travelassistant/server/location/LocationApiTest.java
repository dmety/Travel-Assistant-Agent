package com.travelassistant.server.location;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.V2ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * 高德未配置时的接口行为（V2 第 1 节）。
 *
 * <p>关键点：不能返回 200 + 空数组。那样用户会以为「这里真的没有这个景点」，
 * 而实际上只是没接服务。必须 503 + 原因。
 */
@DisplayName("V2 外部查询降级")
class LocationApiTest extends V2ApiTestBase {

  @Test
  @DisplayName("未配置密钥：地点查询返回 503 并说明原因")
  void placesReturn503WhenUnconfigured() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(get("/api/location/places").session(session).param("keywords", "西湖"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("外部信息查询不可用"))
        .andExpect(jsonPath("$.reason").value(
            org.hamcrest.Matchers.containsString("AMAP_KEY")));
  }

  @Test
  @DisplayName("未配置密钥：天气与路线同样 503")
  void weatherAndRouteAlsoUnavailable() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(get("/api/location/weather").session(session).param("city", "杭州"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/api/location/route").session(session)
            .param("origin", "120.1,30.2")
            .param("destination", "120.2,30.3"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  @DisplayName("状态接口如实说明未配置，且列出会受影响的能力")
  void statusExplainsAvailability() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(get("/api/location/status").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.reason").value(
            org.hamcrest.Matchers.containsString("不受影响")));
  }

  @Test
  @DisplayName("未登录不能调用外部查询（避免配额被匿名消耗）")
  void requiresLogin() throws Exception {
    mvc.perform(get("/api/location/places").param("keywords", "西湖"))
        .andExpect(status().isUnauthorized());
  }
}
