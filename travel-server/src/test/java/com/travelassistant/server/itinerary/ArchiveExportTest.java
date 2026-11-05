package com.travelassistant.server.itinerary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.V3ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3 第 4 节：归档与导出。
 *
 * <p>归档要验证的是「真的只读」——不是某一个接口拒绝，而是所有写入口都拒绝。
 * 只拦一个板块，归档就没有意义。
 */
@DisplayName("V3 归档与导出")
class ArchiveExportTest extends V3ApiTestBase {

  @Test
  @DisplayName("归档：标记为只读，版本 +1，并留下修改记录")
  void archiveMarksReadOnly() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "待归档", "杭州", "2026-11-10", "2026-11-12", null);

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archived").value(true))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.archivedAt").exists());

    MvcResult log = mvc.perform(get("/api/itineraries/" + id + "/changelog").session(owner))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(log.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("ARCHIVE").contains("归档行程");
  }

  @Test
  @DisplayName("归档后所有板块写入都被拒绝（409），不是某一个接口")
  void allWritesRejectedWhenArchived() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "只读", "杭州", "2026-11-10", "2026-11-12", null);
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());

    long version = version(id, owner);

    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "苏州");
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.archived").value(true));

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(owner)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content("[]"))
        .andExpect(status().isConflict());

    mvc.perform(put("/api/itineraries/" + id + "/daily")
            .session(owner)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content("[]"))
        .andExpect(status().isConflict());

    // 成员变更同样被拦
    mvc.perform(post("/api/itineraries/" + id + "/members")
            .session(owner)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(
                java.util.Map.of("username", uniqueUsername("x"), "editSections", List.of()))))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("取消归档后恢复可编辑")
  void unarchiveRestoresEditing() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "恢复", "杭州", "2026-11-10", "2026-11-12", null);
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());
    mvc.perform(post("/api/itineraries/" + id + "/unarchive").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archived").value(false));

    ObjectNode plan = json.createObjectNode();
    plan.put("destination", "苏州");
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner)
            .param("version", Long.toString(version(id, owner)))
            .contentType("application/json")
            .content(json.writeValueAsString(plan)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan.destination").value("苏州"));
  }

  @Test
  @DisplayName("归档与未归档可以分开列出，默认两边都给")
  void listFiltersByArchived() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String active = createTrip(owner, "进行中", "杭州", null, null, null);
    String done = createTrip(owner, "已结束", "苏州", null, null, null);
    mvc.perform(post("/api/itineraries/" + done + "/archive").session(owner))
        .andExpect(status().isOk());

    JsonNode all = read(mvc.perform(get("/api/itineraries").session(owner))
        .andExpect(status().isOk()).andReturn());
    JsonNode onlyArchived = read(mvc.perform(get("/api/itineraries").param("archived", "true")
        .session(owner)).andExpect(status().isOk()).andReturn());
    JsonNode onlyActive = read(mvc.perform(get("/api/itineraries").param("archived", "false")
        .session(owner)).andExpect(status().isOk()).andReturn());

    assertThat(ids(all)).contains(active, done);
    assertThat(ids(onlyArchived)).containsExactly(done);
    assertThat(ids(onlyActive)).contains(active);
    assertThat(ids(onlyActive)).doesNotContain(done);
  }

  @Test
  @DisplayName("只有创建者能归档：成员 403")
  void onlyOwnerCanArchive() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "权限", "杭州", null, null, null);
    String member = uniqueUsername("member");
    MockHttpSession memberSession = registerAndLogin(member, DEFAULT_PASSWORD);
    addMember(id, owner, member, List.of("PLAN"));

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(memberSession))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/itineraries/" + id + "/unarchive").session(memberSession))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("重复归档幂等：不报错也不涨版本号")
  void archiveIsIdempotent() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "幂等", "杭州", null, null, null);
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());
    long afterFirst = version(id, owner);

    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(afterFirst));
  }

  @Test
  @DisplayName("导出：纯文本摘要包含方案、成员、费用、预算与说明，并带下载文件名")
  void exportContainsSummary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "杭州三日", "杭州", "2026-11-10", "2026-11-12", "3000");
    putExpenses(id, owner, cost("交通", "1200", true));
    String cid = addCandidate(id, owner, "西湖");
    vote(id, owner, cid, "SUPPORT");
    putDaily(id, owner, "2026-11-11", "游船", "09:00", "12:00");

    MvcResult result = mvc.perform(get("/api/itineraries/" + id + "/export").session(owner))
        .andExpect(status().isOk())
        .andReturn();

    assertThat(result.getResponse().getContentType()).contains("text/plain");
    assertThat(result.getResponse().getHeader("Content-Disposition"))
        .contains("attachment");

    String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(text)
        .contains("行程摘要：杭州三日")
        .contains("【成员】")
        .contains("【每日安排】")
        .contains("游船")
        .contains("【费用】")
        .contains("【预算分析】")
        .contains("上限：3000.00 CNY")
        .contains("【候选与投票】")
        .contains("西湖")
        .contains("【行程检查】")
        .contains("不构成预订承诺");
  }

  @Test
  @DisplayName("导出越权：非参与者 403")
  void exportRequiresAccess() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "私有", "杭州", null, null, null);
    MockHttpSession outsider = registerAndLogin();

    mvc.perform(get("/api/itineraries/" + id + "/export").session(outsider))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("行程已结束且未归档：体检给出可归档提示")
  void endedTripSuggestsArchive() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "去年的行程", "杭州", "2020-01-01", "2020-01-05", null);

    JsonNode checks = read(mvc.perform(get("/api/itineraries/" + id + "/checks").session(owner))
        .andExpect(status().isOk()).andReturn());

    boolean found = false;
    for (JsonNode warning : checks.get("warnings")) {
      if ("TRIP_ENDED".equals(warning.get("code").asText())) {
        found = true;
      }
    }
    assertThat(found).as("应提示行程已结束").isTrue();
  }

  @Test
  @DisplayName("归档后体检不再提示可归档")
  void archivedTripDoesNotSuggestAgain() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createTrip(owner, "已归档的旧行程", "杭州", "2020-01-01", "2020-01-05", null);
    mvc.perform(post("/api/itineraries/" + id + "/archive").session(owner))
        .andExpect(status().isOk());

    JsonNode checks = read(mvc.perform(get("/api/itineraries/" + id + "/checks").session(owner))
        .andExpect(status().isOk()).andReturn());
    for (JsonNode warning : checks.get("warnings")) {
      assertThat(warning.get("code").asText()).isNotEqualTo("TRIP_ENDED");
    }
  }

  private java.util.List<String> ids(JsonNode array) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    array.forEach(node -> ids.add(node.get("id").asText()));
    return ids;
  }
}
