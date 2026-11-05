package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已确认缺陷的回归测试集。
 *
 * <p>每条用例对应测试负责人开发日志里的一条缺陷编号（D-1 ~ D-6）。
 * 缺陷修复后已去掉 {@code @Disabled}，现在作为回归门禁常驻执行：
 * 断言写的是「需求要求的正确行为」，一旦有人把校验改回去，这些用例会立刻变红。
 *
 * <p>依据：{@code docs/需求文档.md} 与 {@code docs/旅行助手-各版本功能模块.md}。
 */
@DisplayName("已知缺陷回归（已修复，转为回归门禁）")
class KnownDefectTest extends ApiTestBase {

  @Test
  @DisplayName("D-1 邀请不存在的账号应被拒绝")
  void invitingUnregisteredUserIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(post("/api/itineraries/" + id + "/members")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of(
                "username", "no_such_user_at_all",
                "editSections", List.of("PLAN")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("D-2 创建行程缺少标题应返回 400")
  void createWithoutTitleIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();

    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("plan", Map.of("destination", "杭州")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("D-2 创建行程标题为空字符串应返回 400")
  void createWithBlankTitleIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();

    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of(
                "title", "   ", "plan", Map.of("destination", "杭州")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("D-3 修改不存在的成员应返回 404，而不是静默成功")
  void updatingNonexistentMemberReturnsNotFound() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/members/ghost_member")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("editSections", List.of("PLAN")))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("D-4 费用金额为负数应被拒绝")
  void negativeExpenseAmountIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    ObjectNode negative = json.createObjectNode();
    negative.put("category", "交通");
    negative.put("amount", new BigDecimal("-999.99"));
    negative.put("currency", "CNY");
    negative.put("date", "2026-11-10");
    negative.put("note", "负数");

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(negative))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("D-5 住宿离店日期早于入住日期应被拒绝")
  void lodgingCheckOutBeforeCheckInIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    ObjectNode reversed = json.createObjectNode();
    reversed.put("name", "西湖边民宿");
    reversed.put("address", "西湖区某路 1 号");
    reversed.put("checkIn", "2026-11-12");
    reversed.put("checkOut", "2026-11-10");
    reversed.put("roomType", "大床房");
    reversed.put("note", "");

    mvc.perform(put("/api/itineraries/" + id + "/lodgings")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(reversed))))
        .andExpect(status().isBadRequest());
  }
}
