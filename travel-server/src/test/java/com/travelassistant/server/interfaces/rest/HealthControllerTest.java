package com.travelassistant.server.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 就绪检查：JSON 目录、MySQL 连接成功及连接失败/数据源缺失。 */
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
  @org.junit.jupiter.api.Test
  void mysqlHealthDoesNotRequireJsonDirectory() throws Exception {
    var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.mockito.Mockito.when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController("missing-json-directory", "mysql", java.util.Optional.of(jdbc))).build();
    mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
  }

  @org.junit.jupiter.api.Test
  void mysqlConnectionFailureIsNotReady() throws Exception {
    var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.mockito.Mockito.when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(".", "mysql", java.util.Optional.of(jdbc))).build();
    mvc.perform(get("/api/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
  }

  @org.junit.jupiter.api.Test
  void mysqlWithoutDatasourceIsNotReady() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(".", "mysql", java.util.Optional.empty())).build();
    mvc.perform(get("/api/health")).andExpect(status().isServiceUnavailable());
  }
}