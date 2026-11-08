package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V2ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 飞书需求表 #5「基础业务闭环」的验收用例。
 *
 * <p>覆盖六个环节：填需求 → 缺项补问 → 逐日草案（ID/来源/未知项）→ 日期与预算检查
 * → 确认载荷 → 走既有行程接口保存并读回。最后一条刻意不新增任何写库接口：
 * 载荷生成是只读的，写操作全部打到 /api/itineraries 并带 version。
 */
@DisplayName("#5 基础业务闭环")
class PlanClosureTest extends V2ApiTestBase {

  private static final String DEPART = "2026-11-20";
  private static final String RETURN = "2026-11-21";

  @Test
  @DisplayName("缺项补问：天数/人数/预算/日期各有结构化问题，并指向重试接口")
  void missingFieldsProduceStructuredQuestions() throws Exception {
    MockHttpSession session = registerAndLogin();
    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(fullRequest("杭州", 2, 1000, 2, DEPART, RETURN))))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(read(result).path("questions").size()).isZero();

    MvcResult missing = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content("{\"destination\":\"杭州\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = read(missing);
    List<String> fields = texts(body.path("questions"), "field");
    assertThat(fields).containsExactlyInAnyOrder(
        "departDate", "returnDate", "days", "travelers", "budget");
    body.path("questions").forEach(q -> {
      assertThat(q.path("question").asText()).isNotBlank();
      assertThat(q.path("hint").asText()).isNotBlank();
      assertThat(q.path("retryWith").asText()).isEqualTo("POST /api/agent/plan");
    });
    assertThat(body.path("dailySchedules").size()).isZero();
  }

  @Test
  @DisplayName("逐日草案：每天有日期，条目带景点 ID、来源与明确的未知项")
  void dailyDraftCarriesIdSourceAndUnknowns() throws Exception {
    MockHttpSession session = registerAndLogin();
    MvcResult result = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(fullRequest("杭州", 2, 1000, 2, DEPART, RETURN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dailySchedules.length()").value(2))
        .andReturn();

    JsonNode days = read(result).path("dailySchedules");
    assertThat(days.get(0).path("date").asText()).isEqualTo(DEPART);
    assertThat(days.get(1).path("date").asText()).isEqualTo(RETURN);
    assertThat(days.get(0).path("items").size()).isEqualTo(2);
    assertThat(days.get(1).path("items").size()).isEqualTo(1);

    List<String> unknowns = new ArrayList<>();
    days.forEach(day -> day.path("items").forEach(item -> {
      assertThat(item.path("attractionId").asText()).startsWith("t-hz-");
      assertThat(item.path("source").asText()).isNotBlank();
      assertThat(item.path("note").asText()).contains("attractionId=");
      item.path("unknowns").forEach(u -> unknowns.add(u.asText()));
    }));
    assertThat(unknowns).contains("票价未知");
    // 信息齐全的条目不能凭空标「未知」：西湖票价/开放时间/来源都有值。
    JsonNode westLake = days.get(0).path("items").get(0);
    assertThat(westLake.path("attractionId").asText()).isEqualTo("t-hz-001");
    assertThat(westLake.path("unknowns").size()).isZero();
  }

  @Test
  @DisplayName("日期与预算检查：倒置日期、超预算、未知票价都有明确结论")
  void dateAndBudgetChecks() throws Exception {
    MockHttpSession session = registerAndLogin();

    MvcResult inverted = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(
                fullRequest("北京", 1, 1000, 1, "2026-11-22", "2026-11-20"))))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(texts(read(inverted).path("checks"), "code"))
        .contains("RETURN_BEFORE_DEPART");

    MvcResult overBudget = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(
                fullRequest("北京", 1, 10, 1, "2026-11-20", "2026-11-20"))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode budget = read(overBudget);
    assertThat(budget.path("budgetEstimate").path("verdict").asText())
        .isEqualTo("EXCEEDS_LIMIT");
    assertThat(budget.path("budgetEstimate").path("totalForKnownTickets").asDouble())
        .isEqualTo(90.0);
    assertThat(texts(budget.path("checks"), "code"))
        .contains("BUDGET_EXCEEDS_LIMIT");

    MvcResult unknownPrices = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(
                fullRequest("杭州", 2, 1000, 2, DEPART, RETURN))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode unknown = read(unknownPrices);
    assertThat(unknown.path("budgetEstimate").path("verdict").asText())
        .isEqualTo("CANNOT_DETERMINE");
    assertThat(texts(unknown.path("checks"), "code"))
        .contains("UNKNOWN_TICKET_PRICE");
    assertThat(unknown.path("budgetEstimate").path("message").asText())
        .contains("不含交通");
  }

  @Test
  @DisplayName("确认载荷：缺日期、伪造景点 ID 都被拒绝，且不写库")
  void materializeRejectsIncompleteOrForgedDraft() throws Exception {
    MockHttpSession session = registerAndLogin();
    ObjectNode noDates = fullRequest("杭州", 2, 1000, 2, null, null);
    mvc.perform(post("/api/agent/plan/materialize")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(confirm("杭州二日游", noDates, List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("出发日期")));

    ObjectNode forged = fullRequest("杭州", 2, 1000, 2, DEPART, RETURN);
    mvc.perform(post("/api/agent/plan/materialize")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(
                confirm("杭州二日游", forged, List.of("t-fake-001")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("不在本次草案候选")));

    assertThat(countItineraries(session)).isZero();
  }

  @Test
  @DisplayName("完整闭环：生成草案 → 确认载荷 → 创建行程 → 写每日安排 → 重新读取一致")
  void fullClosurePersistsAndReadsBack() throws Exception {
    MockHttpSession session = registerAndLogin();
    ObjectNode request = fullRequest("杭州", 2, 1000, 2, DEPART, RETURN);

    MvcResult draftResult = mvc.perform(post("/api/agent/plan")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(read(draftResult).path("questions").size()).isZero();
    assertThat(countItineraries(session)).isZero(); // 生成草案不写库

    MvcResult payloadResult = mvc.perform(post("/api/agent/plan/materialize")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(confirm("杭州二日游", request, List.of()))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode payload = read(payloadResult);
    assertThat(payload.path("generatedFrom").asText()).isEqualTo("POST /api/agent/plan");
    assertThat(payload.path("plan").path("destination").asText()).isEqualTo("杭州");
    assertThat(payload.path("plan").path("departDate").asText()).isEqualTo(DEPART);
    assertThat(payload.path("plan").path("budgetLimit").asDouble()).isEqualTo(1000.0);
    assertThat(payload.path("dailySchedules").size()).isEqualTo(2);
    String firstNote = payload.path("dailySchedules").get(0)
        .path("activities").get(0).path("note").asText();
    assertThat(firstNote).contains("attractionId=t-hz-001");
    assertThat(countItineraries(session)).isZero(); // 确认载荷也不写库

    ObjectNode create = json.createObjectNode();
    create.put("title", payload.path("title").asText());
    create.set("plan", payload.path("plan"));
    MvcResult created = mvc.perform(post("/api/itineraries")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(create)))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode itinerary = read(created);
    String id = itinerary.path("id").asText();
    long version = itinerary.path("version").asLong();
    assertThat(countItineraries(session)).isEqualTo(1);

    mvc.perform(put("/api/itineraries/" + id + "/daily?version=" + version)
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(payload.path("dailySchedules"))))
        .andExpect(status().isOk());

    MvcResult reread = mvc.perform(get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode restored = read(reread);
    assertThat(restored.path("version").asLong()).isEqualTo(version + 1);
    assertThat(restored.path("dailySchedules").size()).isEqualTo(2);
    assertThat(restored.path("dailySchedules").get(0).path("date").asText()).isEqualTo(DEPART);
    assertThat(restored.path("dailySchedules").get(0).path("activities").get(0)
        .path("note").asText()).isEqualTo(firstNote);
    assertThat(restored.path("plan").path("destination").asText()).isEqualTo("杭州");
  }

  // ---- 辅助 ----

  private ObjectNode fullRequest(String destination, Integer days, Integer budget,
      Integer travelers, String depart, String ret) {
    ObjectNode body = json.createObjectNode();
    body.put("destination", destination);
    if (days != null) {
      body.put("days", days);
    }
    if (budget != null) {
      body.put("budget", budget);
    }
    body.put("currency", "CNY");
    if (travelers != null) {
      body.put("travelers", travelers);
    }
    if (depart != null) {
      body.put("departDate", depart);
    }
    if (ret != null) {
      body.put("returnDate", ret);
    }
    body.putArray("preferences");
    return body;
  }

  private ObjectNode confirm(String title, ObjectNode request, List<String> ids) {
    ObjectNode body = json.createObjectNode();
    body.put("title", title);
    body.set("request", request);
    ArrayNode array = body.putArray("attractionIds");
    ids.forEach(array::add);
    return body;
  }

  private List<String> texts(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.path(field).asText()));
    return values;
  }

  private int countItineraries(MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return read(result).size();
  }
}
