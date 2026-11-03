package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行程增删改查验收测试（对应需求任务 #3，P0）。
 *
 * <p>完成标准：创建"杭州三日游"；保存后能读取和修改；删除后不再出现。
 */
@DisplayName("任务 #3 行程增删改查")
class ItineraryCrudTest extends ApiTestBase {

  @Test
  @DisplayName("创建行程返回 201，版本号从 1 开始")
  void createReturnsCreatedWithVersionOne() throws Exception {
    MockHttpSession owner = registerAndLogin();

    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of(
                "title", "杭州三日游",
                "plan", java.util.Map.of("destination", "杭州", "summary", "西湖", "notes", "高铁")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.title").value("杭州三日游"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.plan.destination").value("杭州"));
  }

  @Test
  @DisplayName("创建后能在列表中查到")
  void createdItineraryAppearsInList() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    MvcResult result = mvc.perform(get("/api/itineraries").session(owner))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode list = json.readTree(result.getResponse().getContentAsString());
    boolean found = false;
    for (JsonNode item : list) {
      if (id.equals(item.get("id").asText())) {
        found = true;
      }
    }
    assertThat(found).as("列表中应包含刚创建的行程").isTrue();
  }

  @Test
  @DisplayName("创建后能按 id 读回完整字段")
  void createdItineraryCanBeReadById() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游", "杭州");

    JsonNode detail = getItinerary(id, owner);
    assertThat(detail.get("title").asText()).isEqualTo("杭州三日游");
    assertThat(detail.get("plan").get("destination").asText()).isEqualTo("杭州");
    assertThat(detail.get("owner").asText()).isNotBlank();
    assertThat(detail.get("createdAt").asText()).isNotBlank();
    assertThat(detail.get("members").isArray()).isTrue();
    assertThat(detail.get("dailySchedules").isArray()).isTrue();
    assertThat(detail.get("transports").isArray()).isTrue();
    assertThat(detail.get("lodgings").isArray()).isTrue();
    assertThat(detail.get("expenses").isArray()).isTrue();
  }

  @Test
  @DisplayName("查询不存在的行程返回 404")
  void unknownItineraryReturnsNotFound() throws Exception {
    MockHttpSession owner = registerAndLogin();

    mvc.perform(get("/api/itineraries/does-not-exist").session(owner))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("未登录时行程接口返回 401")
  void itineraryEndpointsRequireLogin() throws Exception {
    mvc.perform(get("/api/itineraries")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/itineraries").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("删除行程返回成功，随后查询 404")
  void deleteRemovesItinerary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "待删除的行程");

    mvc.perform(delete("/api/itineraries/" + id).session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));

    mvc.perform(get("/api/itineraries/" + id).session(owner))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("删除后不再出现在列表中")
  void deletedItineraryDisappearsFromList() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "待删除的行程");

    mvc.perform(delete("/api/itineraries/" + id).session(owner)).andExpect(status().isOk());

    MvcResult result = mvc.perform(get("/api/itineraries").session(owner)).andReturn();
    JsonNode list = json.readTree(result.getResponse().getContentAsString());
    for (JsonNode item : list) {
      assertThat(item.get("id").asText()).as("已删除的行程不应出现在列表").isNotEqualTo(id);
    }
  }

  @Test
  @DisplayName("只返回自己参与过的行程，别人的方案不可见")
  void listOnlyReturnsOwnItineraries() throws Exception {
    MockHttpSession alice = registerAndLogin();
    MockHttpSession bob = registerAndLogin();

    String aliceTrip = createItinerary(alice, "Alice 的行程");

    MvcResult bobList = mvc.perform(get("/api/itineraries").session(bob)).andReturn();
    JsonNode list = json.readTree(bobList.getResponse().getContentAsString());
    for (JsonNode item : list) {
      assertThat(item.get("id").asText()).as("Bob 不应看到 Alice 的行程").isNotEqualTo(aliceTrip);
    }
  }

  @Test
  @DisplayName("创建时不传 plan 也能成功，plan 字段为空对象而非报错")
  void createWithoutPlanIsTolerated() throws Exception {
    MockHttpSession owner = registerAndLogin();

    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("title", "只有标题"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.plan").exists())
        .andExpect(jsonPath("$.plan.destination").doesNotExist());
  }
}
