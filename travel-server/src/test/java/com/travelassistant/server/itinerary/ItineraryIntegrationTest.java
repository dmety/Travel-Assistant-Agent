package com.travelassistant.server.itinerary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ItineraryIntegrationTest {

  /** 每次运行独立目录，避免上一轮残留的行程影响「全链路」断言。 */
  private static final java.nio.file.Path DATA_DIR =
      com.travelassistant.server.TestDataDirs.sub("integration");

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", DATA_DIR::toString);
  }

  private MockHttpSession registerAndLogin(String username, String password) throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());
    MockHttpSession session = new MockHttpSession();
    mvc.perform(post("/api/login").param("username", username).param("password", password).session(session))
        .andExpect(status().isOk());
    return session;
  }

  private String createItinerary(MockHttpSession owner, String title) throws Exception {
    MvcResult r = mvc.perform(post("/api/itineraries").session(owner)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("title", title,
                "plan", Map.of("destination", "武汉", "summary", "毕业旅行", "notes", "")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1))
        .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void fullItineraryFlowWithFineGrainedPermissions() throws Exception {
    MockHttpSession owner = registerAndLogin("owner_" + System.nanoTime(), "password123");
    MockHttpSession member = registerAndLogin("member_" + System.nanoTime(), "password123");
    MockHttpSession stranger = registerAndLogin("stranger_" + System.nanoTime(), "password123");

    String id = createItinerary(owner, "武汉之行");

    // owner 可见
    mvc.perform(get("/api/itineraries/" + id).session(owner)).andExpect(status().isOk());
    // 出现在 owner 列表（断言「包含」而不是下标：列表顺序取决于存储数组顺序，不该被测试依赖）
    mvc.perform(get("/api/itineraries").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(id)));

    // owner 改方案（version=1 -> 2）
    mvc.perform(put("/api/itineraries/" + id + "/plan").session(owner).param("version", "1")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("destination", "武汉", "summary", "更新版", "notes", "x"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    // 携带旧版本 -> 冲突 409
    mvc.perform(put("/api/itineraries/" + id + "/plan").session(owner).param("version", "1")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("destination", "武汉", "summary", "旧", "notes", ""))))
        .andExpect(status().isConflict());

    // 陌生人不可见 -> 403
    mvc.perform(get("/api/itineraries/" + id).session(stranger)).andExpect(status().isForbidden());

    // owner 把 member 加为仅 TRANSPORT 可编辑（version=2 -> 3）
    mvc.perform(post("/api/itineraries/" + id + "/members").session(owner).param("version", "2")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("username",
                memberName(member), "editSections", List.of("TRANSPORT")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.members[0].editSections[0]").value("TRANSPORT"));

    // member 可见（参与者）
    mvc.perform(get("/api/itineraries/" + id).session(member)).andExpect(status().isOk());

    // member 改交通（version=3 -> 4）成功
    ObjectNode transport = objectMapper.createObjectNode();
    transport.put("mode", "高铁");
    transport.put("from", "北京");
    transport.put("to", "武汉");
    ArrayNode transports = objectMapper.createArrayNode().add(transport);
    mvc.perform(put("/api/itineraries/" + id + "/transports").session(member).param("version", "3")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(transports)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.transports[0].id").exists());

    // member 改方案（无 PLAN 权限）-> 403
    mvc.perform(put("/api/itineraries/" + id + "/plan").session(member).param("version", "4")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(Map.of("destination", "武汉", "summary", "越权", "notes", ""))))
        .andExpect(status().isForbidden());

    // 陌生人删除 -> 403
    mvc.perform(delete("/api/itineraries/" + id).session(stranger)).andExpect(status().isForbidden());

    // owner 删除 -> 200，随后 404
    mvc.perform(delete("/api/itineraries/" + id).session(owner)).andExpect(status().isOk());
    mvc.perform(get("/api/itineraries/" + id).session(owner)).andExpect(status().isNotFound());
  }

  private String memberName(MockHttpSession member) throws Exception {
    MvcResult r = mvc.perform(get("/api/account/me").session(member)).andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString()).get("username").asText();
  }
}
