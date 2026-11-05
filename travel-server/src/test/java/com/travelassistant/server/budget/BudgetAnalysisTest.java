package com.travelassistant.server.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V3ApiTestBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 第 2 节：预算分析。
 *
 * <p>重点验证「不拿 0 冒充没填」「不跨币种硬减」和「预计/实际分开算」三条口径。
 */
@DisplayName("V3 预算分析")
class BudgetAnalysisTest extends V3ApiTestBase {

  private BigDecimal decimal(JsonNode node, String field) {
    return node.get(field).decimalValue();
  }

  private boolean hasWarning(JsonNode analysis, String code) {
    for (JsonNode warning : analysis.get("warnings")) {
      if (code.equals(warning.get("code").asText())) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("未设上限：不给差额，明确提示「无法判断是否超支」")
  void noLimitMeansNoComparison() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "无上限", "杭州", "2026-11-10", "2026-11-12", null);
    putExpenses(id, session, cost("交通", "1200", false));

    JsonNode analysis = analysis(id, session, null);

    assertThat(analysis.get("limit").isNull()).isTrue();
    assertThat(analysis.get("remaining").isNull()).isTrue();
    assertThat(analysis.get("overLimit").asBoolean()).isFalse();
    assertThat(hasWarning(analysis, "BUDGET_LIMIT_MISSING")).isTrue();
  }

  @Test
  @DisplayName("上限内：差额 = 上限 − 预计，人均按人数分摊")
  void remainingWithinLimit() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "有上限", "杭州", "2026-11-10", "2026-11-12", "3000");
    putExpenses(id, session, cost("交通", "1200", false), cost("住宿", "800", false));

    JsonNode analysis = analysis(id, session, null);

    assertThat(decimal(analysis, "limit")).isEqualByComparingTo("3000");
    assertThat(decimal(analysis, "estimatedTotal")).isEqualByComparingTo("2000");
    assertThat(decimal(analysis, "remaining")).isEqualByComparingTo("1000");
    assertThat(analysis.get("overLimit").asBoolean()).isFalse();
    assertThat(analysis.get("travelers").asInt()).isEqualTo(1);
    assertThat(decimal(analysis, "perPerson")).isEqualByComparingTo("2000");
  }

  @Test
  @DisplayName("超上限：overBy 给出超出的钱，提示为超支")
  void overLimitReportsAmount() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "超支", "杭州", "2026-11-10", "2026-11-12", "1000");
    putExpenses(id, session, cost("交通", "1200", false));

    JsonNode analysis = analysis(id, session, null);

    assertThat(analysis.get("overLimit").asBoolean()).isTrue();
    assertThat(decimal(analysis, "overBy")).isEqualByComparingTo("200");
    assertThat(decimal(analysis, "remaining")).isEqualByComparingTo("-200");
    assertThat(hasWarning(analysis, "BUDGET_OVER_LIMIT")).isTrue();
  }

  @Test
  @DisplayName("预计与实际分开：标记「已发生」的才计入实际总额")
  void actualAndPlannedAreSeparated() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "实际", "杭州", "2026-11-10", "2026-11-12", "2000");
    putExpenses(id, session,
        cost("交通", "800", true),
        cost("门票", "300", false));

    JsonNode analysis = analysis(id, session, null);

    assertThat(decimal(analysis, "estimatedTotal")).isEqualByComparingTo("1100");
    assertThat(decimal(analysis, "actualTotal")).isEqualByComparingTo("800");
    assertThat(decimal(analysis, "plannedTotal")).isEqualByComparingTo("300");
  }

  @Test
  @DisplayName("实际已超上限：单独给出「已花超」提示，不只是「预计超支」")
  void actualOverLimitIsReported() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "实际超支", "杭州", "2026-11-10", "2026-11-12", "500");
    putExpenses(id, session, cost("交通", "700", true));

    JsonNode analysis = analysis(id, session, null);

    assertThat(decimal(analysis, "actualTotal")).isEqualByComparingTo("700");
    assertThat(analysis.get("overLimit").asBoolean()).isTrue();
    assertThat(hasWarning(analysis, "BUDGET_OVER_LIMIT")).isTrue();
    assertThat(hasWarning(analysis, "BUDGET_ACTUAL_OVER_LIMIT")).isTrue();
  }

  @Test
  @DisplayName("人数可覆盖：按指定人数分摊人均")
  void travelersOverride() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "人均", "杭州", "2026-11-10", "2026-11-12", "3000");
    putExpenses(id, session, cost("交通", "1200", false));

    JsonNode analysis = analysis(id, session, 4);
    assertThat(analysis.get("travelers").asInt()).isEqualTo(4);
    assertThat(decimal(analysis, "perPerson")).isEqualByComparingTo("300");

    JsonNode notes = analysis.get("notes");
    assertThat(notes.toString()).contains("调用方指定");
  }

  @Test
  @DisplayName("人数按「创建者 + 成员」计算")
  void travelersFollowMembers() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "成员人均", "杭州", "2026-11-10", "2026-11-12", "3000");
    putExpenses(id, owner, cost("交通", "1200", false));

    String member = uniqueUsername("peer");
    registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of());

    JsonNode analysis = analysis(id, owner, null);
    assertThat(analysis.get("travelers").asInt()).isEqualTo(2);
    assertThat(decimal(analysis, "perPerson")).isEqualByComparingTo("600");
  }

  @Test
  @DisplayName("币种不一致：不硬减，remaining 为 null 并说明原因")
  void currencyMismatchIsNotSubtracted() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "币种", "东京", "2026-12-01", "2026-12-05", "5000");

    ObjectNode usd = json.createObjectNode();
    usd.put("category", "住宿");
    usd.put("amount", new BigDecimal("800"));
    usd.put("currency", "USD");
    usd.put("date", "2026-12-01");
    usd.put("note", "美元");
    usd.put("actual", false);
    putExpenses(id, session, usd);

    JsonNode analysis = analysis(id, session, null);

    assertThat(analysis.get("currency").asText()).isEqualTo("USD");
    assertThat(analysis.get("comparable").asBoolean()).isFalse();
    assertThat(analysis.get("remaining").isNull()).isTrue();
    assertThat(analysis.get("overLimit").asBoolean()).isFalse();
    assertThat(hasWarning(analysis, "BUDGET_CURRENCY_MISMATCH")).isTrue();
  }

  @Test
  @DisplayName("没有任何费用：预计为 0，人均为 0，不报 NPE")
  void emptyExpenses() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "空", "杭州", "2026-11-10", "2026-11-12", "1000");

    JsonNode analysis = analysis(id, session, null);

    assertThat(decimal(analysis, "estimatedTotal")).isEqualByComparingTo("0");
    assertThat(decimal(analysis, "actualTotal")).isEqualByComparingTo("0");
    assertThat(decimal(analysis, "perPerson")).isEqualByComparingTo("0");
    assertThat(decimal(analysis, "remaining")).isEqualByComparingTo("1000");
  }
}
