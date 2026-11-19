package com.travelassistant.server.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 深健康检查两分支：数据目录可写 → 200/UP，不可写（目录缺失）→ 503/DOWN。 */
class HealthControllerTest {

  @Test
  void upWhenDataDirectoryWritable(@TempDir Path dir) throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(dir.toString())).build();
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.application").value("travel-assistant"));
  }

  @Test
  void downWhenDataDirectoryNotWritable(@TempDir Path dir) throws Exception {
    Path missing = dir.resolve("absent");
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(missing.toString())).build();
    mvc.perform(get("/api/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.application").value("travel-assistant"));
  }
}