package com.travelassistant.server.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V3ApiTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3 第 1 节：候选地点与投票。
 *
 * <p>重点验证三件事：投票不推动行程版本号、投票结果不自动改动行程、
 * 以及「谁能提、谁能投、谁能确认」的权限边界。
 */
@DisplayName("V3 候选与投票")
class CandidateApiTest extends V3ApiTestBase {

  @Test
  @DisplayName("添加候选：提出者取会话，看板立即可见")
  void addCandidateUsesSessionAsProposer() throws Exception {
    MockHttpSession session = registerAndLogin();
    String me = usernameOf(session);
    String id = createTrip(session, "候选", "杭州", "2026-11-10", "2026-11-12", "3000");

    mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("kind", "ATTRACTION", "name", "  西湖  ",
                "city", "杭州"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("西湖"))
        .andExpect(jsonPath("$.proposedBy").value(me))
        .andExpect(jsonPath("$.kind").value("ATTRACTION"));

    JsonNode board = board(id, session);
    assertThat(board.get("candidateCount").asInt()).isEqualTo(1);
    assertThat(board.get("candidates").get(0).get("candidate").get("name").asText())
        .isEqualTo("西湖");
  }

  @Test
  @DisplayName("名称为空或超长：400，不产生候选")
  void invalidNameRejected() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "校验", "杭州", null, null, null);

    mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("name", "   "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("候选名称不能为空"));

    mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("name", "杭".repeat(81)))))
        .andExpect(status().isBadRequest());

    assertThat(board(id, session).get("candidateCount").asInt()).isZero();
  }

  @Test
  @DisplayName("同名同类型重复添加被拒绝；同名不同类型可以共存")
  void duplicateRejectedButDifferentKindAllowed() throws Exception {
    MockHttpSession session = registerAndLogin();
    String id = createTrip(session, "去重", "杭州", null, null, null);
    addCandidate(id, session, "西湖", "ATTRACTION");

    mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("kind", "ATTRACTION", "name", "西湖"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("候选列表里已有「西湖」"));

    // 同名但作为住宿讨论，是另一件事
    addCandidate(id, session, "西湖", "LODGING");
    assertThat(board(id, session).get("candidateCount").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("投票计入看板，并按净支持数排序")
  void votesAreTalliedAndSorted() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "排序", "杭州", null, null, null);
    String aId = addCandidate(id, owner, "甲景点");
    String bId = addCandidate(id, owner, "乙景点");

    vote(id, owner, aId, "SUPPORT");
    JsonNode board = vote(id, owner, bId, "OPPOSE");

    assertThat(tallyOf(board, "甲景点").get("support").asInt()).isEqualTo(1);
    assertThat(tallyOf(board, "甲景点").get("score").asInt()).isEqualTo(1);
    assertThat(tallyOf(board, "乙景点").get("oppose").asInt()).isEqualTo(1);
    assertThat(tallyOf(board, "乙景点").get("score").asInt()).isEqualTo(-1);

    // 净支持高的排前面
    assertThat(board.get("candidates").get(0).get("candidate").get("name").asText())
        .isEqualTo("甲景点");
    assertThat(board.get("leaderId").asText()).isEqualTo(aId);
  }

  @Test
  @DisplayName("重复投票是改票：票数不变，选项被替换")
  void revotingReplacesChoice() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "改票", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");

    vote(id, owner, cid, "SUPPORT");
    JsonNode board = vote(id, owner, cid, "OPPOSE");

    JsonNode tally = tallyOf(board, "西湖");
    assertThat(tally.get("support").asInt()).isZero();
    assertThat(tally.get("oppose").asInt()).isEqualTo(1);
    assertThat(tally.get("abstain").asInt()).isZero();
    assertThat(board.get("totalVotes").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("撤回投票：票数归零；撤回没投过的票返回 400")
  void withdrawVote() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "撤票", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");
    vote(id, owner, cid, "SUPPORT");

    mvc.perform(delete("/api/itineraries/" + id + "/candidates/" + cid + "/votes")
            .session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalVotes").value(0));

    mvc.perform(delete("/api/itineraries/" + id + "/candidates/" + cid + "/votes")
            .session(owner))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("你还没有对这项候选投票，无法撤回"));
  }

  @Test
  @DisplayName("投票不推动行程版本号")
  void votingDoesNotBumpVersion() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "版本", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");
    long before = version(id, owner);

    vote(id, owner, cid, "SUPPORT");

    assertThat(version(id, owner)).isEqualTo(before);
  }

  @Test
  @DisplayName("并列第一时不给结论：leaderId 为空并说明原因")
  void tieHasNoLeader() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "并列", "杭州", null, null, null);
    String aId = addCandidate(id, owner, "甲");
    String bId = addCandidate(id, owner, "乙");

    String member = uniqueUsername("vote");
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of());

    vote(id, owner, aId, "SUPPORT");
    JsonNode board = vote(id, memberSession, bId, "SUPPORT");

    assertThat(board.get("tie").asBoolean()).isTrue();
    assertThat(board.get("leaderId").isNull()).isTrue();
    assertThat(board.get("notes").toString()).contains("并列");
  }

  @Test
  @DisplayName("未投票人数被如实提示")
  void silentParticipantsAreReported() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "缺票", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");

    String member = uniqueUsername("silent");
    registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of());

    JsonNode board = vote(id, owner, cid, "SUPPORT");
    assertThat(board.get("participantCount").asInt()).isEqualTo(2);
    assertThat(board.get("voterCount").asInt()).isEqualTo(1);
    assertThat(board.get("notes").toString()).contains("还有 1 位参与者没有投票");
  }

  @Test
  @DisplayName("非参与者不能看候选、不能投票（403）")
  void outsiderForbidden() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "越权", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");
    MockHttpSession outsider = registerAndLogin();

    mvc.perform(get("/api/itineraries/" + id + "/candidates").session(outsider))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/itineraries/" + id + "/candidates/" + cid + "/votes")
            .session(outsider)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("choice", "SUPPORT"))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("查看权成员也能提候选和投票：提候选不是改方案")
  void readOnlyMemberCanProposeAndVote() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "只读成员", "杭州", null, null, null);
    String member = uniqueUsername("reader");
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of()); // 没有任何板块编辑权

    String cid = addCandidate(id, memberSession, "灵隐寺");
    JsonNode board = vote(id, memberSession, cid, "SUPPORT");

    assertThat(tallyOf(board, "灵隐寺").get("support").asInt()).isEqualTo(1);
    assertThat(tallyOf(board, "灵隐寺").get("candidate").get("proposedBy").asText())
        .isEqualTo(member);
  }

  @Test
  @DisplayName("删除候选：提出者或创建者可删，其他成员不行；选票一并清除")
  void deleteCandidatePermissionsAndVoteCascade() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "删除", "杭州", null, null, null);

    String proposer = uniqueUsername("prop");
    MockHttpSession proposerSession = registerAndLogin(proposer, DEFAULT_PASSWORD);
    addMember(id, owner, proposer, List.of());

    String other = uniqueUsername("other");
    MockHttpSession otherSession = registerAndLogin(other, DEFAULT_PASSWORD);
    addMember(id, owner, other, List.of());

    String cid = addCandidate(id, proposerSession, "西湖");
    vote(id, owner, cid, "SUPPORT");

    mvc.perform(delete("/api/itineraries/" + id + "/candidates/" + cid)
            .session(otherSession))
        .andExpect(status().isForbidden());

    mvc.perform(delete("/api/itineraries/" + id + "/candidates/" + cid)
            .session(proposerSession))
        .andExpect(status().isOk());

    JsonNode board = board(id, owner);
    assertThat(board.get("candidateCount").asInt()).isZero();
    assertThat(board.get("totalVotes").asInt()).isZero();
  }

  @Test
  @DisplayName("候选不存在：投票返回 404")
  void unknownCandidate() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "没有", "杭州", null, null, null);

    mvc.perform(post("/api/itineraries/" + id + "/candidates/not-a-real-id/votes")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("choice", "SUPPORT"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.candidateId").value("not-a-real-id"));
  }

  @Test
  @DisplayName("确认候选：写入指定日期，版本 +1，投票本身不写行程")
  void applyWritesActivity() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "确认", "杭州", "2026-11-10", "2026-11-12", null);
    String cid = addCandidate(id, owner, "灵隐寺");
    vote(id, owner, cid, "SUPPORT");

    // 投票之后行程还是空的
    assertThat(trip(id, owner).get("dailySchedules").size()).isZero();

    ObjectNode body = json.createObjectNode();
    body.put("date", "2026-11-11");
    body.put("title", "灵隐寺");

    mvc.perform(post("/api/itineraries/" + id + "/candidates/" + cid + "/apply")
            .session(owner)
            .param("version", Long.toString(version(id, owner)))
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.dailySchedules[0].date").value("2026-11-11"))
        .andExpect(jsonPath("$.dailySchedules[0].activities[0].title").value("灵隐寺"));
  }

  @Test
  @DisplayName("确认候选需要 DAILY 编辑权：无权限成员 403")
  void applyRequiresDailyPermission() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "权限", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");

    String member = uniqueUsername("nodaily");
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of("PLAN")); // 只有方案权

    ObjectNode body = json.createObjectNode();
    body.put("date", "2026-11-11");

    mvc.perform(post("/api/itineraries/" + id + "/candidates/" + cid + "/apply")
            .session(memberSession)
            .param("version", Long.toString(version(id, memberSession)))
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("确认候选时版本不符：409")
  void applyWithStaleVersionConflicts() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "冲突", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");
    long stale = version(id, owner);

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());
    mvc.perform(post("/api/itineraries/" + id + "/unarchive").session(owner))
        .andExpect(status().isOk());

    ObjectNode body = json.createObjectNode();
    body.put("date", "2026-11-11");

    mvc.perform(post("/api/itineraries/" + id + "/candidates/" + cid + "/apply")
            .session(owner)
            .param("version", Long.toString(stale))
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("归档后不能添加候选或投票（409）")
  void archivedRejectsDecisionWrites() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "归档", "杭州", null, null, null);
    String cid = addCandidate(id, owner, "西湖");

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());

    mvc.perform(post("/api/itineraries/" + id + "/candidates")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("name", "灵隐寺"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.archived").value(true));

    mvc.perform(post("/api/itineraries/" + id + "/candidates/" + cid + "/votes")
            .session(owner)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("choice", "SUPPORT"))))
        .andExpect(status().isConflict());
  }

  /** 从会话里取当前登录用户名。 */
  private String usernameOf(MockHttpSession session) {
    Object context = session.getAttribute("SPRING_SECURITY_CONTEXT");
    org.springframework.security.core.context.SecurityContext sc =
        (org.springframework.security.core.context.SecurityContext) context;
    return sc.getAuthentication().getName();
  }
}
