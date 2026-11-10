package com.travelassistant.server.interfaces.rest;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.intent.IntentParser;
import com.travelassistant.server.application.intent.IntentResolution;
import com.travelassistant.server.application.intent.TravelIntent;
import com.travelassistant.server.infrastructure.security.SecurityConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        "high", "下周六出发玩四天", "llm", List.of());
  }

  @Test
  @DisplayName("解析成功：意图、槽位与城市一起返回")
  void parseReturnsSlots() throws Exception {
    when(parser.parse(anyString())).thenReturn(sample());

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
