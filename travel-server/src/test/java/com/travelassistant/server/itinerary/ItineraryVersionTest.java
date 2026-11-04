package com.travelassistant.server.itinerary;

import com.travelassistant.server.ApiTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 乐观锁验收测试（需求文档 §4：两名成员同时编辑时检查版本，
 * 冲突时拒绝覆盖并提示重新读取）。
 */
@DisplayName("乐观锁与版本冲突")
class ItineraryVersionTest extends ApiTestBase {

  private static Map<String, String> plan(String summary) {
    return Map.of("destination", "杭州", "summary", summary, "notes", "");
  }

  @Test
  @DisplayName("携带正确版本号可以更新，版本号递增")
  void updateWithCorrectVersionSucceeds() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("第一版"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "2")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("第二版"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.plan.summary").value("第二版"));
  }

  @Test
  @DisplayName("携带旧版本号更新被拒绝，返回 409 且告知当前版本")
  void staleVersionIsRejectedWithConflict() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("我的修改"))))
        .andExpect(status().isOk());

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("想覆盖别人的修改"))))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Version", "2"))
        .andExpect(jsonPath("$.currentVersion").value(2))
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  @DisplayName("冲突后不覆盖数据：原内容保持不变")
  void conflictDoesNotOverwriteExistingData() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("已确认的内容"))))
        .andExpect(status().isOk());

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("被拒绝的内容"))))
        .andExpect(status().isConflict());

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/itineraries/" + id + "/plan").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").value("已确认的内容"));
  }

  @Test
  @DisplayName("冲突后重新读取版本再提交即可成功（模拟客户端重试）")
  void retryAfterRereadingVersionSucceeds() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String id = createItinerary(owner, "杭州三日游");

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("第一版"))))
        .andExpect(status().isOk());

    // 用旧版本失败
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(plan("旧版本"))))
        .andExpect(status().isConflict());

    // 重新读取当前版本后重试
    long current = currentVersion(id, owner);
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", Long.toString(current))
            .contentType("application/json")
            .content(json.writeValueAsString(plan("重试成功"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan.summary").value("重试成功"));
  }

  @Test
  @DisplayName("成员变更同样受版本控制并递增版本")
  void memberChangesAreVersionControlled() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    register(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");

    // version 1 -> 2
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/itineraries/" + id + "/members")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", memberName,
                "editSections", List.of("PLAN")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    // 用旧版本再改成员 -> 409
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/itineraries/" + id + "/members")
            .session(owner).param("version", "1")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", memberName,
                "editSections", List.of("EXPENSE")))))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("两人同时基于同一版本编辑，后提交者被拒绝")
  void secondEditorWithSameVersionIsRejected() throws Exception {
    MockHttpSession owner = registerAndLogin();
    String memberName = uniqueUsername("member");
    MockHttpSession member = registerAndLogin(memberName, DEFAULT_PASSWORD);

    String id = createItinerary(owner, "杭州三日游");
    long base = addMember(id, owner, memberName, List.of("PLAN"));

    // 两人都读到同一版本 base
    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(owner).param("version", Long.toString(base))
            .contentType("application/json")
            .content(json.writeValueAsString(plan("owner 的修改"))))
        .andExpect(status().isOk());

    mvc.perform(put("/api/itineraries/" + id + "/plan")
            .session(member).param("version", Long.toString(base))
            .contentType("application/json")
            .content(json.writeValueAsString(plan("member 的修改"))))
        .andExpect(status().isConflict());
  }
}
