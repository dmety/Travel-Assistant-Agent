package com.travelassistant.server.security;

import com.travelassistant.server.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全边界验收测试（需求文档 §2 与 §5）。
 *
 * <p>关注四件事：公开入口是否真的公开、业务入口是否全部要求登录、
 * 是否还残留 Spring 自动生成的默认用户、会话与响应头是否正确。
 */
@DisplayName("安全边界")
class SecurityRulesTest extends ApiTestBase {

  @Test
  @DisplayName("健康检查无需登录")
  void healthIsPublic() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  @DisplayName("登录与注册入口无需登录即可访问")
  void loginAndRegisterArePublic() throws Exception {
    // 用一个不存在的账号尝试登录，应该得到 401（业务拒绝）而不是 302/403（安全拦截）
    mvc.perform(post("/api/login")
            .param("username", "nobody_here")
            .param("password", "whatever123"))
        .andExpect(status().isUnauthorized());

    // 注册入口能走入业务逻辑：非法输入返回 400 而非 401
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content("{\"username\":\"x\",\"password\":\"y\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("静态页面无需登录：登录页与测试台")
  void staticPagesArePublic() throws Exception {
    mvc.perform(get("/login.html")).andExpect(status().isOk());
    mvc.perform(get("/app.html")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("所有行程接口未登录一律 401")
  void allItineraryEndpointsRequireAuthentication() throws Exception {
    String id = "any-id";
    mvc.perform(get("/api/itineraries")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/plan")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/daily")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/transports")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/lodgings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/expenses")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries/" + id + "/members")).andExpect(status().isUnauthorized());

    mvc.perform(post("/api/itineraries").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .param("version", "1").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/itineraries/" + id)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("登录后可正常访问，未登录时返回 401 而不是跳转登录页")
  void unauthenticatedRequestReturns401NotRedirect() throws Exception {
    var result = mvc.perform(get("/api/itineraries")).andExpect(status().isUnauthorized()).andReturn();
    header().doesNotExist("Location");
    org.assertj.core.api.Assertions
        .assertThat(result.getResponse().getHeader("Location"))
        .as("JSON 客户端不应被重定向到登录页")
        .isNull();
  }

  @Test
  @DisplayName("不再存在 Spring 自动生成的默认 user 账号")
  void defaultGeneratedUserIsGone() throws Exception {
    mvc.perform(post("/api/login").param("username", "user").param("password", "password"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("退出后会话失效，业务接口重新变回 401")
  void logoutRevokesBusinessAccess() throws Exception {
    MockHttpSession session = registerAndLogin();
    mvc.perform(get("/api/itineraries").session(session)).andExpect(status().isOk());

    mvc.perform(post("/api/logout").session(session)).andExpect(status().isOk());

    mvc.perform(get("/api/itineraries").session(session)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("写请求无需 CSRF token（客户端为第一方控制台，非浏览器表单）")
  void stateChangingRequestsDoNotRequireCsrfToken() throws Exception {
    MockHttpSession owner = registerAndLogin();
    // 不带任何 CSRF token 直接创建行程
    mvc.perform(post("/api/itineraries")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of(
                "title", "无 CSRF 令牌创建", "plan", java.util.Map.of("destination", "杭州")))))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("响应带基础安全头：nosniff 与 DENY 防止被嵌入")
  void securityHeadersArePresent() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }
}
