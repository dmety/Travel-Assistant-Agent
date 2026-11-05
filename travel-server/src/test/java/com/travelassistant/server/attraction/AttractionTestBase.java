package com.travelassistant.server.attraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 景点查询测试基类：数据目录与景点库都指向本次运行专属目录（{@code target/test-data} 下），
 * 库内容来自 classpath 的 {@code attractions-test.json}（5 条，含占位值与缺失字段）。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AttractionTestBase {

  protected static final Path DATA_DIR = com.travelassistant.server.TestDataDirs.sub("attraction");
  protected static final Path KB = DATA_DIR.resolve("attractions_index.json");

  static {
    try (InputStream in = AttractionTestBase.class.getResourceAsStream("/attractions-test.json")) {
      if (in == null) {
        throw new IllegalStateException("测试资源 attractions-test.json 未找到");
      }
      Files.copy(in, KB, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired
  protected MockMvc mvc;

  @Autowired
  protected ObjectMapper json;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
    registry.add("travel.attraction-kb-path", () -> KB.toString());
  }

  /** 读取响应体为 JSON 树，固定 UTF-8 避免中文断言受平台编码影响。 */
  protected com.fasterxml.jackson.databind.JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
}
