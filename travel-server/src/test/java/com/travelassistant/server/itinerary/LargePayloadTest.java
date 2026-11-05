package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 大载荷往返（T-13）。
 *
 * <p>真正要防的是 O(n²) 回退：{@code fillIds} 逐条补 id、{@code removeIf} 按 id 过滤、
 * 落盘时整份重写——任何一处退化成「每处理一条就扫一遍全表」，在 500 条时才显形。
 * 因此上限设得很宽（5 s），它不是性能指标，而是「复杂度没坏」的粗筛：
 * 当前实现两次往返都在 100 ms 内，真跑成 O(n²) 会是秒级。
 *
 * <p>断言里更重要的部分是**数据完整性**：500 笔全部保留、id 唯一、顺序未被打乱。
 */
@DisplayName("大载荷：500 笔费用 / 90 天日程")
class LargePayloadTest extends ApiTestBase {

  private static final int EXPENSES = 500;
  private static final int DAYS = 90;
  private static final long BUDGET_MS = 5000;

  @Test
  @DisplayName("500 笔费用写入并读回：一条不丢、id 唯一、耗时不失控")
  void fiveHundredExpensesRoundTrip() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "large-expenses");

    ArrayNode expenses = json.createArrayNode();
    for (int i = 0; i < EXPENSES; i++) {
      ObjectNode expense = expenses.addObject();
      expense.put("category", "c" + (i % 7));
      expense.put("amount", (i % 100) + 1);
      expense.put("currency", "CNY");
      expense.put("actual", i % 3 == 0);
    }

    long startWrite = System.nanoTime();
    MvcResult written = mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner)
            .param("version", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(expenses)))
        .andReturn();
    long writeMs = (System.nanoTime() - startWrite) / 1_000_000;

    assertThat(written.getResponse().getStatus()).isEqualTo(200);

    long startRead = System.nanoTime();
    MvcResult read = mvc.perform(get("/api/itineraries/" + id + "/expenses").session(owner))
        .andReturn();
    long readMs = (System.nanoTime() - startRead) / 1_000_000;

    assertThat(read.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = json.readTree(read.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(body).hasSize(EXPENSES);

    List<String> ids = new ArrayList<>();
    for (JsonNode expense : body) {
      assertThat(expense.get("id").asText()).as("服务端必须补上 id").isNotBlank();
      ids.add(expense.get("id").asText());
    }
    assertThat(ids).doesNotHaveDuplicates();

    // 顺序不能在补 id 的过程中被打乱
    for (int i = 0; i < EXPENSES; i++) {
      assertThat(body.get(i).get("category").asText()).isEqualTo("c" + (i % 7));
    }

    assertThat(writeMs).as("写入 %d 笔耗时 %d ms", EXPENSES, writeMs).isLessThan(BUDGET_MS);
    assertThat(readMs).as("读回 %d 笔耗时 %d ms", EXPENSES, readMs).isLessThan(BUDGET_MS);
  }

  @Test
  @DisplayName("90 天日程往返：每天保留、活动 id 全部补全")
  void ninetyDaysRoundTrip() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "large-daily");

    LocalDate start = LocalDate.of(2026, 11, 1);
    ArrayNode daily = json.createArrayNode();
    for (int d = 0; d < DAYS; d++) {
      ObjectNode day = daily.addObject();
      day.put("date", start.plusDays(d).toString());
      ArrayNode activities = day.putArray("activities");
      for (int a = 0; a < 2; a++) {
        ObjectNode activity = activities.addObject();
        activity.put("time", "0" + (8 + a) + ":00");
        activity.put("title", "d" + d + "-a" + a);
      }
    }

    long startWrite = System.nanoTime();
    MvcResult written = mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(owner)
            .param("version", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(daily)))
        .andReturn();
    long writeMs = (System.nanoTime() - startWrite) / 1_000_000;

    assertThat(written.getResponse().getStatus()).isEqualTo(200);

    MvcResult read = mvc.perform(get("/api/itineraries/" + id + "/daily").session(owner))
        .andReturn();
    JsonNode body = json.readTree(read.getResponse().getContentAsString(StandardCharsets.UTF_8));

    assertThat(body).hasSize(DAYS);
    for (int d = 0; d < DAYS; d++) {
      JsonNode day = body.get(d);
      assertThat(day.get("date").asText()).isEqualTo(start.plusDays(d).toString());
      assertThat(day.get("activities")).hasSize(2);
      assertThat(day.get("activities").get(0).get("id").asText()).isNotBlank();
      assertThat(day.get("activities").get(1).get("id").asText()).isNotBlank();
    }

    assertThat(writeMs).as("写入 %d 天耗时 %d ms", DAYS, writeMs).isLessThan(BUDGET_MS);
  }
}
