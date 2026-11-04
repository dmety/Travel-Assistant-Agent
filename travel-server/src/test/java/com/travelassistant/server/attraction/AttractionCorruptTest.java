package com.travelassistant.server.attraction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 损坏库场景：JSON 无法解析时停用查询并说明原因，
 * 同时验证**原文件未被改写**——损坏的库必须留给人工/离线构建修复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("景点库损坏")
class AttractionCorruptTest {

  private static final Path DATA_DIR = createTempDir();
  private static final Path KB = DATA_DIR.resolve("attractions_index.json");
  private static final String BROKEN = "{\"attractions\": [ {\"id\": \"x\", 这不是合法JSON";

  static {
    try {
      Files.writeString(KB, BROKEN, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired
  private MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
    registry.add("travel.attraction-kb-path", () -> KB.toString());
  }

  @Test
  @DisplayName("解析失败时返回 503 并说明原因")
  void queryIsDisabledWithParseReason() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "杭州"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("景点查询已停用"))
        .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("解析失败")));
  }

  @Test
  @DisplayName("状态接口报告不可用")
  void statusReportsUnavailable() throws Exception {
    mvc.perform(get("/api/attractions/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("解析失败")));
  }

  @Test
  @DisplayName("损坏的原库不被覆盖或改写")
  void brokenFileIsNotOverwritten() throws IOException {
    assertThat(Files.readString(KB, StandardCharsets.UTF_8))
        .as("加载失败后不得改写库文件")
        .isEqualTo(BROKEN);
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("travel-attraction-corrupt-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
