package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 同一个请求可能同时满足多个拒绝条件时的**优先级**。
 *
 * <p>这类顺序不会有人主动去写，它只是代码里几行校验的先后。一旦顺序反了，症状不是崩溃，
 * 而是「该说无权限的时候说版本冲突」「不存在的 id 也返回 403 之外的东西」——
 * 前端按状态码做的提示全部错位，用户看到的解释与真实原因无关。
 *
 * <p>当前实现的顺序是：{@code requireExists} → 参与/板块权限 → 归档 → 版本号。
 * 三者都能同时成立时，先命中的那个决定状态码。这里把它钉住，
 * 将来有人调整校验顺序时，这些用例会先红，而不是等线上反馈。
 *
 * <p>**权限必须先于归档**：非成员与这份行程没有任何关系，不该从「已归档」这种
 * 具体状态里读到任何信息。所以非成员 + 已归档只回 403，不附 {@code archived}。
 */
@DisplayName("错误优先级：不存在 → 无权限 → 已归档 → 版本冲突")
class ErrorPriorityTest extends ApiTestBase {

  private MvcResult putPlan(String id, MockHttpSession session, long version) throws Exception {
    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "杭州");
    plan.put("summary", "西湖");
    return mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(session)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andReturn();
  }

  @Test
  @DisplayName("不存在的 id + 陌生人：先说 404，不泄露「这个 id 有没有人用」之外的权限信息")
  void unknownIdBeatsForbidden() throws Exception {
    MockHttpSession stranger = registerAndLogin();
    String missing = UUID.randomUUID().toString();

    mvc.perform(get("/api/itineraries/" + missing).session(stranger))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("行程不存在"))
        .andExpect(jsonPath("$.id").value(missing));

    assertThat(putPlan(missing, stranger, 1).getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("有成员身份但没给 PLAN 权限 + 版本号也已过期：先说 403，不说版本冲突")
  void forbiddenBeatsVersionConflict() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "权限优先于版本");

    String viewerName = uniqueUsername("viewer");
    register(viewerName, DEFAULT_PASSWORD);
    MockHttpSession viewer = login(viewerName, DEFAULT_PASSWORD);
    long afterAdd = addMember(id, owner, viewerName, List.of("DAILY"));

    // 版本号故意传过期的（成员加入已把版本推到 afterAdd），权限仍应先命中
    MvcResult result = putPlan(id, viewer, afterAdd - 1);

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("无权限")
        .doesNotContain("版本冲突");
  }

  @Test
  @DisplayName("已归档 + 版本号过期：先说「已归档」（409 + archived 标记），不冒充版本冲突")
  void archivedBeatsVersionConflict() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "归档优先于版本");
    long beforeArchive = currentVersion(id, owner);

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archived").value(true));

    MvcResult result = putPlan(id, owner, beforeArchive);

    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    var body = json.readTree(result.getResponse()
        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    // 版本冲突的响应体带 currentVersion，归档的响应体带 archived，两者必须能区分开
    assertThat(body.get("archived").asBoolean()).isTrue();
    assertThat(body.has("currentVersion")).isFalse();
  }

  @Test
  @DisplayName("对照组：只是版本过期（未归档、有权限）时才是版本冲突，并带回当前版本")
  void versionConflictCarriesCurrentVersion() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "版本冲突");
    long stale = currentVersion(id, owner) - 1;

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", Long.toString(stale))
            .contentType("application/json")
            .content("{\"destination\":\"苏州\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.currentVersion").value(stale + 1))
        .andExpect(jsonPath("$.archived").doesNotExist());
  }

  @Test
  @DisplayName("非成员连读都读不到：403 而不是 404，避免用状态码区分「存在」与「不存在」")
  void nonMemberCannotRead() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "别人的行程");
    MockHttpSession stranger = registerAndLogin();

    mvc.perform(get("/api/itineraries/" + id).session(stranger))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("无权限"));
  }

  @Test
  @DisplayName("非成员 + 已归档：仍是 403，不因为「归档」提前泄露行程状态")
  void nonMemberOnArchivedIsForbiddenNotArchived() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "已归档且与你无关");
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archived").value(true));
    MockHttpSession stranger = registerAndLogin();

    MvcResult result = putPlan(id, stranger, 1);

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    var body = json.readTree(result.getResponse()
        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(body.get("error").asText()).isEqualTo("无权限");
    // 归档的响应体带 archived，这里必须没有——有的话就等于告诉外人「这行程已定稿」
    assertThat(body.has("archived")).isFalse();
  }

  @Test
  @DisplayName("非成员退出已归档行程：404「成员不存在」，不是 409「已归档」")
  void nonMemberLeaveOnArchivedIsNotFound() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "已归档，外人退出");
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());
    MockHttpSession stranger = registerAndLogin();

    MvcResult result = mvc.perform(post("/api/itineraries/" + id + "/leave").session(stranger))
        .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("成员不存在")
        .doesNotContain("已归档");
  }
}
