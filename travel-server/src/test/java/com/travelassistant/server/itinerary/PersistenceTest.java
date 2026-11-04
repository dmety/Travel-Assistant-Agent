package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化验收测试（需求文档 §4：服务端以本地文本文件持久化账号、邀请和方案；重启后恢复）。
 *
 * <p>「重启」在本测试里用「用同一数据目录新建一个 Store 实例重新加载」来模拟，
 * 等价于进程启动时 {@code @PostConstruct} 的行为，避免为一条用例拉起整个上下文。
 */
@DisplayName("持久化与重启恢复")
class PersistenceTest extends ApiTestBase {

  @Test
  @DisplayName("账号落盘到 accounts.json，且能以 JSON 数组形式解析")
  void accountIsWrittenToJsonFile() throws Exception {
    String username = uniqueUsername("persist");
    register(username, DEFAULT_PASSWORD);

    Path file = DATA_DIR.resolve("accounts.json");
    assertThat(Files.exists(file)).isTrue();

    JsonNode records = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
    assertThat(records.isArray()).as("账号文件应为 JSON 数组").isTrue();

    boolean found = false;
    for (JsonNode record : records) {
      if (username.equals(record.get("username").asText())) {
        found = true;
        assertThat(record.get("passwordHash").asText()).startsWith("$2a$");
        assertThat(record.get("roles").get(0).asText()).isEqualTo("ROLE_USER");
      }
    }
    assertThat(found).as("新注册的账号应已写入文件").isTrue();
  }

  @Test
  @DisplayName("行程落盘到 itineraries.json，字段与接口返回一致")
  void itineraryIsWrittenToJsonFile() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游", "杭州");

    Path file = DATA_DIR.resolve("itineraries.json");
    assertThat(Files.exists(file)).isTrue();

    JsonNode records = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
    assertThat(records.isArray()).as("行程文件应为 JSON 数组").isTrue();

    JsonNode target = null;
    for (JsonNode record : records) {
      if (id.equals(record.get("id").asText())) {
        target = record;
      }
    }
    assertThat(target).as("新建的行程应已写入文件").isNotNull();
    assertThat(target.get("title").asText()).isEqualTo("杭州三日游");
    assertThat(target.get("version").asLong()).isEqualTo(1);
    assertThat(target.get("plan").get("destination").asText()).isEqualTo("杭州");
  }

  @Test
  @DisplayName("「重启」后账号仍能被重新加载")
  void accountsSurviveRestart() throws Exception {
    String username = uniqueUsername("restart");
    register(username, DEFAULT_PASSWORD);

    // 模拟进程重启：用同一数据目录重新初始化 Store
    AccountStore reloaded = new AccountStore(json, DATA_DIR.toString());
    reloaded.init();

    assertThat(reloaded.findByUsername(username))
        .as("重启后应能按用户名找到账号")
        .isPresent();
    assertThat(reloaded.exists(username)).isTrue();
  }

  @Test
  @DisplayName("「重启」后行程仍能被重新加载，内容完整")
  void itinerariesSurviveRestart() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游", "杭州");

    // 写入一个板块，确保多字段数据也能正确往返序列化
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/itineraries/" + id + "/expenses")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(expense("交通", "580.50")))))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isOk());

    ItineraryStore reloaded = new ItineraryStore(json, DATA_DIR.toString());
    reloaded.init();

    var restored = reloaded.findById(id);
    assertThat(restored).as("重启后应能按 id 找到行程").isPresent();
    assertThat(restored.get().title()).isEqualTo("杭州三日游");
    assertThat(restored.get().version()).isEqualTo(2);
    assertThat(restored.get().expenses()).hasSize(1);
    assertThat(restored.get().expenses().get(0).category()).isEqualTo("交通");
    assertThat(restored.get().expenses().get(0).amount()).isEqualByComparingTo("580.50");
  }

  @Test
  @DisplayName("删除后文件里也不再有该行程，重启不会复活")
  void deletedItineraryDoesNotComeBackAfterRestart() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "待删除的行程");

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/itineraries/" + id).session(owner))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isOk());

    ItineraryStore reloaded = new ItineraryStore(json, DATA_DIR.toString());
    reloaded.init();

    assertThat(reloaded.findById(id)).as("已删除的行程重启后不应复活").isEmpty();
  }
}
