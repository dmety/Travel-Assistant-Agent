package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V2ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * V2 第 2 节「Agent 工具调用与调整」。
 *
 * <p>这里验证的是**降级路径**：没有模型密钥时，仍然要给出带来源的候选清单，
 * 只是没有文字建议；而「用户确认后才保存」这条约束必须由接口形态本身保证 ——
 * 规划接口不写库。
 */
@DisplayName("V2 Agent 规划草案")
class PlanningApiTest extends V2ApiTestBase {

  @Test
  @DisplayName("未配置模型：仍返回本地候选，并说明没有文字建议")
  void planWithoutModelStillReturnsCandidates() throws Exception {
    MockHttpSession session = registerAndLogin();

    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("杭州", null, null, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(3))
        .andReturn();

    JsonNode body = read(result);
    assertThat(body.get("advice").isNull()).isTrue();
    assertThat(notices(body)).anyMatch(n -> n.contains("未配置模型密钥"));
    assertThat(notices(body)).anyMatch(n -> n.contains("未接入实时天气"));
    assertThat(sources(body)).contains("本地景点知识库");
    assertThat(body.path("generatedAt").asText()).isNotBlank();
  }

  @Test
  @DisplayName("候选都带 id 与来源，可直接被后续保存引用")
  void candidatesCarryIdAndSource() throws Exception {
    MockHttpSession session = registerAndLogin();
    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("北京", null, null, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(2))
        .andReturn();

    read(result).get("candidates").forEach(c -> {
      assertThat(c.get("id").asText()).isNotBlank();
      assertThat(c.get("name").asText()).isNotBlank();
    });
  }

  @Test
  @DisplayName("规划接口不写库：行程数量不变")
  void planDoesNotPersistAnything() throws Exception {
    MockHttpSession session = registerAndLogin();
    int before = countItineraries(session);

    mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("杭州", 2, 800, 3))))
        .andExpect(status().isOk());

    assertThat(countItineraries(session)).isEqualTo(before);
  }

  @Test
  @DisplayName("缺少目的地：400")
  void missingDestinationRejected() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("  ", null, null, null))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("目的地")));
  }

  @Test
  @DisplayName("库里没有该城市：明确说明未收录，而不是返回空就不管")
  void unknownCityGivesNotice() throws Exception {
    MockHttpSession session = registerAndLogin();
    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("火星", null, null, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(0))
        .andReturn();

    assertThat(notices(read(result))).anyMatch(n -> n.contains("未收录"));
  }

  @Test
  @DisplayName("城市查不到时用偏好关键词兜底，并提示确认目的地")
  void preferenceFallback() throws Exception {
    MockHttpSession session = registerAndLogin();
    ObjectNode body = request("火星", null, null, null);
    ArrayNode prefs = body.putArray("preferences");
    prefs.add("西湖");

    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(1))
        .andReturn();

    JsonNode response = read(result);
    assertThat(response.get("candidates").get(0).get("name").asText()).isEqualTo("西湖");
    assertThat(notices(response)).anyMatch(n -> n.contains("请确认目的地"));
  }

  @Test
  @DisplayName("缺天数/预算/人数：逐条提示，不擅自补默认值")
  void missingFieldsAreReported() throws Exception {
    MockHttpSession session = registerAndLogin();
    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request("杭州", null, null, null))))
        .andExpect(status().isOk())
        .andReturn();

    String all = String.join("|", notices(read(result)));
    assertThat(all).contains("游玩天数").contains("预算上限").contains("同行人数");
  }

  @Test
  @DisplayName("状态接口如实报告模型未配置")
  void statusReportsModelAvailability() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(get("/api/agent/status").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelConfigured").value(false))
        .andExpect(jsonPath("$.note").value(
            org.hamcrest.Matchers.containsString("DEEPSEEK_API_KEY")));
  }

  // ---- 辅助 ----

  private ObjectNode request(String destination, Integer days, Integer budget, Integer travelers) {
    ObjectNode body = json.createObjectNode();
    body.put("destination", destination);
    if (days != null) {
      body.put("days", days);
    }
    if (budget != null) {
      body.put("budget", budget);
    }
    if (travelers != null) {
      body.put("travelers", travelers);
    }
    return body;
  }

  private java.util.List<String> notices(JsonNode body) {
    java.util.List<String> list = new java.util.ArrayList<>();
    body.path("notices").forEach(n -> list.add(n.asText()));
    return list;
  }

  private java.util.List<String> sources(JsonNode body) {
    java.util.List<String> list = new java.util.ArrayList<>();
    body.path("sources").forEach(n -> list.add(n.asText()));
    return list;
  }

  private int countItineraries(MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return read(result).size();
  }
}
