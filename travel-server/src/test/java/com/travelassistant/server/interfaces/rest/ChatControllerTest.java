package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.chat.ChatAgent;
import com.travelassistant.server.application.chat.ChatReply;
import com.travelassistant.server.application.chat.ChatTools;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.infrastructure.security.SecurityConfiguration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
  @DisplayName("空请求体在同步接口中返回 400")
  void nullRequestIsRejected() {
    var response = new ChatController(agent).chat(null);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
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
  @DisplayName("图片请求：仅当前问题附图并交给 Agent")
  void acceptsBoundedImageDataUrl() throws Exception {
    String image = "data:image/png;base64,AA==";
    when(agent.chat(anyString(), any(), any(), any())).thenReturn(sample());
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"这张图是什么\",\"images\":[\"" + image + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("在武汉找到 8 家可以去的餐馆。"));
    verify(agent).chat(eq("这张图是什么"), any(), any(), eq(List.of(image)));
  }

  @Test
  @DisplayName("无效图片 data URL：400 且不调用 Agent")
  void rejectsInvalidImageDataUrl() throws Exception {
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别一下\",\"images\":[\"https://example.invalid/image.png\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("图片必须是 PNG、JPEG、GIF 或 WebP data URL"));
  }

  @Test
  @DisplayName("图片数量、空图片和非法 Base64 都在调用 Agent 前拒绝")
  void rejectsInvalidImagePayloads() throws Exception {
    String image = "data:image/png;base64,AA==";
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[\"" + image + "\",\""
                + image + "\",\"" + image + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("每条问题最多附带 2 张图片"));

    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[null]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("单张图片过大，最多 2 MB"));

    String oversizedUrl = "x".repeat(3_000_001);
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[\"" + oversizedUrl + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("单张图片过大，最多 2 MB"));

    String oversizedPayload = java.util.Base64.getEncoder()
        .encodeToString(new byte[2 * 1024 * 1024 + 1]);
    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[\"data:image/png;base64,"
                + oversizedPayload + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("图片总大小不能超过 4 MB，单张不能超过 2 MB"));

    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[\"data:image/png;base64,A\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("图片编码无效"));

    mvc.perform(post("/api/chat").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别\",\"images\":[\"data:image/png;base64,\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("图片总大小不能超过 4 MB，单张不能超过 2 MB"));
  }

  @Test
  @DisplayName("三字段 ChatRequest 构造器默认不附图")
  void legacyChatRequestDefaultsToNoImages() {
    var request = new ChatController.ChatRequest("继续", List.of(), null);

    assertThat(request.images()).isEmpty();
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

  @Test
  @DisplayName("流式回答：发送进度、增量 token 与完成载荷")
  void streamsProgressTokensAndDone() throws Exception {
    when(agent.chatStream(anyString(), any(), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Consumer<String> onDelta = invocation.getArgument(3);
      onDelta.accept("实时回复片段");
      return sample();
    });

    MvcResult started = mvc.perform(post("/api/chat/stream").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"message\":\"武汉有什么好吃的\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    MvcResult completed = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andReturn();

    String body = completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body).contains("event:progress", "event:token", "event:done",
        "实时回复片段", "在武汉找到 8 家可以去的餐馆。");
    verify(agent).chatStream(eq("武汉有什么好吃的"), any(), any(), any());
  }

  @Test
  @DisplayName("流式回答异常：发送截断后的 error 事件并完成响应")
  void streamFailureIsReportedAndBounded() throws Exception {
    when(agent.chatStream(anyString(), any(), any(), any()))
        .thenThrow(new com.travelassistant.server.application.agent.AgentUnavailableException(
            "x".repeat(220)));

    MvcResult started = mvc.perform(post("/api/chat/stream").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"message\":\"继续\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    MvcResult completed = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn();

    String body = completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body).contains("event:error", "x".repeat(180) + "…")
        .doesNotContain("x".repeat(181));
  }

  @Test
  @DisplayName("流式图片请求：调用含图片的 Agent 重载")
  void streamsImageRequestThroughImageOverload() throws Exception {
    String image = "data:image/png;base64,AA==";
    when(agent.chatStream(anyString(), any(), any(), any(), any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Consumer<String> onDelta = invocation.getArgument(4);
      onDelta.accept("看到了图片");
      return sample();
    });

    MvcResult started = mvc.perform(post("/api/chat/stream").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"message\":\"识别一下\",\"images\":[\"" + image + "\"]}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    MvcResult completed = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn();

    assertThat(completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("event:token", "看到了图片");
    verify(agent).chatStream(eq("识别一下"), any(), any(), eq(List.of(image)), any());
  }

  @Test
  @DisplayName("流式图片请求拒绝无效 data URL")
  void streamRejectsInvalidImageDataUrl() throws Exception {
    mvc.perform(post("/api/chat/stream").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"识别一下\",\"images\":[\"https://example.invalid/image.png\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("流式接口拒绝空请求、空消息和超长消息")
  void streamRejectsInvalidMessages() {
    var controller = new ChatController(agent);
    assertStreamBadRequest(controller, null);
    assertStreamBadRequest(controller, new ChatController.ChatRequest("  ", null, null));
    assertStreamBadRequest(controller,
        new ChatController.ChatRequest("x".repeat(2001), null, null));
  }

  @Test
  @DisplayName("流式 Agent 异常没有可展示信息时使用通用提示")
  void streamFailureUsesFallbackWhenMessageIsBlank() throws Exception {
    when(agent.chatStream(anyString(), any(), any(), any()))
        .thenThrow(new IllegalStateException("  "));

    MvcResult started = mvc.perform(post("/api/chat/stream").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"message\":\"继续\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    MvcResult completed = mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn();

    assertThat(completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("event:error", "回复生成中断，请重试。");
  }

  private static void assertStreamBadRequest(ChatController controller,
      ChatController.ChatRequest request) {
    assertThatThrownBy(() -> controller.chatStream(request, new MockHttpServletResponse()))
        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
  }
}
