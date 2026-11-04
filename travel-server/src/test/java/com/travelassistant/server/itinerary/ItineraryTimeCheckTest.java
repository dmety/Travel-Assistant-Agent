package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * V2 第 3 节「详细时间检查」。
 *
 * <p>覆盖三类判断：写入时拦截倒置（硬错误），体检时提示重叠（需用户判断），
 * 以及历史数据没有起止时间时的提示口径（只提示一次，避免逐条刷屏）。
 */
@DisplayName("V2-3 详细时间检查")
class ItineraryTimeCheckTest extends ApiTestBase {

  // ---- 用例 ----

  @Test
  @DisplayName("结束时间早于开始时间：写入即拒绝，版本号不变")
  void rejectsInvertedActivityTime() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "时间倒置");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");
    long before = currentVersion(id, session);

    ArrayNode daily = arrayOf(day("2026-11-10", activity("看日出", "09:00", "08:00", "西湖")));

    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(session)
            .param("version", Long.toString(before))
            .contentType("application/json")
            .content(json.writeValueAsString(daily)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("早于开始时间")));

    assertThat(currentVersion(id, session)).isEqualTo(before);
  }

  @Test
  @DisplayName("同一天两项时间重叠：体检给出提示，写入本身成功")
  void reportsOverlappingActivities() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "时间重叠");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    ArrayNode daily = arrayOf(day("2026-11-10",
        activity("游船", "09:00", "12:00", "西湖"),
        activity("午饭", "11:30", "13:00", "楼外楼")));
    putDaily(id, session, daily);

    JsonNode checks = checks(id, session);
    assertThat(checks.get("ok").asBoolean()).isFalse();
    assertThat(codes(checks)).contains("ACTIVITY_TIME_OVERLAP");
  }

  @Test
  @DisplayName("首尾相接不算重叠：12:00 结束、12:00 开始")
  void backToBackIsNotOverlap() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "首尾相接");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    ArrayNode daily = arrayOf(day("2026-11-10",
        activity("上午", "09:00", "12:00", "西湖"),
        activity("下午", "12:00", "15:00", "灵隐寺")));
    putDaily(id, session, daily);

    assertThat(codes(checks(id, session))).doesNotContain("ACTIVITY_TIME_OVERLAP");
  }

  @Test
  @DisplayName("三项互相重叠只报必要的提示，不展开成 n² 条")
  void overlapWarningsDoNotExplode() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "多项重叠");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    ArrayNode daily = arrayOf(day("2026-11-10",
        activity("A", "09:00", "18:00", "西湖"),
        activity("B", "10:00", "11:00", "灵隐寺"),
        activity("C", "12:00", "13:00", "千岛湖")));
    putDaily(id, session, daily);

    JsonNode checks = checks(id, session);
    long overlap = count(checks, "ACTIVITY_TIME_OVERLAP");
    // A 与 B、A 与 C 重叠，B 与 C 不重叠：最多两条，且不会因三项组合而膨胀
    assertThat(overlap).isBetween(1L, 2L);
  }

  @Test
  @DisplayName("整天都没填起止时间：只提示一次，不逐条刷屏")
  void missingTimesReportOncePerDay() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "缺时间");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    ObjectNode a1 = json.createObjectNode();
    a1.put("title", "甲");
    ObjectNode a2 = json.createObjectNode();
    a2.put("title", "乙");
    putDaily(id, session, arrayOf(day("2026-11-10", a1, a2)));

    JsonNode checks = checks(id, session);
    assertThat(codes(checks)).contains("ACTIVITY_TIME_MISSING");
    assertThat(count(checks, "ACTIVITY_TIME_MISSING")).isEqualTo(1);
  }

  @Test
  @DisplayName("有自由文本时间（如「上午」）时不判为缺失")
  void freeTextTimeCountsAsProvided() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "自由文本时间");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    ObjectNode a = json.createObjectNode();
    a.put("time", "上午");
    a.put("title", "逛西湖");
    putDaily(id, session, arrayOf(day("2026-11-10", a)));

    assertThat(codes(checks(id, session))).doesNotContain("ACTIVITY_TIME_MISSING");
  }

  @Test
  @DisplayName("起止时间能落盘并读回，格式为 ISO 本地时间")
  void persistsActivityTime() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "时间落盘");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    putDaily(id, session, arrayOf(day("2026-11-10", activity("游船", "09:30", "11:00", "西湖"))));

    MvcResult result = mvc.perform(get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode activity = json.readTree(result.getResponse()
            .getContentAsString(StandardCharsets.UTF_8))
        .path("dailySchedules").path(0).path("activities").path(0);

    assertThat(activity.get("startTime").asText()).startsWith("09:30");
    assertThat(activity.get("endTime").asText()).startsWith("11:00");
  }

  @Test
  @DisplayName("历史数据只有自由文本时间：仍可正常写入与读取")
  void legacyActivityWithoutTimeRangeStillWorks() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "历史兼容");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    putDaily(id, session, arrayOf(dailySchedule("2026-11-10", "老格式安排")));

    JsonNode activity = getItinerary(id, session)
        .path("dailySchedules").path(0).path("activities").path(0);
    assertThat(activity.get("title").asText()).isEqualTo("老格式安排");
    assertThat(activity.path("startTime").isNull()).isTrue();
  }

  @Test
  @DisplayName("安排日期超出旅行范围：体检给出越界提示")
  void activityOutsideTripRange() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "越界安排");
    putPlanDates(id, session, "2026-11-10", "2026-11-12");

    putDaily(id, session, arrayOf(day("2026-11-20", activity("晚了一天", "09:00", "10:00", "西湖"))));

    assertThat(codes(checks(id, session))).contains("DAILY_OUT_OF_RANGE");
  }

  // ---- 辅助 ----

  private void putPlanDates(String id, MockHttpSession session, String depart, String ret)
      throws Exception {
    long version = currentVersion(id, session);
    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "杭州");
    plan.put("summary", "西湖两日");
    plan.put("notes", "");
    plan.put("departDate", depart);
    plan.put("returnDate", ret);
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isOk());
  }

  private void putDaily(String id, MockHttpSession session, ArrayNode daily) throws Exception {
    long version = currentVersion(id, session);
    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(daily)))
        .andExpect(status().isOk());
  }

  private JsonNode checks(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/checks").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private List<String> codes(JsonNode checks) {
    List<String> list = new ArrayList<>();
    checks.path("warnings").forEach(w -> list.add(w.get("code").asText()));
    return list;
  }

  private long count(JsonNode checks, String code) {
    return codes(checks).stream().filter(code::equals).count();
  }

  /** 组装一天，可带多个活动。 */
  private ObjectNode day(String date, ObjectNode... activities) {
    ObjectNode node = json.createObjectNode();
    node.put("date", date);
    node.put("note", "");
    ArrayNode array = node.putArray("activities");
    for (ObjectNode a : activities) {
      array.add(a);
    }
    return node;
  }

  /** 组装一个带起止时间的活动。 */
  private ObjectNode activity(String title, String start, String end, String location) {
    ObjectNode node = json.createObjectNode();
    node.put("title", title);
    node.put("location", location);
    node.put("startTime", start);
    node.put("endTime", end);
    node.put("note", "");
    return node;
  }
}
