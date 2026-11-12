package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.application.intent.IntentParser;
import com.travelassistant.server.application.intent.IntentResolution;
import com.travelassistant.server.application.intent.TravelIntent;
import com.travelassistant.server.infrastructure.security.SecurityConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 自然语言解析接口的 HTTP 契约：入参校验、响应字段、登录要求。
 * 解析规则本身由 {@code IntentParserTest} 覆盖，这里是切片，不重复。
 */
@WebMvcTest(IntentController.class)
@Import(SecurityConfiguration.class)
@DisplayName("自然语言解析接口")
class IntentControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private IntentParser parser;

  private static IntentResolution sample() {
    return new IntentResolution("下周六去武当山玩四天", TravelIntent.PLAN_DRAFT, "武当山", true,
        "十堰", "武当山", "按所在城市 十堰 检索", List.of(), 4, 3, new BigDecimal("6000"),
        "CNY", List.of("人文"), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 17),
        "high", "下周六出发玩四天", "llm", List.of(), null, List.of());
  }

  @Test
  @DisplayName("解析成功：意图、槽位与城市一起返回")
  void parseReturnsSlots() throws Exception {
    when(parser.parse(anyString(), any(), any())).thenReturn(sample());

    mvc.perform(post("/api/intent/parse").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"下周六去武当山玩四天\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intent").value("PLAN_DRAFT"))
        .andExpect(jsonPath("$.destination").value("武当山"))
        .andExpect(jsonPath("$.resolved").value(true))
        .andExpect(jsonPath("$.city").value("十堰"))
        .andExpect(jsonPath("$.days").value(4))
        .andExpect(jsonPath("$.travelers").value(3))
        .andExpect(jsonPath("$.preferences[0]").value("人文"))
        .andExpect(jsonPath("$.departDate").value("2026-11-14"))
        .andExpect(jsonPath("$.source").value("llm"));
  }

  @Test
  @DisplayName("带上下文：历史与已确认槽位原样交给解析器")
  void historyAndStateAreForwarded() throws Exception {
    when(parser.parse(anyString(), any(), any())).thenReturn(sample());

    mvc.perform(post("/api/intent/parse").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"text":"有没有那种很小众的馆子",
                 "history":[{"role":"user","text":"武汉有什么好吃的"},
                            {"role":"assistant","text":"在武汉找到 30 家可以去的餐馆。"}],
                 "state":{"city":"武汉","days":3}}
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<List<ConversationTurn>> turns = ArgumentCaptor.captor();
    ArgumentCaptor<ConversationState> state = ArgumentCaptor.captor();
    verify(parser).parse(anyString(), turns.capture(), state.capture());
    assertThat(turns.getValue()).hasSize(2);
    assertThat(turns.getValue().get(1).fromUser()).isFalse();
    assertThat(state.getValue().city()).isEqualTo("武汉");
    assertThat(state.getValue().days()).isEqualTo(3);
  }

  @Test
  @DisplayName("text 为空：400 并说明要传什么")
  void blankTextIsRejected() throws Exception {
    mvc.perform(post("/api/intent/parse").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("完全没有请求体：同样 400，不是 500")
  void missingBodyIsRejected() throws Exception {
    mvc.perform(post("/api/intent/parse").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("未登录：401，避免匿名消耗模型调用")
  void requiresLogin() throws Exception {
    mvc.perform(post("/api/intent/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"武汉三天\"}"))
        .andExpect(status().isUnauthorized());
  }
}
