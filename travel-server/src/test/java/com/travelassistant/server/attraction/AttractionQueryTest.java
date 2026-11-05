package com.travelassistant.server.attraction;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点查询验收测试：城市/名称/ID 三种入口、条数限制、无结果提示、
 * 占位值规范化、未知票价与开放时间。
 */
@DisplayName("景点查询")
class AttractionQueryTest extends AttractionTestBase {

  @Test
  @DisplayName("输入城市名列出库中景点")
  void listByCity() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "杭州"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(3))
        .andExpect(jsonPath("$.returned").value(3))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].city").value("杭州"));
  }

  @Test
  @DisplayName("省份名同样可作为查询入口")
  void listByProvince() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "浙江省"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(3));
  }

  @Test
  @DisplayName("limit 生效：命中 3 条只返回 2 条，并保留命中总数")
  void limitTruncatesButKeepsMatchedTotal() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "杭州").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(3))
        .andExpect(jsonPath("$.returned").value(2))
        .andExpect(jsonPath("$.limit").value(2))
        .andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  @DisplayName("条数上限封顶 50，非正数回落默认 20")
  void limitIsClamped() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "杭州").param("limit", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(50));

    mvc.perform(get("/api/attractions").param("city", "杭州").param("limit", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(20));

    mvc.perform(get("/api/attractions").param("city", "杭州").param("limit", "-5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(20));
  }

  @Test
  @DisplayName("按名称查询：精确名优先命中")
  void searchByName() throws Exception {
    mvc.perform(get("/api/attractions").param("name", "西湖"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.items[0].name").value("西湖"));
  }

  @Test
  @DisplayName("按名称模糊查询：关键词包含即可命中")
  void searchByNamePartially() throws Exception {
    mvc.perform(get("/api/attractions").param("name", "故宫"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.items[0].name").value("故宫博物院"));
  }

  @Test
  @DisplayName("结果保留 ID 与来源")
  void resultsKeepIdAndSource() throws Exception {
    MvcResult result = mvc.perform(get("/api/attractions").param("city", "北京"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode items = body(result).get("items");
    assertThat(items).isNotEmpty();
    for (JsonNode item : items) {
      assertThat(item.get("id").asText()).isNotBlank();
      assertThat(item.get("source").asText()).isNotBlank();
    }
  }

  @Test
  @DisplayName("选择景点后可查看详情")
  void detailById() throws Exception {
    MvcResult list = mvc.perform(get("/api/attractions").param("city", "杭州"))
        .andExpect(status().isOk()).andReturn();
    String id = body(list).get("items").get(0).get("id").asText();

    mvc.perform(get("/api/attractions/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").isNotEmpty());
  }

  @Test
  @DisplayName("详情 ID 不存在返回 404")
  void detailUnknownId() throws Exception {
    mvc.perform(get("/api/attractions/no-such-id"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("景点不存在"));
  }

  @Test
  @DisplayName("无结果时给出明确提示而不是静默空列表")
  void emptyResultHasMessage() throws Exception {
    mvc.perform(get("/api/attractions").param("city", "不存在的城市"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(0))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未找到")));
  }

  @Test
  @DisplayName("既无 city 也无 name 返回 400")
  void missingQueryParam() throws Exception {
    mvc.perform(get("/api/attractions"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  @DisplayName("占位值规范：ticket=\"暂无\"、level=\"其他\"、伪地址一律视为缺失")
  void placeholdersAreNormalized() throws Exception {
    MvcResult result = mvc.perform(get("/api/attractions").param("name", "灵隐寺"))
        .andExpect(status().isOk()).andReturn();
    JsonNode item = body(result).get("items").get(0);

    assertThat(item.get("ticket").isNull()).as("“暂无”票价应规范为 null").isTrue();
    assertThat(item.get("level").isNull()).as("“其他”不是真实等级").isTrue();
    assertThat(item.get("address").isNull()).as("拼接伪地址应清空").isTrue();
  }

  @Test
  @DisplayName("票价或开放时间未知时明确显示“未知”")
  void unknownFieldsShowUnknownLabel() throws Exception {
    MvcResult result = mvc.perform(get("/api/attractions").param("name", "灵隐寺"))
        .andExpect(status().isOk()).andReturn();
    JsonNode item = body(result).get("items").get(0);

    assertThat(item.get("ticketText").asText()).isEqualTo("未知");
    assertThat(item.get("openTimeText").asText()).isEqualTo("未知");
  }

  @Test
  @DisplayName("已知的票价与开放时间原样返回，不被“未知”覆盖")
  void knownFieldsArePreserved() throws Exception {
    MvcResult result = mvc.perform(get("/api/attractions").param("name", "西湖"))
        .andExpect(status().isOk()).andReturn();
    JsonNode item = body(result).get("items").get(0);

    assertThat(item.get("ticketText").asText()).isEqualTo("免费");
    assertThat(item.get("openTimeText").asText()).isEqualTo("全天开放");
    assertThat(item.get("source").asText()).isEqualTo("OpenStreetMap + Amap");
  }

  @Test
  @DisplayName("知识库状态接口报告可用与统计")
  void statusReportsAvailable() throws Exception {
    mvc.perform(get("/api/attractions/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.count").value(5))
        .andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  @DisplayName("景点查询无需登录：公开只读接口")
  void queryIsPublic() throws Exception {
    // 不携带任何会话，直接查询应成功
    mvc.perform(get("/api/attractions").param("city", "北京"))
        .andExpect(status().isOk());
  }
}
