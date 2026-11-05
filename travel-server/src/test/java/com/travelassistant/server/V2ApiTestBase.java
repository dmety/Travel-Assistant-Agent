package com.travelassistant.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2 外部能力测试基类：景点库与购票入口都指向测试副本，
 * 并强制把模型密钥置空，避免本地环境变量让测试意外发起真实调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class V2ApiTestBase {

  protected static final String DEFAULT_PASSWORD = "Passw0rd!2026";

  /** 本次运行专属目录（{@code target/test-data} 下，退出时回收），见 {@link TestDataDirs}。 */
  protected static final Path DATA_DIR = TestDataDirs.sub("v2");
  protected static final Path KB = DATA_DIR.resolve("attractions_index.json");
  protected static final Path TICKETS = DATA_DIR.resolve("ticket_channels.json");

  private static final AtomicLong SEQ = new AtomicLong();

  static {
    copy("/attractions-test.json", KB);
    copy("/ticket_channels_test.json", TICKETS);
  }

  private static void copy(String resource, Path target) {
    try (InputStream in = V2ApiTestBase.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("测试资源未找到：" + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
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
    registry.add("travel.ticket-channel-file", () -> TICKETS.toString());
    // 强制为空：测试不应依赖（也不应触发）真实模型调用
    registry.add("travel.ai.api-key", () -> "");
    registry.add("travel.amap.key", () -> "");
  }

  // ---- 账号与行程 ----

  protected String uniqueUsername(String prefix) {
    return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
  }

  protected MockHttpSession registerAndLogin() throws Exception {
    return registerAndLogin(uniqueUsername("v2"), DEFAULT_PASSWORD);
  }

  protected MockHttpSession registerAndLogin(String username, String password) throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());
    MockHttpSession session = new MockHttpSession();
    mvc.perform(post("/api/login")
            .param("username", username)
            .param("password", password)
            .session(session))
        .andExpect(status().isOk());
    return session;
  }

  protected String createItinerary(MockHttpSession session, String title, String destination)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("title", title);
    ObjectNode plan = body.putObject("plan");
    plan.put("destination", destination);
    plan.put("summary", "");
    plan.put("notes", "");

    MvcResult result = mvc.perform(post("/api/itineraries")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .get("id").asText();
  }

  protected JsonNode read(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
