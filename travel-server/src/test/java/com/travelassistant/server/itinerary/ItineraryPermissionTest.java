package com.travelassistant.server.itinerary;

import com.travelassistant.server.ApiTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限验收测试（需求文档 §2：服务端必须验证每次请求的身份和方案权限，
 * 不能依赖客户端隐藏菜单）。
 *
 * <p>三种角色：owner（全部权限）、member（仅被授予的板块）、stranger（什么都看不到）。
 */
@DisplayName("权限控制")
class ItineraryPermissionTest extends ApiTestBase {

  @Test
  @DisplayName("陌生人不能查看别人的行程")
  void strangerCannotReadItinerary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    MockHttpSession stranger = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(get("/api/itineraries/" + id).session(stranger))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("陌生人不能修改别人行程的任何板块")
  void strangerCannotWriteAnySection() throws Exception {
    MockHttpSession owner = registerAndLogin();
    MockHttpSession stranger = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(stranger).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("destination", "篡改", "summary", "x", "notes", "x"))))
        .andExpect(status().isForbidden());

    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(stranger).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(expense("交通", "1")))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("陌生人不能删除别人的行程")
  void strangerCannotDeleteItinerary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    MockHttpSession stranger = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(delete("/api/itineraries/" + id).session(stranger))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("被邀请的成员可以查看行程")
  void memberCanViewItinerary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    addMember(id, owner, memberName, List.of("TRANSPORT"));

    mvc.perform(get("/api/itineraries/" + id).session(member))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("杭州三日游"));
  }

  @Test
  @DisplayName("成员只能改被授权的板块，未授权板块返回 403")
  void memberCanOnlyEditGrantedSections() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    long version = addMember(id, owner, memberName, List.of("TRANSPORT"));

    // 有 TRANSPORT 权限 -> 200
    mvc.perform(put("/api/itineraries/" + id + "/transports")
            .session(member).param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(transport("高铁", "北京", "杭州")))))
        .andExpect(status().isOk());

    // 没有 PLAN 权限 -> 403
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(member).param("version", Long.toString(version + 1))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("destination", "越权", "summary", "x", "notes", "x"))))
        .andExpect(status().isForbidden());

    // 没有 EXPENSE 权限 -> 403
    mvc.perform(put("/api/itineraries/" + id + "/expenses")
            .session(member).param("version", Long.toString(version + 1))
            .contentType("application/json")
            .content(json.writeValueAsString(arrayOf(expense("交通", "1")))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("成员不能删除行程（仅创建者可删）")
  void memberCannotDeleteItinerary() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    addMember(id, owner, memberName, List.of("PLAN", "EXPENSE"));

    mvc.perform(delete("/api/itineraries/" + id).session(member))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("成员不能管理成员（加人、改权限、移除）")
  void memberCannotManageMembers() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);
    String otherName = uniqueUsername("other");
    register(otherName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    long version = addMember(id, owner, memberName, List.of("PLAN"));

    // 成员试图再拉一个人进来
    mvc.perform(post("/api/itineraries/" + id + "/members")
            .session(member).param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", otherName,
                "editSections", List.of("PLAN")))))
        .andExpect(status().isForbidden());

    // 成员试图改自己的权限
    mvc.perform(put("/api/itineraries/" + id + "/members/" + memberName)
            .session(member).param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("editSections",
                List.of("PLAN", "DAILY", "TRANSPORT", "LODGING", "EXPENSE")))))
        .andExpect(status().isForbidden());

    // 成员试图移除别人
    mvc.perform(delete("/api/itineraries/" + id + "/members/" + otherName)
            .session(member).param("version", Long.toString(version)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("创建者可以添加、修改、移除成员")
  void ownerCanManageMembers() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    register(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    long version = addMember(id, owner, memberName, List.of("PLAN"));

    // 改权限
    mvc.perform(put("/api/itineraries/" + id + "/members/" + memberName)
            .session(owner).param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("editSections", List.of("EXPENSE")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members[0].editSections[0]").value("EXPENSE"));

    long afterUpdate = currentVersion(id, owner);

    // 移除成员
    mvc.perform(delete("/api/itineraries/" + id + "/members/" + memberName)
            .session(owner).param("version", Long.toString(afterUpdate)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(0));
  }

  @Test
  @DisplayName("成员被移除后立即失去访问权")
  void removedMemberLosesAccess() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    long version = addMember(id, owner, memberName, List.of("PLAN"));

    mvc.perform(get("/api/itineraries/" + id).session(member)).andExpect(status().isOk());

    mvc.perform(delete("/api/itineraries/" + id + "/members/" + memberName)
            .session(owner).param("version", Long.toString(version)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/itineraries/" + id).session(member))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("成员名单对所有参与者可读")
  void memberListIsVisibleToParticipants() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    addMember(id, owner, memberName, List.of("PLAN"));

    mvc.perform(get("/api/itineraries/" + id + "/members").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].username").value(memberName));

    mvc.perform(get("/api/itineraries/" + id + "/members").session(member))
        .andExpect(status().isOk());
  }

}
