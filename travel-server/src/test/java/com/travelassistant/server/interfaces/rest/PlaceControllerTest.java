package com.travelassistant.server.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.infrastructure.security.SecurityConfiguration;
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
 * 地名识别接口的 HTTP 契约：入参校验、响应字段、登录要求。
 * 识别规则本身由 {@code PlaceResolverTest} 覆盖，这里是切片，不重复。
 */
@WebMvcTest(PlaceController.class)
@Import(SecurityConfiguration.class)
@DisplayName("地名识别接口")
class PlaceControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private PlaceResolver resolver;

  @Test
  @DisplayName("识别成功：返回城市、来源与说明")
  void resolveReturnsCity() throws Exception {
    when(resolver.resolve(anyString(), any())).thenReturn(
        PlaceResolution.hit("黄梅", "黄冈", null, "湖北省", "county", "llm",
            "黄梅县属湖北省黄冈市，按 黄冈 检索"));

    mvc.perform(post("/api/places/resolve").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"黄梅\",\"candidates\":[\"黄梅\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolved").value(true))
        .andExpect(jsonPath("$.city").value("黄冈"))
        .andExpect(jsonPath("$.source").value("llm"))
        .andExpect(jsonPath("$.note").value("黄梅县属湖北省黄冈市，按 黄冈 检索"));
  }

  @Test
  @DisplayName("识别失败：resolved=false 并带可点的相近城市")
  void missReturnsAlternatives() throws Exception {
    when(resolver.resolve(anyString(), any())).thenReturn(
        PlaceResolution.miss("黄梅", "没认出「黄梅」", List.of("黄冈", "黄石")));

    mvc.perform(post("/api/places/resolve").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"黄梅\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolved").value(false))
        .andExpect(jsonPath("$.alternatives.length()").value(2))
        .andExpect(jsonPath("$.alternatives[0]").value("黄冈"));
  }

  @Test
  @DisplayName("什么都没传：400 并说明要传什么")
  void blankInputIsRejected() throws Exception {
    mvc.perform(post("/api/places/resolve").with(user("tester"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("未登录：401，避免匿名消耗模型调用")
  void requiresLogin() throws Exception {
    mvc.perform(post("/api/places/resolve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\":\"黄梅\"}"))
        .andExpect(status().isUnauthorized());
  }
}
