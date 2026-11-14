package com.travelassistant.server.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.chat.ChatAgent;
import com.travelassistant.server.application.chat.ChatReply;
import com.travelassistant.server.application.chat.ChatTools;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.infrastructure.security.SecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 对话接口的 HTTP 契约：入参、登录要求、响应形状。
 * Agent 的推理过程由 {@code ChatAgentTest} 与 {@code ChatToolsTest} 覆盖，这里不重复。
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfiguration.class)
@DisplayName("对话接口")
class ChatControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ChatAgent agent;

  private static ChatReply sample() {
    return new ChatReply("在武汉找到 8 家可以去的餐馆。",
        List.of(new ChatTools.Card("weather", null, java.util.Map.of("city", "武汉"))),
        List.of(new ChatReply.ToolTrace("find_food", "找餐馆", true)),
        new ConversationState("武汉", null, null, null, List.of()), "agent", null);
  }

  @Test
  @DisplayName("正常回答：回复、卡片、查询痕迹、沿用槽位一起返回")
  void returnsReply() throws Exception {
    when(agent.chat(anyString(), any(), any())).thenReturn(sample());

    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"武汉有什么好吃的\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("在武汉找到 8 家可以去的餐馆。"))
        .andExpect(jsonPath("$.source").value("agent"))
        .andExpect(jsonPath("$.cards[0].type").value("weather"))
        .andExpect(jsonPath("$.trace[0].label").value("找餐馆"))
        .andExpect(jsonPath("$.state.city").value("武汉"));
  }

  @Test
  @DisplayName("空 message：400 并说明要传什么")
  void blankMessageIsRejected() throws Exception {
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("未登录：401，避免匿名消耗模型配额")
  void requiresLogin() throws Exception {
    mvc.perform(post("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"武汉三天\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("message 超过 2000 字：400，挡住超长请求打模型（F5）")
  void oversizedMessageIsRejected() throws Exception {
    String tooLong = "x".repeat(2001);
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("history 超过 50 轮：只把最近 50 轮送给模型，不全量转发（F5）")
  void historyIsCappedToRecentTurns() throws Exception {
    when(agent.chat(anyString(), any(), any())).thenReturn(sample());
    StringBuilder body = new StringBuilder("{\"message\":\"继续\",\"history\":[");
    for (int i = 0; i < 60; i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append("{\"role\":\"user\",\"text\":\"第").append(i).append("句\"}");
    }
    body.append("]}");

    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toString()))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ConversationTurn>> captor = ArgumentCaptor.forClass(List.class);
    verify(agent).chat(anyString(), captor.capture(), any());
    List<ConversationTurn> sent = captor.getValue();
    assertEquals(50, sent.size());
    // 保留的是最近 50 轮（第 10..59 句），最早的 10 句被丢弃。
    assertEquals("第59句", sent.get(sent.size() - 1).text());
    assertEquals("第10句", sent.get(0).text());
  }
}
