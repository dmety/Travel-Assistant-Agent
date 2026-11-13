package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1 第 5 节「预算与时间检查」验收测试。
 *
 * <p>覆盖：分类汇总、待填写费用、多币种不合并、出发/返回日期写入校验、
 * 体检提示（缺日期 / 越界安排）、只读接口不改版本号、越权与未登录拦截。
 */
@DisplayName("V1-5 预算与时间检查")
class ItineraryBudgetTest extends ApiTestBase {

  @Autowired
  private ItineraryStore store;

  // ---- 预算汇总 ----

  @Test
  @DisplayName("无费用时预算为 0，且不产生分类行")
  void emptyBudgetIsZero() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "空预算行程");

    mvc.perform(get("/api/itineraries/" + id + "/budget").session(owner))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Version", "1"))
        .andExpect(jsonPath("$.primaryCurrency").value("CNY"))
        .andExpect(jsonPath("$.totals.length()").value(1))
        .andExpect(jsonPath("$.itemCount").value(0))
        .andExpect(jsonPath("$.pendingCount").value(0))
        .andExpect(jsonPath("$.byCategory.length()").value(0))
        .andExpect(jsonPath("$.mixedCurrency").value(false));

    JsonNode body = budget(id, owner);
    assertThat(body.get("totals").get(0).get("amount").asDouble()).isEqualTo(0.0);
    assertThat(body.get("totals").get(0).get("itemCount").asInt()).isZero();
  }

  @Test
  @DisplayName("按类别汇总：同类多条相加，类别行保留条目数")
  void sumsByCategory() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    saveExpenses(id, owner, 1,
        expenseOf("交通", "600", "CNY"),
        expenseOf("住宿", "800", "CNY"),
        expenseOf("交通", "200", "CNY"));

    JsonNode body = budget(id, owner);
    assertThat(body.get("itemCount").asInt()).as("三条费用都应计入").isEqualTo(3);
    assertThat(body.get("totals").get(0).get("amount").asDouble()).as("总额 1600").isEqualTo(1600.0);
    assertThat(categoryAmount(body, "交通", "CNY")).as("交通 600+200").isEqualTo(800.0);
    assertThat(categoryAmount(body, "住宿", "CNY")).isEqualTo(800.0);
    assertThat(categoryCount(body, "交通", "CNY")).as("交通有 2 条").isEqualTo(2);
    assertThat(body.get("mixedCurrency").asBoolean()).isFalse();
    assertThat(budgetRaw(id, owner)).as("金额必须按两位小数输出，不能被截成 1600.0")
        .contains("\"amount\":1600.00");
  }

  @Test
  @DisplayName("未填金额的费用标记为待填写，且不计入合计")
  void pendingExpenseIsMarkedNotSummed() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "待填写费用");
    saveExpenses(id, owner, 1,
        expenseOf("交通", "300", "CNY"),
        expenseOf("住宿", null, "CNY"));

    JsonNode body = budget(id, owner);
    assertThat(body.get("itemCount").asInt()).as("只有 1 条参与合计").isEqualTo(1);
    assertThat(body.get("pendingCount").asInt()).isEqualTo(1);
    assertThat(body.get("totals").get(0).get("amount").asDouble()).isEqualTo(300.0);
    assertThat(body.get("pending").get(0).get("category").asText()).isEqualTo("住宿");
    assertThat(notes(body)).anyMatch(n -> n.contains("待填写"));
  }

  @Test
  @DisplayName("多币种分别汇总，绝不直接相加")
  void keepsCurrenciesSeparate() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "跨境行程");
    saveExpenses(id, owner, 1,
        expenseOf("交通", "100", "CNY"),
        expenseOf("餐饮", "100", "CNY"),
        expenseOf("交通", "100", "USD"));

    JsonNode body = budget(id, owner);
    assertThat(body.get("mixedCurrency").asBoolean()).isTrue();
    assertThat(body.get("currencies")).hasSize(2);
    assertThat(body.get("totals")).as("两个币种两行").hasSize(2);
    assertThat(body.get("primaryCurrency").asText()).as("条目多的 CNY 为主币种").isEqualTo("CNY");
    assertThat(body.get("totals").get(0).get("currency").asText()).isEqualTo("CNY");
    assertThat(body.get("totals").get(0).get("amount").asDouble()).as("CNY 合计 200").isEqualTo(200.0);
    assertThat(body.get("totals").get(1).get("currency").asText()).isEqualTo("USD");
    assertThat(body.get("totals").get(1).get("amount").asDouble()).as("USD 合计 100").isEqualTo(100.0);
    assertThat(categoryAmount(body, "交通", "CNY")).isEqualTo(100.0);
    assertThat(categoryAmount(body, "交通", "USD")).as("同类不同币种不合并").isEqualTo(100.0);
    assertThat(notes(body)).anyMatch(n -> n.contains("汇率换算"));
  }

  @Test
  @DisplayName("类别为空归入「未分类」，币种为空按 CNY 处理")
  void blankCategoryAndCurrencyAreNormalized() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "缺字段费用");
    saveExpenses(id, owner, 1, expenseOf(null, "50", null));

    JsonNode body = budget(id, owner);
    assertThat(body.get("byCategory").get(0).get("category").asText()).isEqualTo("未分类");
    assertThat(body.get("byCategory").get(0).get("currency").asText()).isEqualTo("CNY");
    assertThat(body.get("totals").get(0).get("amount").asDouble()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("预算接口是只读的，不改变行程版本号")
  void budgetIsReadOnly() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "只读校验");
    saveExpenses(id, owner, 1, expenseOf("交通", "100", "CNY"));

    long before = currentVersion(id, owner);
    mvc.perform(get("/api/itineraries/" + id + "/budget").session(owner))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Version", Long.toString(before)));
    mvc.perform(get("/api/itineraries/" + id + "/checks").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(before));
    assertThat(currentVersion(id, owner)).as("读预算/体检不应写库").isEqualTo(before);
  }

  // ---- 时间校验（写入拦截） ----

  @Test
  @DisplayName("更新方案时返回早于出发 → 400，且版本不变")
  void rejectsReturnBeforeDepartOnUpdate() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "日期倒置");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(planWithDates("2026-11-10", "2026-11-08"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("返回日期不能早于出发日期")));

    assertThat(currentVersion(id, owner)).as("校验失败不得落库").isEqualTo(1);
  }

  @Test
  @DisplayName("创建行程时返回早于出发 → 400")
  void rejectsReturnBeforeDepartOnCreate() throws Exception {
    MockHttpSession owner = registerAndLogin();
    ObjectNode body = json.createObjectNode();
    body.put("title", "日期倒置");
    body.set("plan", planWithDates("2026-11-10", "2026-11-09"));

    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("同日往返合法，出发/返回日期落库可读回")
  void acceptsSameDayTrip() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "当日往返");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(planWithDates("2026-11-10", "2026-11-10"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan.departDate").value("2026-11-10"))
        .andExpect(jsonPath("$.plan.returnDate").value("2026-11-10"));
  }

  // ---- 体检 ----

  @Test
  @DisplayName("缺日期、无费用时体检不通过，给出稳定 code")
  void checksReportMissingDatesAndNoExpense() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "信息不全");

    JsonNode body = checks(id, owner);
    assertThat(body.get("ok").asBoolean()).isFalse();
    List<String> codes = codes(body);
    assertThat(codes).contains("DEPART_DATE_MISSING", "RETURN_DATE_MISSING", "NO_EXPENSE");
  }

  @Test
  @DisplayName("信息完整且安排都在范围内时体检通过")
  void checksPassWhenDataIsComplete() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "完整行程");
    // 相对今天取日期：硬编码的固定日期会随日历推移变成过去，触发 TRIP_ENDED 而误报体检不通过
    LocalDate depart = LocalDate.now();
    LocalDate ret = depart.plusDays(2);
    savePlan(id, owner, planWithDates(depart.toString(), ret.toString()), 1);
    saveDaily(id, owner, 2, depart.plusDays(1).toString(), "逛西湖");
    saveTransports(id, owner, 3, transportOn(depart.toString()));
    saveLodgings(id, owner, 4, lodgingBetween(depart.toString(), ret.toString()));
    saveExpenses(id, owner, 5, expenseOf("交通", "600", "CNY"));

    JsonNode body = checks(id, owner);
    assertThat(codes(body)).as("不应有提示").isEmpty();
    assertThat(body.get("ok").asBoolean()).isTrue();
    assertThat(body.get("version").asLong()).isEqualTo(6);
  }

  @Test
  @DisplayName("每日安排或交通超出旅行时间范围时给出提示")
  void checksFlagOutOfRangeSchedules() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "越界安排");
    savePlan(id, owner, planWithDates("2026-11-10", "2026-11-12"), 1);
    saveDaily(id, owner, 2, "2026-12-01", "不该在这天");
    saveTransports(id, owner, 3, transportOn("2026-12-20"));
    saveExpenses(id, owner, 4, expenseOf("交通", "600", "CNY"));

    JsonNode body = checks(id, owner);
    assertThat(codes(body)).contains("DAILY_OUT_OF_RANGE", "TRANSPORT_OUT_OF_RANGE");
    assertThat(body.get("ok").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("日期区间倒置时只报一条倒置提示，不重复刷越界")
  void invertedRangeReportsSingleWarning() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "历史脏数据");
    savePlan(id, owner, planWithDates("2026-11-10", "2026-11-12"), 1);
    saveDaily(id, owner, 2, "2026-11-11", "逛西湖");
    // 接口已被校验拦住，只能绕过 service 直接改仓库，模拟 V1 之前落盘的旧数据
    writeInvertedPlanDates(id, "2026-11-12", "2026-11-10");

    JsonNode body = checks(id, owner);
    assertThat(codes(body)).contains("RETURN_BEFORE_DEPART");
    assertThat(codes(body)).doesNotContain("DAILY_OUT_OF_RANGE");
  }

  // ---- 权限 ----

  @Test
  @DisplayName("非成员读预算/体检 → 403")
  void nonMemberIsForbidden() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "越权测试");
    MockHttpSession stranger = registerAndLogin();

    mvc.perform(get("/api/itineraries/" + id + "/budget").session(stranger))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/itineraries/" + id + "/checks").session(stranger))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("未登录读预算/体检 → 401")
  void anonymousIsUnauthorized() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "未登录");

    mvc.perform(get("/api/itineraries/" + id + "/budget")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/checks")).andExpect(status().isUnauthorized());
  }

  // ---- 辅助 ----

  private JsonNode budget(String id, MockHttpSession session) throws Exception {
    return json.readTree(budgetRaw(id, session));
  }

  private String budgetRaw(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/budget").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private JsonNode checks(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/checks").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(
        java.nio.charset.StandardCharsets.UTF_8));
  }

  private List<String> codes(JsonNode checks) {
    List<String> codes = new ArrayList<>();
    checks.get("warnings").forEach(w -> codes.add(w.get("code").asText()));
    return codes;
  }

  private List<String> notes(JsonNode budget) {
    List<String> notes = new ArrayList<>();
    budget.get("notes").forEach(n -> notes.add(n.asText()));
    return notes;
  }

  private double categoryAmount(JsonNode budget, String category, String currency) {
    for (JsonNode row : budget.get("byCategory")) {
      if (row.get("category").asText().equals(category)
          && row.get("currency").asText().equals(currency)) {
        return row.get("amount").asDouble();
      }
    }
    throw new AssertionError("未找到分类行：" + category + "/" + currency);
  }

  private int categoryCount(JsonNode budget, String category, String currency) {
    for (JsonNode row : budget.get("byCategory")) {
      if (row.get("category").asText().equals(category)
          && row.get("currency").asText().equals(currency)) {
        return row.get("itemCount").asInt();
      }
    }
    throw new AssertionError("未找到分类行：" + category + "/" + currency);
  }

  private ObjectNode planWithDates(String depart, String ret) {
    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "杭州");
    plan.put("summary", "西湖与灵隐寺");
    plan.put("notes", "高铁往返");
    if (depart != null) {
      plan.put("departDate", depart);
    }
    if (ret != null) {
      plan.put("returnDate", ret);
    }
    return plan;
  }

  private ObjectNode expenseOf(String category, String amount, String currency) {
    ObjectNode node = json.createObjectNode();
    if (category != null) {
      node.put("category", category);
    }
    if (amount != null) {
      node.put("amount", new BigDecimal(amount));
    }
    if (currency != null) {
      node.put("currency", currency);
    }
    node.put("date", "2026-11-10");
    node.put("note", "预算");
    return node;
  }

  private ObjectNode transportOn(String date) {
    ObjectNode node = transport("高铁", "北京", "杭州");
    node.put("date", date);
    return node;
  }

  private ObjectNode lodgingBetween(String checkIn, String checkOut) {
    ObjectNode node = lodging("西湖边民宿");
    node.put("checkIn", checkIn);
    node.put("checkOut", checkOut);
    return node;
  }

  private void savePlan(String id, MockHttpSession session, ObjectNode plan, long version)
      throws Exception {
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isOk());
  }

  private void saveDaily(String id, MockHttpSession session, long version, String date, String title)
      throws Exception {
    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(dailySchedule(date, title)))))
        .andExpect(status().isOk());
  }

  private void saveTransports(String id, MockHttpSession session, long version, ObjectNode... items)
      throws Exception {
    mvc.perform(put("/api/itineraries/" + id + "/transports")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(items))))
        .andExpect(status().isOk());
  }

  private void saveLodgings(String id, MockHttpSession session, long version, ObjectNode... items)
      throws Exception {
    mvc.perform(put("/api/itineraries/" + id + "/lodgings")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(items))))
        .andExpect(status().isOk());
  }

  private void saveExpenses(String id, MockHttpSession session, long version, ObjectNode... items)
      throws Exception {
    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(items))))
        .andExpect(status().isOk());
  }

  /**
   * 模拟历史脏数据：写入口的校验在 service 层，接口无法写入倒置日期，
   * 因此绕过 service 直接经仓库落盘一份倒置方案。
   */
  private void writeInvertedPlanDates(String id, String depart, String ret) {
    Itinerary original = store.findById(id).orElseThrow();
    Plan plan = original.plan();
    Plan inverted = new Plan(plan.destination(), plan.summary(), plan.notes(),
        LocalDate.parse(depart), LocalDate.parse(ret));
    store.save(new Itinerary(original.id(), original.owner(), original.title(),
        original.version(), original.createdAt(), original.updatedAt(), original.members(),
        inverted, original.dailySchedules(), original.transports(), original.lodgings(),
        original.expenses()));
  }
}
