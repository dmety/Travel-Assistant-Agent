package com.travelassistant.server.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册登录接口验收测试（对应需求任务 #2，P0）。
 *
 * <p>完成标准：新账号能登录；重复用户名和错误密码有提示；
 * 退出后不能访问个人方案；密码不明文保存。
 */
@DisplayName("任务 #2 注册登录")
class AccountApiTest extends ApiTestBase {

  // ---- 注册 ----

  @Test
  @DisplayName("注册新账号成功，返回用户名且不泄露密码")
  void registerReturnsCreatedWithoutLeakingPassword() throws Exception {
    String username = uniqueUsername("alice");
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", DEFAULT_PASSWORD))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString(DEFAULT_PASSWORD))));
  }

  @Test
  @DisplayName("重复用户名注册返回 409 并说明原因")
  void duplicateUsernameIsRejected() throws Exception {
    String username = uniqueUsername("dup");
    register(username, DEFAULT_PASSWORD);

    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", DEFAULT_PASSWORD))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("用户名少于 3 位返回 400")
  void tooShortUsernameIsRejected() throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "ab", "password", DEFAULT_PASSWORD))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("用户名含非法字符返回 400")
  void illegalCharacterUsernameIsRejected() throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "bad name!", "password", DEFAULT_PASSWORD))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("密码不足 8 位返回 400")
  void weakPasswordIsRejected() throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", uniqueUsername("weak"), "password", "1234567"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("空白用户名/密码由控制器层 Bean Validation 拦截（P8：@Valid 接线，声明的约束真正生效）")
  void blankCredentialsRejectedByBeanValidation() throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "", "password", DEFAULT_PASSWORD))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("用户名不能为空"));
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(
                Map.of("username", uniqueUsername("blankpw"), "password", ""))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("密码不能为空"));
  }

  @Test
  @DisplayName("密码以 BCrypt 哈希落盘，文件中不存在明文")
  void passwordIsStoredAsBcryptHash() throws Exception {
    String username = uniqueUsername("hash");
    register(username, DEFAULT_PASSWORD);

    Path accounts = DATA_DIR.resolve("accounts.json");
    assertThat(Files.exists(accounts)).as("账号文件应已创建").isTrue();
    String raw = Files.readString(accounts, StandardCharsets.UTF_8);

    assertThat(raw).as("文件中不得出现密码明文").doesNotContain(DEFAULT_PASSWORD);
    JsonNode records = json.readTree(raw);
    JsonNode target = null;
    for (JsonNode record : records) {
      if (username.equals(record.get("username").asText())) {
        target = record;
      }
    }
    assertThat(target).as("应能在文件中找到该账号").isNotNull();
    assertThat(target.get("passwordHash").asText())
        .as("密码应为 BCrypt 格式")
        .startsWith("$2a$");
  }

  // ---- 登录 ----

  @Test
  @DisplayName("正确凭据登录成功并建立会话")
  void loginWithCorrectCredentialsSucceeds() throws Exception {
    String username = uniqueUsername("login");
    register(username, DEFAULT_PASSWORD);

    mvc.perform(post("/api/login")
            .param("username", username)
            .param("password", DEFAULT_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.username").value(username));
  }

  @Test
  @DisplayName("错误密码登录返回 401，提示不区分账号是否存在")
  void loginWithWrongPasswordFails() throws Exception {
    String username = uniqueUsername("wrongpw");
    register(username, DEFAULT_PASSWORD);

    MvcResult wrongPassword = mvc.perform(post("/api/login")
            .param("username", username)
            .param("password", "WrongPassword!"))
        .andExpect(status().isUnauthorized())
        .andReturn();

    MvcResult unknownUser = mvc.perform(post("/api/login")
            .param("username", uniqueUsername("ghost"))
            .param("password", DEFAULT_PASSWORD))
        .andExpect(status().isUnauthorized())
        .andReturn();

    String a = wrongPassword.getResponse().getContentAsString(StandardCharsets.UTF_8);
    String b = unknownUser.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(a).as("密码错误与账号不存在应返回同样的提示，避免枚举账号").isEqualTo(b);
  }

  @Test
  @DisplayName("未登录访问 /api/account/me 返回 401")
  void meRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/account/me")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("登录后可查询自身用户名与角色")
  void meReturnsCurrentUser() throws Exception {
    String username = uniqueUsername("me");
    MockHttpSession session = registerAndLogin(username, DEFAULT_PASSWORD);

    mvc.perform(get("/api/account/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
  }

  @Test
  @DisplayName("退出后同一会话不能再访问个人数据")
  void logoutInvalidatesSession() throws Exception {
    MockHttpSession session = registerAndLogin();

    mvc.perform(get("/api/account/me").session(session)).andExpect(status().isOk());

    mvc.perform(post("/api/logout").session(session)).andExpect(status().isOk());

    // 退出后服务端会话失效，携带旧会话再访问应被拒
    mvc.perform(get("/api/account/me").session(session)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/itineraries").session(session)).andExpect(status().isUnauthorized());
  }
}
