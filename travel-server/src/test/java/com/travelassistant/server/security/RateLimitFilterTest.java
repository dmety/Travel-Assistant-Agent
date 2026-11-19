package com.travelassistant.server.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F7 限流门禁的端到端验证：专属低配额上下文（登录/注册各 3 次/分钟），
 * 与共享测试上下文隔离，避免批量注册误伤，也避免本测试把配额耗尽影响他人。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("登录/注册限流门禁")
class RateLimitFilterTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper json;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> "target/ratelimit-test-data");
    registry.add("travel.rate-limit.login-per-minute", () -> "3");
    registry.add("travel.rate-limit.register-per-minute", () -> "3");
    registry.add("travel.ai.api-key", () -> "");
    registry.add("travel.amap.key", () -> "");
  }

  @Test
  @DisplayName("同一账号+IP 登录超过配额后返回 429，先于认证")
  void loginIsRateLimited() throws Exception {
    String username = "brute_" + System.nanoTime();
    for (int i = 0; i < 3; i++) {
      mvc.perform(post("/api/login").param("username", username).param("password", "wrong"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/login").param("username", username).param("password", "wrong"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("同一 IP 注册超过配额后返回 429")
  void registerIsRateLimited() throws Exception {
    for (int i = 0; i < 3; i++) {
      String username = "reg_" + System.nanoTime() + "_" + i;
      mvc.perform(post("/api/account/register").contentType("application/json")
              .content(json.writeValueAsString(
                  Map.of("username", username, "password", "Passw0rd!2026"))))
          .andExpect(status().isCreated());
    }
    String fourth = "reg_" + System.nanoTime() + "_4";
    mvc.perform(post("/api/account/register").contentType("application/json")
            .content(json.writeValueAsString(
                Map.of("username", fourth, "password", "Passw0rd!2026"))))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("非登录/注册端点不受限流影响")
  void otherEndpointsAreNotLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
  }
}