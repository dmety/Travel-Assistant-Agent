package com.travelassistant.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3 测试基类：在 V2 基类基础上补齐「带日期与预算上限的行程」「费用」「候选与投票」的快捷方法。
 *
 * <p>继承 V2 基类是为了继续强制模型与高德密钥为空——V3 的决策建议同样依赖这两项，
 * 测试绝不能因为本机配了环境变量就去发真实请求。
 */
public abstract class V3ApiTestBase extends V2ApiTestBase {

  /** 创建带起止日期与预算上限的行程，返回 id。 */
  protected String createTrip(MockHttpSession session, String title, String destination,
      String depart, String ret, String budgetLimit) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("title", title);
    ObjectNode plan = body.putObject("plan");
    plan.put("destination", destination);
    plan.put("summary", "测试方案");
    plan.put("notes", "");
    if (depart != null) {
      plan.put("departDate", depart);
    }
    if (ret != null) {
      plan.put("returnDate", ret);
    }
    if (budgetLimit != null) {
      plan.put("budgetLimit", new java.math.BigDecimal(budgetLimit));
      plan.put("budgetCurrency", "CNY");
    }

    MvcResult result = mvc.perform(post("/api/itineraries")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
    return read(result).get("id").asText();
  }

  /** 读取行程当前版本号。 */
  protected long version(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    return read(result).get("version").asLong();
  }

  /** 一条费用。{@code actual} 决定它算「已发生」还是「预计」。 */
  protected ObjectNode cost(String category, String amount, boolean actual) {
    ObjectNode node = json.createObjectNode();
    node.put("category", category);
    node.put("amount", new java.math.BigDecimal(amount));
    node.put("currency", "CNY");
    node.put("date", "2026-11-10");
    node.put("note", category);
    node.put("actual", actual);
    return node;
  }

  /** 覆盖式写入费用列表。 */
  protected void putExpenses(String id, MockHttpSession session, ObjectNode... expenses)
      throws Exception {
    ArrayNode array = json.createArrayNode();
    for (ObjectNode e : expenses) {
      array.add(e);
    }
    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(session)
            .param("version", Long.toString(version(id, session)))
            .contentType("application/json")
            .content(json.writeValueAsString(array)))
        .andExpect(status().isOk());
  }

  /** 添加候选景点，返回候选 id。 */
  protected String addCandidate(String id, MockHttpSession session, String name) throws Exception {
    return addCandidate(id, session, name, "ATTRACTION");
  }

  protected String addCandidate(String id, MockHttpSession session, String name, String kind)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("kind", kind);
    body.put("name", name);
    body.put("city", "杭州");
    body.put("note", "");
    MvcResult result = mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
    return read(result).get("id").asText();
  }

  /** 投票（可改票）。 */
  protected JsonNode vote(String id, MockHttpSession session, String candidateId, String choice)
      throws Exception {
    MvcResult result = mvc.perform(post(
            "/api/itineraries/" + id + "/candidates/" + candidateId + "/votes")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("choice", choice))))
        .andExpect(status().isOk())
        .andReturn();
    return read(result);
  }

  /** 读取候选看板。 */
  protected JsonNode board(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/candidates")
            .session(session))
        .andExpect(status().isOk())
        .andReturn();
    return read(result);
  }

  /** 读取预算分析。 */
  protected JsonNode analysis(String id, MockHttpSession session, Integer travelers)
      throws Exception {
    var request = get("/api/itineraries/" + id + "/budget/analysis").session(session);
    if (travelers != null) {
      request = request.param("travelers", Integer.toString(travelers));
    }
    MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
    return read(result);
  }

  /** 给行程添加成员并授予指定板块编辑权限，返回新版本号。 */
  protected long addMember(String id, MockHttpSession owner, String memberUsername,
      java.util.List<String> sections) throws Exception {
    MvcResult result = mvc.perform(post("/api/itineraries/" + id + "/members")
            .session(owner)
            .param("version", Long.toString(version(id, owner)))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", memberUsername,
                "editSections", sections))))
        .andExpect(status().isOk())
        .andReturn();
    return read(result).get("version").asLong();
  }

  /** 每天一条活动（用于时间冲突与交通衔接测试）。 */
  protected void putDaily(String id, MockHttpSession session, String date, String title,
      String start, String end) throws Exception {
    ObjectNode day = json.createObjectNode();
    day.put("date", date);
    day.put("note", "");
    ObjectNode activity = day.putArray("activities").addObject();
    activity.put("title", title);
    activity.put("location", "杭州");
    activity.put("note", "");
    if (start != null) {
      activity.put("startTime", start);
    }
    if (end != null) {
      activity.put("endTime", end);
    }
    ArrayNode daily = json.createArrayNode();
    daily.add(day);
    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(session)
            .param("version", Long.toString(version(id, session)))
            .contentType("application/json")
            .content(json.writeValueAsString(daily)))
        .andExpect(status().isOk());
  }

  /** 登记一段交通。 */
  protected void putTransport(String id, MockHttpSession session, String date, String departTime,
      String arriveTime) throws Exception {
    ObjectNode t = json.createObjectNode();
    t.put("mode", "高铁");
    t.put("from", "上海");
    t.put("to", "杭州");
    t.put("date", date);
    t.put("departTime", departTime);
    t.put("arriveTime", arriveTime);
    t.put("flightNo", "G1234");
    t.put("seat", "二等座");
    t.put("note", "");
    ArrayNode array = json.createArrayNode();
    array.add(t);
    mvc.perform(put("/api/itineraries/" + id + "/transports")
            .session(session)
            .param("version", Long.toString(version(id, session)))
            .contentType("application/json")
            .content(json.writeValueAsString(array)))
        .andExpect(status().isOk());
  }

  /** 读取行程 JSON。 */
  protected JsonNode trip(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    return read(result);
  }

  /** 从候选看板里按名称找一项。 */
  protected JsonNode tallyOf(JsonNode board, String name) {
    for (JsonNode node : board.get("candidates")) {
      if (name.equals(node.get("candidate").get("name").asText())) {
        return node;
      }
    }
    throw new AssertionError("看板里没有候选：" + name);
  }

  /** 导出文本。 */
  protected String exportText(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/export").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
