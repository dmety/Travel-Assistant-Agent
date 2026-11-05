package com.travelassistant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V3ApiTestBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3 第 3 节：综合决策建议。
 *
 * <p>核心断言不是「模型说了什么」，而是「规则算出了什么」——模型未配置时，
 * 风险和建议必须照样完整，这才是这套设计的意义。
 */
@DisplayName("V3 综合决策建议")
class DecisionAdviceTest extends V3ApiTestBase {

  private JsonNode advice(String id, MockHttpSession session, String body) throws Exception {
    MvcResult result = mvc.perform(post("/api/agent/itineraries/" + id + "/advice")
            .session(session)
            .contentType("application/json")
            .content(body == null ? "{}" : body))
        .andExpect(status().isOk())
        .andReturn();
    return read(result);
  }

  private JsonNode riskOf(JsonNode report, String code) {
    for (JsonNode risk : report.get("risks")) {
      if (code.equals(risk.get("code").asText())) {
        return risk;
      }
    }
    return null;
  }

  private String codes(JsonNode report) {
    StringBuilder sb = new StringBuilder();
    report.get("risks").forEach(r -> sb.append(r.get("code").asText()).append(' '));
    return sb.toString();
  }

  @Test
  @DisplayName("没有候选也没有上限：仍然给出可执行结论，模型缺失时只说明降级")
  void emptyItineraryStillReturnsRuleBasedAdvice() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "空白行程", "杭州", "2026-11-10", "2026-11-12", null);

    JsonNode report = advice(id, owner, null);

    assertThat(report.get("itineraryId").asText()).isEqualTo(id);
    assertThat(codes(report)).contains("NO_CANDIDATE").contains("BUDGET_LIMIT_MISSING");
    assertThat(report.get("advice").isNull()).isTrue();
    assertThat(report.get("notices").toString()).contains("未配置模型密钥");
    assertThat(report.get("votes").get("candidateCount").asInt()).isZero();
  }

  @Test
  @DisplayName("超支排在风险列表最前：HIGH 优先于 INFO")
  void budgetOverLimitIsTopRisk() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "超支行程", "杭州", "2026-11-10", "2026-11-12", "1000");
    putExpenses(id, owner, cost("交通", "1500", false));

    JsonNode report = advice(id, owner, null);

    JsonNode risk = riskOf(report, "BUDGET_OVER_LIMIT");
    assertThat(risk).isNotNull();
    assertThat(risk.get("severity").asText()).isEqualTo("HIGH");
    assertThat(risk.get("basis").asText()).isEqualTo("预算");
    assertThat(risk.get("suggestion").asText()).isNotBlank();
    // 排序：第一条必须是 HIGH
    assertThat(report.get("risks").get(0).get("severity").asText()).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("投票并列会被单独提示，且不指定赢家")
  void tieIsReported() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "并列", "杭州", null, null, null);
    String aId = addCandidate(id, owner, "甲");
    String bId = addCandidate(id, owner, "乙");

    String member = uniqueUsername("peer");
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of());

    vote(id, owner, aId, "SUPPORT");
    vote(id, memberSession, bId, "SUPPORT");

    JsonNode report = advice(id, owner, null);
    assertThat(riskOf(report, "VOTE_TIE")).isNotNull();
    assertThat(report.get("votes").get("leaderId").isNull()).isTrue();
  }

  @Test
  @DisplayName("未投票人数进入风险列表")
  void partialVoteIsReported() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "缺票", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");
    String member = uniqueUsername("quiet");
    registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of());
    vote(id, owner, cid, "SUPPORT");

    JsonNode report = advice(id, owner, null);
    JsonNode risk = riskOf(report, "VOTE_PARTIAL");
    assertThat(risk).isNotNull();
    assertThat(risk.get("message").asText()).contains("还有 1 位参与者没有投票");
  }

  @Test
  @DisplayName("同日安排时间重叠：体检风险进入报告")
  void overlapIsReported() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "重叠", "杭州", "2026-11-10", "2026-11-10", null);

    ObjectNode day = json.createObjectNode();
    day.put("date", "2026-11-10");
    day.put("note", "");
    ArrayNode acts = day.putArray("activities");
    ObjectNode a1 = acts.addObject();
    a1.put("title", "游船");
    a1.put("startTime", "09:00");
    a1.put("endTime", "12:00");
    ObjectNode a2 = acts.addObject();
    a2.put("title", "午饭");
    a2.put("startTime", "11:30");
    a2.put("endTime", "13:00");

    ArrayNode daily = json.createArrayNode();
    daily.add(day);
    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(owner)
            .param("version", Long.toString(version(id, owner)))
            .contentType("application/json")
            .content(json.writeValueAsString(daily)))
        .andExpect(status().isOk());

    JsonNode report = advice(id, owner, null);
    JsonNode risk = riskOf(report, "ACTIVITY_TIME_OVERLAP");
    assertThat(risk).isNotNull();
    assertThat(risk.get("severity").asText()).isEqualTo("MEDIUM");
    assertThat(report.get("suggestions").toString()).contains("时间重叠");
  }

  @Test
  @DisplayName("交通到达晚于当天安排：HIGH，且说明是交通与安排的冲突")
  void transportArrivalConflict() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "赶不上", "杭州", "2026-11-10", "2026-11-12", null);
    putTransport(id, owner, "2026-11-10", "08:00", "12:30");
    putDaily(id, owner, "2026-11-10", "游船", "09:00", "11:00");

    JsonNode report = advice(id, owner, null);
    JsonNode risk = riskOf(report, "TRANSPORT_ARRIVAL_CONFLICT");
    assertThat(risk).isNotNull();
    assertThat(risk.get("severity").asText()).isEqualTo("HIGH");
    assertThat(risk.get("basis").asText()).contains("交通与每日安排");
  }

  @Test
  @DisplayName("未配置高德：天气相关判断跳过，并在 notices 里说明")
  void weatherSkippedWithoutAmap() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "无天气", "杭州", null, null, null);

    JsonNode report = advice(id, owner, null);

    assertThat(report.get("weather").isNull()).isTrue();
    assertThat(report.get("notices").toString()).contains("未接入实时天气");
  }

  @Test
  @DisplayName("关闭天气查询：不再尝试，明确说明是调用方关掉的")
  void weatherCanBeDisabled() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "关天气", "杭州", null, null, null);

    JsonNode report = advice(id, owner, "{\"includeWeather\":false}");
    assertThat(report.get("notices").toString()).contains("关闭了天气查询");
  }

  @Test
  @DisplayName("票数最高者会体现在建议里，但不会声称「已决定」")
  void leaderAppearsInSuggestions() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "建议", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "灵隐寺");
    vote(id, owner, cid, "SUPPORT");

    JsonNode report = advice(id, owner, null);
    String suggestions = report.get("suggestions").toString();
    assertThat(suggestions).contains("灵隐寺");
    // 投票结果不会被说成结论
    assertThat(suggestions).doesNotContain("已决定").doesNotContain("最终选择是");
  }

  @Test
  @DisplayName("越权：非参与者不能对我的行程要建议（403）")
  void adviceRequiresAccess() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "私有", "杭州", null, null, null);
    MockHttpSession outsider = registerAndLogin();

    mvc.perform(post("/api/agent/itineraries/" + id + "/advice")
            .session(outsider)
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("建议不落库：调用后行程版本号不变")
  void adviceDoesNotMutate() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "只读建议", "杭州", "2026-11-10", "2026-11-12", "2000");
    long before = version(id, owner);

    advice(id, owner, "{\"travelers\":3}");

    assertThat(version(id, owner)).isEqualTo(before);
    JsonNode report = advice(id, owner, "{\"travelers\":3}");
    assertThat(report.get("budget").get("travelers").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("归档行程也能分析，但会提示当前只读")
  void archivedItineraryCanBeReviewed() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "归档后", "杭州", "2026-11-10", "2026-11-12", null);
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());

    JsonNode report = advice(id, owner, null);
    JsonNode risk = riskOf(report, "ITINERARY_ARCHIVED");
    assertThat(risk).isNotNull();
    assertThat(risk.get("severity").asText()).isEqualTo("INFO");
  }

  @Test
  @DisplayName("能力状态包含综合决策分析")
  void statusMentionsNewCapability() throws Exception {
    MockHttpSession session = registerAndLogin();
    JsonNode status = read(mvc.perform(get("/api/agent/status").session(session))
        .andExpect(status().isOk()).andReturn());
    assertThat(status.get("capabilities").toString()).contains("综合决策分析");
  }
}
