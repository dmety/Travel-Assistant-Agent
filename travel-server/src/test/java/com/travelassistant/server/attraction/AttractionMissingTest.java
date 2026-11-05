package com.travelassistant.server.attraction;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * 缺库场景：景点库文件不存在时，查询必须停用并说明原因，
 * 且**不得顺手创建一个空库**（不覆盖/不伪造原库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("景点库缺失")
class AttractionMissingTest {

  private static final Path DATA_DIR = createTempDir();
  private static final Path KB = DATA_DIR.resolve("attractions_index.json");

  @Autowired
  private MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
    registry.add("travel.attraction-kb-path", () -> KB.toString());
  }

  @Test
  @DisplayName("查询返回 503 并说明“文件不存在”，而非空结果")
  void queryIsDisabledWithReason() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "杭州"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("景点查询已停用"))
        .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("不存在")));
  }

  @Test
  @DisplayName("状态接口报告不可用及原因")
  void statusReportsUnavailable() throws Exception {
    mvc.perform(get("/api/attractions/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.reason").isNotEmpty())
        .andExpect(jsonPath("$.count").doesNotExist());
  }

  @Test
  @DisplayName("按 ID 查询同样被停用（503），不是 404")
  void detailIsDisabled() throws Exception {
    mvc.perform(get("/api/attractions/any-id"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  @DisplayName("不自动创建库文件，原路径保持不存在")
  void doesNotCreateFile() {
    assertThat(Files.exists(KB)).as("缺库时不得自动生成文件").isFalse();
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("travel-attraction-missing-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
