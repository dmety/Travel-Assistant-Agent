package com.travelassistant.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口测试基类：拉起一次 Spring 上下文供所有子类共用，数据目录指向本次运行专属目录，
 * 避免污染仓库里的 {@code data/} 或因上一轮残留数据导致断言飘忽。
 *
 * <p>目录落在 {@code target/test-data} 下并在 JVM 退出时回收，见 {@link TestDataDirs}。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

  /**
   * 本测试运行专属的数据目录，{@code accounts.json} 与 {@code itineraries.json} 都写在这里。
   *
   * <p>落在模块的 {@code target/test-data} 下并由 {@link TestDataDirs} 统一回收，
   * 不再写系统临时目录（Windows 上即 C 盘 {@code %TEMP%}）。
   */
  protected static final Path DATA_DIR = TestDataDirs.sub("api");

  protected static final String DEFAULT_PASSWORD = "Passw0rd!2026";

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  protected MockMvc mvc;

  @Autowired
  protected ObjectMapper json;

  @DynamicPropertySource
  static void dataDirectory(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
    // 共享上下文里成百上千次注册/登录都来自 127.0.0.1，限流默认值会误伤整套测试；
    // 这里放到极大值等效关闭，限流行为由专属低配额上下文 RateLimitFilterTest 验证。
    registry.add("travel.rate-limit.login-per-minute", () -> "1000000");
    registry.add("travel.rate-limit.register-per-minute", () -> "1000000");
  }

  // ---- 账号 ----

  /** 生成一个合法且不重复的用户名（3-32 位字母数字下划线）。 */
  protected String uniqueUsername(String prefix) {
    return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
  }

  /** 注册并登录，返回已认证的会话。 */
  protected MockHttpSession registerAndLogin(String username, String password) throws Exception {
    register(username, password);
    return login(username, password);
  }

  /** 注册并登录，用户名自动生成。 */
  protected MockHttpSession registerAndLogin() throws Exception {
    return registerAndLogin(uniqueUsername("user"), DEFAULT_PASSWORD);
  }

  /** 只注册不登录。 */
  protected void register(String username, String password) throws Exception {
    mvc.perform(post("/api/account/register")
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", password))))
        .andExpect(status().isCreated());
  }

  /** 登录并返回会话。 */
  protected MockHttpSession login(String username, String password) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mvc.perform(post("/api/login")
            .param("username", username)
            .param("password", password)
            .session(session))
        .andExpect(status().isOk());
    return session;
  }

  // ---- 行程 ----

  /** 创建一份行程，返回其 id。 */
  protected String createItinerary(MockHttpSession session, String title) throws Exception {
    return createItinerary(session, title, "杭州");
  }

  protected String createItinerary(MockHttpSession session, String title, String destination)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("title", title);
    ObjectNode plan = body.putObject("plan");
    plan.put("destination", destination);
    plan.put("summary", "西湖与灵隐寺");
    plan.put("notes", "高铁往返");

    MvcResult result = mvc.perform(post("/api/itineraries")
            .session(session)
            .contentType("application/json")
            .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** 读取行程当前版本号（取详情接口的 version 字段）。 */
  protected long currentVersion(String id, MockHttpSession session) throws Exception {
    MvcResult result = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("version").asLong();
  }

  /** 直接读取行程 JSON，便于断言多个字段。 */
  protected com.fasterxml.jackson.databind.JsonNode getItinerary(String id, MockHttpSession session)
      throws Exception {
    MvcResult result = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/itineraries/" + id).session(session))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsetsHolder.UTF_8));
  }

  /** 把一批 JSON 对象组装成数组，用于 daily / transports / lodgings / expenses 的 PUT 请求。 */
  protected ArrayNode arrayOf(ObjectNode... nodes) {
    ArrayNode array = json.createArrayNode();
    for (ObjectNode n : nodes) {
      array.add(n);
    }
    return array;
  }

  protected ObjectNode expense(String category, String amount) {
    ObjectNode node = json.createObjectNode();
    node.put("category", category);
    node.put("amount", new java.math.BigDecimal(amount));
    node.put("currency", "CNY");
    node.put("date", "2026-11-10");
    node.put("note", "预算");
    return node;
  }

  protected ObjectNode transport(String mode, String from, String to) {
    ObjectNode node = json.createObjectNode();
    node.put("mode", mode);
    node.put("from", from);
    node.put("to", to);
    node.put("date", "2026-11-10");
    node.put("departTime", "08:00");
    node.put("arriveTime", "12:30");
    node.put("flightNo", "G1234");
    node.put("seat", "二等座");
    node.put("note", "");
    return node;
  }

  protected ObjectNode lodging(String name) {
    ObjectNode node = json.createObjectNode();
    node.put("name", name);
    node.put("address", "西湖区某路 1 号");
    node.put("checkIn", "2026-11-10");
    node.put("checkOut", "2026-11-12");
    node.put("roomType", "大床房");
    node.put("note", "");
    return node;
  }

  /** 每日安排：含一条活动，活动不带 id，用于验证服务端会自动补 id。 */
  protected ObjectNode dailySchedule(String date, String activityTitle) {
    ObjectNode day = json.createObjectNode();
    day.put("date", date);
    day.put("note", "第一天");
    ObjectNode activity = json.createObjectNode();
    activity.put("time", "09:00");
    activity.put("title", activityTitle);
    activity.put("location", "西湖");
    activity.put("note", "");
    day.putArray("activities").add(activity);
    return day;
  }

  /** 给行程添加成员并授予指定板块编辑权限，返回新版本号。 */
  protected long addMember(String id, MockHttpSession owner, String memberUsername,
      List<String> sections) throws Exception {
    long version = currentVersion(id, owner);
    MvcResult result = mvc.perform(post("/api/itineraries/" + id + "/members")
            .session(owner)
            .param("version", Long.toString(version))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", memberUsername,
                "editSections", sections))))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("version").asLong();
  }

  /** 内部小工具：固定 UTF-8，避免平台默认编码影响中文断言。 */
  private static final class StandardCharsetsHolder {
    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;
  }
}
