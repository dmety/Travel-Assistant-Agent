package com.travelassistant.server.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 地图配置下发的三个分支：已配置 / 未配置 / 首尾空白。测试里只用占位值，不含真实密钥。 */
class MapConfigControllerTest {

  @Test
  void returnsConfiguredAk() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MapConfigController("placeholder-ak")).build();
    mvc.perform(get("/api/map/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("baidu"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.ak").value("placeholder-ak"));
  }

  @Test
  void staysDisabledWhenAkMissing() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MapConfigController("")).build();
    mvc.perform(get("/api/map/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.ak").value(""));
  }

  @Test
  void trimsSurroundingWhitespace() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MapConfigController("  \r\n  ")).build();
    mvc.perform(get("/api/map/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.ak").value(""));
  }

  @Test
  void toleratesNullAk() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MapConfigController(null)).build();
    mvc.perform(get("/api/map/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.ak").value(""));
  }
}
