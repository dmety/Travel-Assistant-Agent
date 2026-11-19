package com.travelassistant.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多用户隔离回归：身份漂移探针、公开状态接口、越权导出、成员留言互通。
 *
 * <p>对应真实复现结论：后端行程/协作的归属校验本身是完整的，缺陷集中在
 * 「公开接口泄露全局用量」「produces=text/plain 让 403 变 500」「SPA 不轮询留言」。
 * 这里把前两条固化成断言，并补一条成员之间留言互通的后端回归。
 */
@DisplayName("多用户隔离与身份漂移回归")
class MultiUserIsolationTest extends ApiTestBase {

  @Test
  @DisplayName("匿名状态接口只报能力，不返回全进程模型用量")
  void statusDoesNotLeakUsage() throws Exception {
    mvc.perform(get("/api/agent/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelConfigured").exists())
        .andExpect(jsonPath("$.capabilities").isArray())
        .andExpect(jsonPath("$.usage").doesNotExist());
  }

  @Test
  @DisplayName("认证响应带 X-Auth-User；匿名响应不带，不给账号枚举线索")
  void identityHeaderOnlyForAuthenticated() throws Exception {
    String user = uniqueUsername("isol");
    MockHttpSession session = registerAndLogin(user, DEFAULT_PASSWORD);
    mvc.perform(get("/api/account/me").session(session))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Auth-User", user));
    mvc.perform(get("/api/account/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("X-Auth-User"));
  }

  @Test
  @DisplayName("非成员导出他人行程返回 403 JSON，而不是 500 空响应")
  void nonMemberExportIsForbiddenNotServerError() throws Exception {
    String owner = uniqueUsername("owner");
    String stranger = uniqueUsername("stranger");
    MockHttpSession ownerSession = registerAndLogin(owner, DEFAULT_PASSWORD);
    MockHttpSession strangerSession = registerAndLogin(stranger, DEFAULT_PASSWORD);
    String id = createItinerary(ownerSession, "仅创建者可见");

    // 关键点：export 声明 produces=text/plain，异常若走 ResponseEntity<Map> 会协商失败变 500
    mvc.perform(get("/api/itineraries/" + id + "/export").session(strangerSession)
            .accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("无权限"));

    mvc.perform(get("/api/itineraries/" + id + "/export").session(ownerSession)
            .accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("成员之间留言互通：A 发 B 可见，B 回 A 可见")
  void membersSeeEachOthersComments() throws Exception {
    String owner = uniqueUsername("chatowner");
    String member = uniqueUsername("chatmember");
    MockHttpSession ownerSession = registerAndLogin(owner, DEFAULT_PASSWORD);
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    String id = createItinerary(ownerSession, "协作讨论");

    JsonNode invitation = json.readTree(mvc.perform(post("/api/itineraries/" + id + "/invitations")
            .session(ownerSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + member + "\",\"editSections\":[]}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    mvc.perform(post("/api/invitations/" + invitation.get("id").asText() + "/accept")
            .session(memberSession))
        .andExpect(status().isOk());

    mvc.perform(post("/api/itineraries/" + id + "/comments").session(ownerSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"A 的第一条留言\"}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/api/itineraries/" + id + "/comments").session(memberSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].author").value(owner))
        .andExpect(jsonPath("$[0].content").value("A 的第一条留言"));

    mvc.perform(post("/api/itineraries/" + id + "/comments").session(memberSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"B 的回复\"}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/api/itineraries/" + id + "/comments").session(ownerSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[1].content").value("B 的回复"));
  }

}
