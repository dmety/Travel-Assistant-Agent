package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 邀请与协作流程（V1 需求 §4）：创建者按账号邀请 → 受邀者接受或拒绝 → 成员可主动退出。
 *
 * <p>重点是「邀请不等于成员」这条边界：发出邀请后受邀者仍看不到行程，
 * 只有接受才产生成员关系，拒绝则什么都不留下。
 */
class ItineraryInvitationTest extends ApiTestBase {

  // ---- 发起与可见性 ----

  @Test
  @DisplayName("发出邀请后受邀者仍不是成员，看不到行程")
  void inviteDoesNotGrantAccess() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "杭州三日游");

    invite(id, owner, inviteeName, List.of("DAILY"), 201);

    mvc.perform(get("/api/itineraries/" + id).session(login(inviteeName, DEFAULT_PASSWORD)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("受邀者能在「我的邀请」里看到行程标题与邀请人")
  void inviteeSeesPendingInvitation() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String ownerName = ownerUsername(owner);
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "青岛看海");

    invite(id, owner, inviteeName, List.of("PLAN", "DAILY"), 201);

    JsonNode pending = pendingOf(inviteeName);
    assertThat(pending).hasSize(1);
    JsonNode first = pending.get(0);
    assertThat(first.get("itineraryId").asText()).isEqualTo(id);
    assertThat(first.get("itineraryTitle").asText()).isEqualTo("青岛看海");
    assertThat(first.get("inviter").asText()).isEqualTo(ownerName);
    assertThat(first.get("invitee").asText()).isEqualTo(inviteeName);
    assertThat(first.get("status").asText()).isEqualTo("PENDING");
    assertThat(sections(first)).containsExactlyInAnyOrder("PLAN", "DAILY");
  }

  @Test
  @DisplayName("创建者没收到自己的邀请，「我的邀请」为空")
  void ownerSeesNoSelfInvitation() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "成都");
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    invite(id, owner, inviteeName, List.of("DAILY"), 201);

    MvcResult result = mvc.perform(get("/api/invitations").session(owner))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(body(result).size()).isZero();
  }

  // ---- 接受 ----

  @Test
  @DisplayName("接受邀请后成为成员，可以查看行程")
  void acceptMakesMember() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "西安");

    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    JsonNode accepted = accept(invId, invitee);

    assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.get("respondedAt").isNull()).isFalse();
    mvc.perform(get("/api/itineraries/" + id).session(invitee)).andExpect(status().isOk());
    assertThat(memberNames(id, owner)).contains(inviteeName);
  }

  @Test
  @DisplayName("接受后仅能编辑被授予的板块，未授予的仍拒绝")
  void acceptedMemberEditsOnlyGrantedSections() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "厦门");

    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    accept(invId, invitee);

    long version = currentVersion(id, owner);
    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(invitee)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(dailySchedule("2026-11-10", "鼓浪屿")))))
        .andExpect(status().isOk());
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(invitee)
            .param("version", Long.toString(currentVersion(id, owner)))
            .contentType("application/json")
            .content("{\"destination\":\"厦门\",\"summary\":\"s\",\"notes\":\"n\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("接受邀请不需要版本号，创建者中途改动也不冲突")
  void acceptIgnoresVersionConflict() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "拉萨");

    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    // 创建者在受邀者接受之前改了一次方案，版本号已推进
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", Long.toString(currentVersion(id, owner)))
            .contentType("application/json")
            .content("{\"destination\":\"拉萨\",\"summary\":\"布达拉宫\",\"notes\":\"高反药\"}"))
        .andExpect(status().isOk());

    assertThat(accept(invId, invitee).get("status").asText()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("重复接受同一邀请返回 409，状态不被覆盖")
  void acceptTwiceConflicts() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "贵阳");
    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);

    accept(invId, invitee);
    mvc.perform(post("/api/invitations/" + invId + "/accept").session(invitee))
        .andExpect(status().isConflict());
  }

  // ---- 拒绝 ----

  @Test
  @DisplayName("拒绝邀请后不成为成员，也不出现在成员列表")
  void rejectLeavesNoMember() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "昆明");

    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    MvcResult rejected = mvc.perform(post("/api/invitations/" + invId + "/reject").session(invitee))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(body(rejected).get("status").asText()).isEqualTo("REJECTED");

    mvc.perform(get("/api/itineraries/" + id).session(invitee))
        .andExpect(status().isForbidden());
    assertThat(memberNames(id, owner)).doesNotContain(inviteeName);
    assertThat(pendingOf(inviteeName)).isEmpty();
  }

  @Test
  @DisplayName("拒绝后创建者可以再次邀请，受邀者能重新收到")
  void reinviteAfterReject() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "南京");

    String first = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    mvc.perform(post("/api/invitations/" + first + "/reject").session(invitee))
        .andExpect(status().isOk());

    String second = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    assertThat(second).isNotEqualTo(first);
    assertThat(pendingOf(inviteeName)).hasSize(1);
  }

  // ---- 撤回了 ----

  @Test
  @DisplayName("创建者撤回邀请后受邀者看不到，也不能再接受")
  void cancelRevokesInvitation() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "哈尔滨");

    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    mvc.perform(delete("/api/invitations/" + invId).session(owner))
        .andExpect(status().isOk());

    assertThat(pendingOf(inviteeName)).isEmpty();
    mvc.perform(post("/api/invitations/" + invId + "/accept").session(invitee))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("受邀者不能撤回发给自己的邀请")
  void inviteeCannotCancel() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "兰州");
    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);

    mvc.perform(delete("/api/invitations/" + invId).session(invitee))
        .andExpect(status().isForbidden());
  }

  // ---- 发起方的错误分支 ----

  @Test
  @DisplayName("不能邀请自己")
  void cannotInviteSelf() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "自贡");
    invite(id, owner, ownerUsername(owner), List.of("DAILY"), 400);
  }

  @Test
  @DisplayName("不能邀请未注册账号")
  void cannotInviteUnknownUser() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "敦煌");
    invite(id, owner, "no_such_user_" + System.nanoTime(), List.of("DAILY"), 400);
  }

  @Test
  @DisplayName("不能邀请已是成员的用户")
  void cannotInviteExistingMember() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("mate");
    register(memberName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "桂林");
    addMember(id, owner, memberName, List.of("DAILY"));

    invite(id, owner, memberName, List.of("PLAN"), 400);
  }

  @Test
  @DisplayName("非创建者不能发起邀请")
  void nonOwnerCannotInvite() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("mate");
    String otherName = uniqueUsername("guest");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);
    register(otherName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "洛阳");
    addMember(id, owner, memberName, List.of("PLAN", "DAILY", "TRANSPORT", "LODGING", "EXPENSE"));

    invite(id, member, otherName, List.of("DAILY"), 403);
  }

  @Test
  @DisplayName("重复邀请同一人不新增记录，只更新授权板块")
  void reinvitePendingIsIdempotent() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "银川");

    String first = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    String second = inviteId(id, owner, inviteeName, List.of("PLAN"), 200);

    assertThat(second).isEqualTo(first);
    JsonNode all = invitationsOfItinerary(id, owner);
    assertThat(all).hasSize(1);
    assertThat(sections(all.get(0))).containsExactly("PLAN");
  }

  @Test
  @DisplayName("非受邀者不能接受别人的邀请")
  void strangerCannotAccept() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    MockHttpSession stranger = registerAndLogin();
    String id = createItinerary(owner, "西宁");
    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);

    mvc.perform(post("/api/invitations/" + invId + "/accept").session(stranger))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("接受一个不存在的邀请返回 404")
  void acceptUnknownInvitationIs404() throws Exception {
    MockHttpSession invitee = registerAndLogin();
    mvc.perform(post("/api/invitations/does-not-exist/accept").session(invitee))
        .andExpect(status().isNotFound());
  }

  // ---- 成员退出 ----

  @Test
  @DisplayName("成员主动退出后失去访问权，行程仍在")
  void memberCanLeave() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("mate");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "太原");
    addMember(id, owner, memberName, List.of("DAILY"));

    mvc.perform(post("/api/itineraries/" + id + "/leave").session(member))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/itineraries/" + id).session(member))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/itineraries/" + id).session(owner)).andExpect(status().isOk());
    assertThat(memberNames(id, owner)).doesNotContain(memberName);
  }

  @Test
  @DisplayName("创建者不能退出自己的行程")
  void ownerCannotLeave() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "福州");
    mvc.perform(post("/api/itineraries/" + id + "/leave").session(owner))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("非成员退出返回 404，不会被当作已退出")
  void strangerLeaveIs404() throws Exception {
    MockHttpSession owner = registerAndLogin();
    MockHttpSession stranger = registerAndLogin();
    String id = createItinerary(owner, "合肥");
    mvc.perform(post("/api/itineraries/" + id + "/leave").session(stranger))
        .andExpect(status().isNotFound());
  }

  // ---- 生命周期与落盘 ----

  @Test
  @DisplayName("删除行程后待处理邀请被置为已取消，受邀者列表清空")
  void deleteItineraryCancelsInvitations() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    MockHttpSession invitee = registerAndLogin(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "三亚");
    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);
    assertThat(pendingOf(inviteeName)).hasSize(1);

    mvc.perform(delete("/api/itineraries/" + id).session(owner)).andExpect(status().isOk());

    assertThat(pendingOf(inviteeName)).isEmpty();
    mvc.perform(post("/api/invitations/" + invId + "/accept").session(invitee))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("邀请落盘到 invitations.json，重启后仍可读取")
  void invitationsArePersisted() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String inviteeName = uniqueUsername("guest");
    register(inviteeName, DEFAULT_PASSWORD);
    String id = createItinerary(owner, "呼伦贝尔");
    String invId = inviteId(id, owner, inviteeName, List.of("DAILY"), 201);

    Path file = DATA_DIR.resolve("invitations.json");
    assertThat(Files.exists(file)).as("邀请文件应已创建").isTrue();
    String text = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(text).contains(invId).contains("\"PENDING\"").contains(inviteeName);
  }

  @Test
  @DisplayName("未登录访问邀请接口返回 401")
  void invitationsRequireLogin() throws Exception {
    mvc.perform(get("/api/invitations")).andExpect(status().isUnauthorized());
  }

  // ---- 辅助 ----

  private void invite(String itineraryId, MockHttpSession owner, String invitee,
      List<String> sections, int expectedStatus) throws Exception {
    inviteId(itineraryId, owner, invitee, sections, expectedStatus);
  }

  private String inviteId(String itineraryId, MockHttpSession owner, String invitee,
      List<String> sections, int expectedStatus) throws Exception {
    MvcResult result = mvc.perform(post("/api/itineraries/" + itineraryId + "/invitations")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", invitee,
                "editSections", sections))))
        .andExpect(status().is(expectedStatus))
        .andReturn();
    JsonNode node = body(result);
    return node.has("id") ? node.get("id").asText() : null;
  }

  private JsonNode accept(String invitationId, MockHttpSession invitee) throws Exception {
    MvcResult result = mvc.perform(post("/api/invitations/" + invitationId + "/accept")
            .session(invitee))
        .andExpect(status().isOk())
        .andReturn();
    return body(result);
  }

  private JsonNode pendingOf(String username) throws Exception {
    MvcResult result = mvc.perform(get("/api/invitations").session(login(username,
            DEFAULT_PASSWORD)))
        .andExpect(status().isOk())
        .andReturn();
    return body(result);
  }

  private JsonNode invitationsOfItinerary(String id, MockHttpSession owner) throws Exception {
    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/invitations").session(owner))
        .andExpect(status().isOk())
        .andReturn();
    return body(result);
  }

  private List<String> memberNames(String id, MockHttpSession owner) throws Exception {
    JsonNode it = getItinerary(id, owner);
    List<String> names = new java.util.ArrayList<>();
    it.get("members").forEach(m -> names.add(m.get("username").asText()));
    return names;
  }

  private List<String> sections(JsonNode invitation) {
    List<String> result = new java.util.ArrayList<>();
    invitation.get("editSections").forEach(s -> result.add(s.asText()));
    return result;
  }

  /** 从会话里取当前用户名：详情接口会回显 owner，用它反查最省事。 */
  private String ownerUsername(MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(get("/api/account/me").session(session))
        .andExpect(status().isOk())
        .andReturn();
    return body(result).get("username").asText();
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
