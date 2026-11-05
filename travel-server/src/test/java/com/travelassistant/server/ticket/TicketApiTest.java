package com.travelassistant.server.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.V2ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * V2「票务 Agent：购票辅助」。
 *
 * <p>这一组用例的核心不是「能不能查到入口」，而是**查不到时的说法**：
 * 不猜测链接、不假装有票、不把「未核验」说成「有票」。
 */
@DisplayName("V2 票务购票辅助")
class TicketApiTest extends V2ApiTestBase {

  @Test
  @DisplayName("已知景点：返回经核验的官方入口、票种与规则")
  void returnsConfiguredChannel() throws Exception {
    MvcResult result = mvc.perform(get("/api/tickets").param("name", "西湖"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CHANNEL_FOUND"))
        .andExpect(jsonPath("$.stockVerified").value(false))
        .andExpect(jsonPath("$.channels[0].channelName").value("西湖风景名胜区官方预约"))
        .andExpect(jsonPath("$.channels[0].officialUrl").value("https://www.hzwestlake.gov.cn/ticket"))
        .andExpect(jsonPath("$.channels[0].ticketTypes.length()").value(2))
        .andExpect(jsonPath("$.channels[0].rules[0]").value("需实名预约"))
        .andReturn();

    JsonNode body = read(result);
    assertThat(body.get("notice").asText()).contains("余票未核验");
    assertThat(body.get("source").asText()).isEqualTo("景区官网公示页面，人工核对");
    assertThat(body.path("queriedAt").asText()).isNotBlank();
  }

  @Test
  @DisplayName("票价缺失时保持未知，不补成 0")
  void missingPriceStaysUnknown() throws Exception {
    MvcResult result = mvc.perform(get("/api/tickets").param("name", "西湖"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode second = read(result).path("channels").path(0).path("ticketTypes").path(1);
    assertThat(second.get("name").asText()).isEqualTo("环湖观光车");
    assertThat(second.path("price").isNull()).isTrue();
    assertThat(second.get("priceNote").asText()).contains("现场公示");
  }

  @Test
  @DisplayName("未收录的景点：明确说明自行前往官方渠道，不生成链接")
  void unknownAttractionHasNoChannel() throws Exception {
    MvcResult result = mvc.perform(get("/api/tickets").param("name", "不存在的景区"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_VERIFIED_CHANNEL"))
        .andExpect(jsonPath("$.channels.length()").value(0))
        .andExpect(jsonPath("$.stockVerified").value(false))
        .andReturn();

    assertThat(read(result).get("notice").asText())
        .contains("未找到经核验的官方购票入口")
        .contains("不生成猜测链接");
  }

  @Test
  @DisplayName("地址协议非法的入口在加载时被丢弃，该景点回到「无入口」")
  void invalidUrlIsDropped() throws Exception {
    mvc.perform(get("/api/tickets").param("name", "灵隐寺"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_VERIFIED_CHANNEL"))
        .andExpect(jsonPath("$.channels.length()").value(0));
  }

  @Test
  @DisplayName("未记录核验日期的入口仍然给出，但明确提示需再次确认")
  void unverifiedChannelIsFlagged() throws Exception {
    MvcResult result = mvc.perform(get("/api/tickets").param("name", "千岛湖"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CHANNEL_FOUND"))
        .andReturn();

    String notice = read(result).get("notice").asText();
    assertThat(notice).contains("余票未核验").contains("核验日期");
  }

  @Test
  @DisplayName("按景点 id 查询，并从景点库补齐名称")
  void lookupByAttractionId() throws Exception {
    mvc.perform(get("/api/tickets").param("attractionId", "t-hz-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CHANNEL_FOUND"))
        .andExpect(jsonPath("$.attractionName").value("西湖"));
  }

  @Test
  @DisplayName("既没给 id 也没给名称：400")
  void missingQueryIsRejected() throws Exception {
    mvc.perform(get("/api/tickets"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("景点")));
  }

  @Test
  @DisplayName("入口清单只包含通过校验的条目")
  void channelsEndpointListsUsableOnly() throws Exception {
    MvcResult result = mvc.perform(get("/api/tickets/channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2))
        .andReturn();

    JsonNode body = read(result);
    assertThat(body.get("verificationNote").asText()).contains("不生成链接");
    body.get("channels").forEach(c ->
        assertThat(c.get("officialUrl").asText()).startsWith("https://"));
  }
}
