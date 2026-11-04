package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 五大板块读写验收测试（任务 #3 的「填写日期、景点、交通、住宿和费用」部分）。
 *
 * <p>板块：方案 PLAN、每日安排 DAILY、交通 TRANSPORT、住宿 LODGING、费用 EXPENSE。
 * 每个板块都验证「写入成功 → 版本号 +1 → 读回一致」。
 */
@DisplayName("任务 #3 五大板块读写")
class ItinerarySectionTest extends ApiTestBase {

  @Test
  @DisplayName("方案板块：写入后读回一致，版本号 +1")
  void planSectionIsWritableAndReadable() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of(
                "destination", "杭州", "summary", "改过的摘要", "notes", "加了西溪湿地"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.plan.summary").value("改过的摘要"));

    mvc.perform(get("/api/itineraries/" + id + "/plan").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").value("改过的摘要"))
        .andExpect(jsonPath("$.notes").value("加了西溪湿地"));
  }

  @Test
  @DisplayName("读板块时返回 X-Version 头，便于客户端做乐观锁")
  void sectionReadReturnsVersionHeader() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(get("/api/itineraries/" + id + "/plan").session(owner))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Version", "1"));
  }

  @Test
  @DisplayName("每日安排：写入后读回一致，服务端为缺失的活动补 id")
  void dailySectionIsWritableAndReadable() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(dailySchedule("2026-11-10", "逛西湖")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.dailySchedules[0].date").value("2026-11-10"))
        .andExpect(jsonPath("$.dailySchedules[0].id").exists())
        .andExpect(jsonPath("$.dailySchedules[0].activities[0].title").value("逛西湖"))
        .andExpect(jsonPath("$.dailySchedules[0].activities[0].id").exists());

    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/daily").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].activities[0].title").value("逛西湖"))
        .andReturn();
    JsonNode days = json.readTree(result.getResponse().getContentAsString());
    assertThat(days.get(0).get("id").asText()).as("服务端应自动生成每日安排的 id").isNotBlank();
  }

  @Test
  @DisplayName("交通板块：写入后读回一致并保留业务字段")
  void transportSectionIsWritableAndReadable() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/transports")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(transport("高铁", "北京", "杭州")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.transports[0].mode").value("高铁"))
        .andExpect(jsonPath("$.transports[0].from").value("北京"))
        .andExpect(jsonPath("$.transports[0].id").exists());

    mvc.perform(get("/api/itineraries/" + id + "/transports").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].to").value("杭州"))
        .andExpect(jsonPath("$[0].departTime").value("08:00"))
        .andExpect(jsonPath("$[0].flightNo").value("G1234"));
  }

  @Test
  @DisplayName("住宿板块：写入后读回一致并保留入离日期")
  void lodgingSectionIsWritableAndReadable() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/lodgings")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(lodging("西湖边民宿")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.lodgings[0].name").value("西湖边民宿"))
        .andExpect(jsonPath("$.lodgings[0].id").exists());

    mvc.perform(get("/api/itineraries/" + id + "/lodgings").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].checkIn").value("2026-11-10"))
        .andExpect(jsonPath("$[0].checkOut").value("2026-11-12"))
        .andExpect(jsonPath("$[0].roomType").value("大床房"));
  }

  @Test
  @DisplayName("费用板块：写入后读回一致，金额按 BigDecimal 精确保存")
  void expenseSectionIsWritableAndReadable() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(
                expense("交通", "580.50"), expense("住宿", "1200.00")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.expenses[0].category").value("交通"))
        .andExpect(jsonPath("$.expenses.length()").value(2));

    mvc.perform(get("/api/itineraries/" + id + "/expenses").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].amount").value(580.50))
        .andExpect(jsonPath("$[1].amount").value(1200.00))
        .andExpect(jsonPath("$[0].currency").value("CNY"));
  }

  @Test
  @DisplayName("五个板块依次写入，每次写入版本号各 +1")
  void eachSectionWriteBumpsVersion() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    long version = 1;
    String[] sections = {"plan", "daily", "transports", "lodgings", "expenses"};
    for (String section : sections) {
      String body = switch (section) {
        case "plan" -> json.writeValueAsString(
            java.util.Map.of("destination", "杭州", "summary", "s", "notes", "n"));
        case "daily" -> json.writeValueAsString(arrayOf(dailySchedule("2026-11-10", "逛西湖")));
        case "transports" -> json.writeValueAsString(arrayOf(transport("高铁", "北京", "杭州")));
        case "lodgings" -> json.writeValueAsString(arrayOf(lodging("民宿")));
        default -> json.writeValueAsString(arrayOf(expense("交通", "100")));
      };

      version++;
      mvc.perform(put("/api/itineraries/" + id + "/" + section)
              .session(owner)
              .param("version", Long.toString(version - 1))
              .contentType("application/json")
              .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.version").value(version));
    }
    assertThat(version).as("五次写入后版本号应为 6").isEqualTo(6);
  }

  @Test
  @DisplayName("板块数据清空后为数组/空值，不报错")
  void clearingSectionIsTolerated() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner)
            .param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(List.of())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expenses.length()").value(0));
  }
}
