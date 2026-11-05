package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.travelassistant.server.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * 列表型请求体里出现 {@code null} 元素时的回归门禁。
 *
 * <p>复现路径很常见：前端表格留一个空行、或者把 {@code [undefined]} 直接
 * {@code JSON.stringify} 出去，发到服务端就是 {@code [null]}。修复前，四个板块的写入接口
 * 都在补 id 时解引用空元素抛 NPE，最终以 500 返回：用户看到「服务器内部错误」，
 * 日志里只剩一条没有上下文的 NPE，排障时很难定位到是哪一行表格。
 *
 * <p>断言的是「这是请求体不合法（400），不是服务器故障（500）」，同时错误体要与其它接口
 * 同样是 {@code {"error": "..."}}，前端不必为异常路径写第二套解析。
 */
@DisplayName("列表请求体含空元素（应为 400 而非 500）")
class MalformedListBodyTest extends ApiTestBase {

  private void expectBadRequest(MockHttpSession session, String path, String body)
      throws Exception {
    mvc.perform(put(path).session(session).param("version", "1")
            .contentType("application/json").content(body))
        .andExpect(status().isBadRequest())
        .andExpect(result -> {
          var response = json.readTree(result.getResponse().getContentAsString());
          assertThat(response.has("error")).isTrue();
          assertThat(response.get("error").asText()).isNotBlank();
        });
  }

  @Test
  @DisplayName("费用列表含 null 元素 → 400")
  void expensesWithNullElementIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/expenses", "[null]");
  }

  @Test
  @DisplayName("住宿列表含 null 元素 → 400")
  void lodgingsWithNullElementIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/lodgings", "[null]");
  }

  @Test
  @DisplayName("交通列表含 null 元素 → 400")
  void transportsWithNullElementIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/transports", "[null]");
  }

  @Test
  @DisplayName("日程列表含 null 元素 → 400")
  void dailyWithNullElementIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/daily", "[null]");
  }

  @Test
  @DisplayName("日程内的安排列表含 null 元素 → 400")
  void activityWithNullElementIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/daily",
        "[{\"date\":\"2026-11-20\",\"activities\":[null]}]");
  }

  @Test
  @DisplayName("JSON 语法错误 → 400，错误体统一为 {\"error\": ...}")
  void unparsableJsonIsRejectedWithUnifiedBody() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    expectBadRequest(owner, "/api/itineraries/" + id + "/expenses", "{not-json");
  }

  @Test
  @DisplayName("合法数组里的空元素位置被准确指出，且版本不推进")
  void errorMessagePointsAtTheOffendingIndex() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");
    long before = currentVersion(id, owner);

    ArrayNode body = json.createArrayNode();
    body.add(expense("交通", "120"));
    body.addNull();

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner).param("version", String.valueOf(before))
            .contentType("application/json").content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertThat(
            json.readTree(result.getResponse().getContentAsString()).get("error").asText())
            .contains("第 2 项"));

    assertThat(currentVersion(id, owner)).isEqualTo(before);
  }
}
