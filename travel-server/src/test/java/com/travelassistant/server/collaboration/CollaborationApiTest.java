package com.travelassistant.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * V2 第 4 节「协作记录与留言」。
 *
 * <p>重点验证三件事：留言不推动版本号、修改记录由服务端产生且倒序、
 * 非成员碰不到别人的讨论。
 */
@DisplayName("V2-4 协作记录与留言")
class CollaborationApiTest extends ApiTestBase {

  @Test
  @DisplayName("发表留言：作者取服务端会话，内容去首尾空白")
  void commentIsCreatedWithServerSideAuthor() throws Exception {
    String name = uniqueUsername("collab");
    MockHttpSession session = registerAndLogin(name, DEFAULT_PASSWORD);
    String id = createItinerary(session, "留言");

    mvc.perform(post("/api/itineraries/" + id + "/comments")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("content", "  明早八点出发  "))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author").value(name))
        .andExpect(jsonPath("$.content").value("明早八点出发"));

    assertThat(comments(id, session)).hasSize(1);
  }

  @Test
  @DisplayName("留言不改变行程版本号")
  void commentDoesNotBumpVersion() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "版本不受留言影响");
    long before = currentVersion(id, session);

    postComment(id, session, "随便说一句");

    assertThat(currentVersion(id, session)).isEqualTo(before);
  }

  @Test
  @DisplayName("空留言与超长留言都被拒绝")
  void invalidCommentRejected() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "非法留言");

    mvc.perform(post("/api/itineraries/" + id + "/comments")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("content", "   "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不能为空")));

    String tooLong = "字".repeat(2001);
    mvc.perform(post("/api/itineraries/" + id + "/comments")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("content", tooLong))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不能超过")));
  }

  @Test
  @DisplayName("非成员既看不到留言，也不能留言")
  void outsiderIsForbidden() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "私有讨论");
    postComment(id, owner, "只有成员能看");

    MockHttpSession stranger = registerAndLogin();
    mvc.perform(get("/api/itineraries/" + id + "/comments").session(stranger))
        .andExpect(status().isForbidden());

    mvc.perform(post("/api/itineraries/" + id + "/comments")
            .session(stranger)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("content", "我也要说话"))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("成员可以看留言并留言")
  void memberCanComment() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "共同讨论");
    String memberName = uniqueUsername("mate");
    register(memberName, DEFAULT_PASSWORD);
    addMember(id, owner, memberName, List.of("DAILY"));

    MockHttpSession member = login(memberName, DEFAULT_PASSWORD);
    postComment(id, member, "第二天想加个灵隐寺");

    assertThat(comments(id, owner)).hasSize(1);
    assertThat(comments(id, member)).hasSize(1);
  }

  @Test
  @DisplayName("修改记录：创建与各板块写入都被记录，最近的在前")
  void changelogRecordsWritesInReverseOrder() throws Exception {
    String name = uniqueUsername("collab");
    MockHttpSession session = registerAndLogin(name, DEFAULT_PASSWORD);
    String id = createItinerary(session, "记录");

    long version = currentVersion(id, session);
    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "上海");
    plan.put("summary", "改去上海");
    plan.put("notes", "");
    plan.put("departDate", "2026-12-01");
    plan.put("returnDate", "2026-12-03");
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isOk());

    JsonNode log = changelog(id, session, 20);
    assertThat(log.size()).isGreaterThanOrEqualTo(2);

    // 倒序：最近一条是 PLAN，最早一条是创建
    assertThat(log.get(0).get("section").asText()).isEqualTo("PLAN");
    assertThat(log.get(0).get("actor").asText()).isEqualTo(name);
    assertThat(log.get(0).get("summary").asText())
        .contains("目的地改为").contains("上海");
    assertThat(log.get(log.size() - 1).get("section").asText()).isEqualTo("ITINERARY");
  }

  @Test
  @DisplayName("修改记录不含客户端提交的伪造内容：只由服务端产生")
  void changelogIgnoresClientPayload() throws Exception {
    String name = uniqueUsername("collab");
    MockHttpSession session = registerAndLogin(name, DEFAULT_PASSWORD);
    String id = createItinerary(session, "防伪造");
    long version = currentVersion(id, session);

    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "杭州");
    plan.put("summary", "正常修改");
    plan.put("notes", "");
    plan.put("departDate", "2026-11-10");
    plan.put("returnDate", "2026-11-12");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isOk());

    JsonNode log = changelog(id, session, 20);
    log.forEach(entry -> assertThat(entry.get("actor").asText())
        .as("操作者必须来自会话")
        .isEqualTo(name));
  }

  @Test
  @DisplayName("删除行程后，它的留言与修改记录一并清掉")
  void deletingItineraryPurgesCollaboration() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createItinerary(session, "待删除");
    postComment(id, session, "删除前留一句");

    mvc.perform(delete("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk());

    // 行程没了，接口回到 404，说明该行程的协作数据也已不可达
    mvc.perform(get("/api/itineraries/" + id + "/comments").session(session))
        .andExpect(status().isNotFound());
  }

  // ---- 辅助 ----

  private void postComment(String id, MockHttpSession session, String content) throws Exception {
    mvc.perform(post("/api/itineraries/" + id + "/comments")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("content", content))))
        .andExpect(status().isCreated());
  }

  private JsonNode comments(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/comments").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private JsonNode changelog(String id, MockHttpSession session, int limit) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/changelog")
            .session(session)
            .param("limit", Integer.toString(limit)))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
