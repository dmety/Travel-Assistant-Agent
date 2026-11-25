package com.travelassistant.server.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.chat.ChatReply;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 契约 1.1：核验声明从工具调用逐层汇总到回复，不在展示层重新生成。 */
@DisplayName("对话回复的核验声明透传")
class ChatReplyTest {

  @Test
  @DisplayName("六参构造按工具调用顺序汇总，只取成功调用并去重")
  void aggregatesFromTrace() {
    var reply = new ChatReply("ok", List.of(), List.of(
        new ChatReply.ToolTrace("find_food", "找餐馆", true, null,
            List.of("GENERAL_DISH_LIST", "GEOCODE_REFERENCE")),
        new ChatReply.ToolTrace("list_attractions", "查景点", true, null,
            List.of("GENERAL_DISH_LIST", "OFFICIAL_NOTICE")),
        new ChatReply.ToolTrace("book_ticket", "查票务", false, null,
            List.of("NO_REALTIME_STOCK"))),
        null, "agent", null);

    assertThat(reply.disclaimers()).containsExactly(
        "GENERAL_DISH_LIST", "GEOCODE_REFERENCE", "OFFICIAL_NOTICE");
  }

  @Test
  @DisplayName("显式声明优先，不再从 trace 追加第二份")
  void explicitDisclaimersWin() {
    var reply = new ChatReply("ok", List.of(), List.of(
        new ChatReply.ToolTrace("get_weather", "查天气", true, null,
            List.of("FORECAST_NOT_REALTIME"))),
        null, "agent", null, List.of("UNVERIFIED_TICKET"));

    assertThat(reply.disclaimers()).containsExactly("UNVERIFIED_TICKET");
  }

  @Test
  @DisplayName("旧四参 ToolTrace 构造仍可用，声明缺省为空")
  void legacyTraceConstructor() {
    var trace = new ChatReply.ToolTrace("resolve_place", "核实地名", true, "已核实");
    assertThat(trace.disclaimers()).isEmpty();
    assertThat(trace.summary()).isEqualTo("已核实");
  }
}
