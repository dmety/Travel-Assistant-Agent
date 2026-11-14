package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 兜底异常处理（P4）：未预期异常统一 500 + {@code {"error": ...}}，不把堆栈或异常细节回给客户端；
 * 同时**不能**把 Spring 框架异常（404 找不到资源、405 方法不支持）误吞成 500。
 *
 * <p>用 standalone MockMvc + 一个只会抛异常的测试控制器，直接驱动 {@link ApiExceptionHandler}，
 * 不依赖真实业务里恰好存在一个 bug。框架异常由测试控制器主动抛出，模拟 DispatcherServlet 的产物。
 */
@DisplayName("兜底异常：未预期→500 不泄细节，框架异常保留原状态码")
class ApiExceptionHandlerFallbackTest {

  private MockMvc mvc;

  @RestController
  static class ThrowingController {
    @GetMapping("/boom")
    public String boom() {
      throw new IllegalStateException("内部细节：jdbc:mysql://root:secret@db");
    }

    @GetMapping("/boom-null-message")
    public String boomNullMessage() {
      throw new RuntimeException((String) null);
    }

    @GetMapping("/missing-resource")
    public String missingResource() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "no-such-file.txt");
    }

    @GetMapping("/method-guard")
    public String methodGuard() throws HttpRequestMethodNotSupportedException {
      throw new HttpRequestMethodNotSupportedException("DELETE", List.of("GET"));
    }
  }

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(new ApiExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
        .build();
  }

  @Test
  @DisplayName("未预期异常：500 + 统一文案，响应体不含堆栈/异常类名/内部细节")
  void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
    MvcResult result = mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("服务器内部错误，请稍后重试"))
        .andExpect(jsonPath("$.trace").doesNotExist())
        .andExpect(jsonPath("$.exception").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist())
        .andReturn();
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).doesNotContain("jdbc").doesNotContain("secret")
        .doesNotContain("IllegalStateException");
  }

  @Test
  @DisplayName("异常 message 为 null 也不 NPE：仍是 500 + 统一文案")
  void nullMessageStillReturns500() throws Exception {
    mvc.perform(get("/boom-null-message"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("服务器内部错误，请稍后重试"));
  }

  @Test
  @DisplayName("框架 404（NoResourceFoundException）不被兜底吞成 500")
  void frameworkNotFoundKeeps404() throws Exception {
    mvc.perform(get("/missing-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("框架 405（HttpRequestMethodNotSupportedException）不被兜底吞成 500")
  void frameworkMethodNotSupportedKeeps405() throws Exception {
    mvc.perform(get("/method-guard"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error").exists());
  }
}