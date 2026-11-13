package com.travelassistant.server.security;

import com.travelassistant.server.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F1：Provider 管理端点独立管理令牌鉴权。
 *
 * <p>管理操作（reload/probe/shadow/activate）与准入报告（admission）不再对任意登录用户开放，
 * 改为只认环境变量注入的 X-Admin-Token，与用户注册/会话解耦；令牌常量时间比对。
 */
@DisplayName("Provider 管理端点管理令牌鉴权（F1）")
@TestPropertySource(properties = "travel.agent.admin-token=test-admin-token-xyz")
class AdminEndpointSecurityTest extends ApiTestBase {

  private static final String TOKEN = "test-admin-token-xyz";

  @Test
  @DisplayName("登录用户但无管理令牌 → 403（修复前任意登录用户可调）")
  void loggedInWithoutTokenIsForbidden() throws Exception {
    MockHttpSession user = registerAndLogin();
    mvc.perform(post("/api/agent/providers/reload").session(user))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/agent/providers/any-id/admission").session(user))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/agent/providers/any-id/probe").session(user))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("错误管理令牌 → 403")
  void wrongTokenIsForbidden() throws Exception {
    mvc.perform(post("/api/agent/providers/reload").header("X-Admin-Token", "wrong-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("匿名 + 正确管理令牌 → 200（与用户会话解耦，无需注册登录）")
  void anonymousWithValidTokenIsAllowed() throws Exception {
    mvc.perform(post("/api/agent/providers/reload").header("X-Admin-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reloaded").value(true));
  }

  @Test
  @DisplayName("正确令牌过门禁：admission 未知 id 返回 404 而非 403")
  void validTokenPassesGateForAdmission() throws Exception {
    mvc.perform(get("/api/agent/providers/no-such-id/admission").header("X-Admin-Token", TOKEN))
        .andExpect(status().isNotFound());
  }
}