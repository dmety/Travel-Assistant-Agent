package com.travelassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.common.api.HealthResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 旅行助手控制台客户端。
 * 默认只做连通性健康检查；传入用户名和密码时会演示完整的
 * 注册 → 登录（服务端会话 + JSESSIONID Cookie）→ 查询当前用户 流程。
 */
public class TravelClient {

  private final String base;
  private final HttpClient client;
  private final ObjectMapper json = new ObjectMapper();

  public TravelClient(String base) {
    this.base = base.replaceAll("/+$", "");
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    this.client = HttpClient.newBuilder().cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5)).build();
  }

  public static void main(String[] args) {
    String base = args.length > 0 ? args[0] : "http://localhost:8080";
    var cli = new TravelClient(base);
    try {
      cli.checkHealth();
      if (args.length >= 2 && "attractions".equalsIgnoreCase(args[1])) {
        // 景点查询：attractions（交互） / attractions 杭州（一次性） / attractions --id <ID>
        var console = new AttractionConsole(base);
        if (args.length >= 4 && "--id".equalsIgnoreCase(args[2])) {
          console.detailByIdAndPrint(args[3]);
        } else if (args.length >= 3) {
          console.searchAndPrint(args[2]);
        } else {
          console.runInteractive();
        }
      } else if (args.length >= 3) {
        cli.demoAuth(args[1], args[2]);
      } else {
        System.out.println("景点查询（交互）：java -jar travel-client.jar " + base + " attractions");
        System.out.println("按城市查询：      java -jar travel-client.jar " + base + " attractions 杭州");
        System.out.println("按 ID 查详情：    java -jar travel-client.jar " + base + " attractions --id <景点ID>");
        System.out.println("登录演示（含预算与检查）：java -jar travel-client.jar " + base + " <用户名> <密码>");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("连接已中断");
      System.exit(1);
    } catch (Exception e) {
      System.err.println("无法连接旅行服务：" + e.getMessage());
      System.exit(1);
    }
  }

  private void checkHealth() throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + "/api/health"))
        .timeout(Duration.ofSeconds(10)).GET().build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException("HTTP " + response.statusCode());
    }
    HealthResponse health = json.readValue(response.body(), HealthResponse.class);
    System.out.println("旅行助手：已连接服务 " + health.application() + "，状态 " + health.status());
  }

  /** 演示注册（已存在则忽略）、登录（拿到会话 Cookie）、查询当前用户。 */
  private void demoAuth(String username, String password) throws Exception {
    // 1) 注册：账号写入服务端 JSON
    var regBody = json.writeValueAsString(Map.of("username", username, "password", password));
    var reg = postJson("/api/account/register", regBody);
    System.out.println("注册：HTTP " + reg.statusCode() + " " + reg.body());

    // 2) 登录：服务端建立会话，JSESSIONID 由 CookieManager 自动保存
    String form = "username=" + encode(username) + "&password=" + encode(password);
    var loginReq = HttpRequest.newBuilder(URI.create(base + "/api/login"))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    var login = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
    System.out.println("登录：HTTP " + login.statusCode() + " " + login.body());

    // 3) 携带 Cookie 查询当前用户
    var meReq = HttpRequest.newBuilder(URI.create(base + "/api/account/me"))
        .timeout(Duration.ofSeconds(10)).GET().build();
    var me = client.send(meReq, HttpResponse.BodyHandlers.ofString());
    System.out.println("当前用户：HTTP " + me.statusCode() + " " + me.body());
    if (me.statusCode() == 200) {
      JsonNode node = json.readTree(me.body());
      System.out.println("已以 " + node.get("username").asText() + " 的身份通过会话登录。");
      // 4) 有行程时顺带打印第一份的预算汇总与体检结果（V1 第 5 节）
      showBudgetAndChecks();
    }
  }

  /**
   * 取「我的行程」第一份，打印预算汇总与体检提示。
   *
   * <p>多币种时按币种分行显示并注明「不做汇率换算」，避免把 100 元和 100 美元加成 200。
   */
  private void showBudgetAndChecks() throws Exception {
    var list = get("/api/itineraries");
    if (list.statusCode() != 200) {
      System.out.println("行程列表：HTTP " + list.statusCode() + "（跳过预算汇总）");
      return;
    }
    JsonNode items = json.readTree(list.body());
    if (!items.isArray() || items.isEmpty()) {
      System.out.println("尚未创建行程，暂无预算可汇总。");
      return;
    }
    String id = items.get(0).get("id").asText();
    String title = items.get(0).get("title").asText();

    var budgetRes = get("/api/itineraries/" + id + "/budget");
    var checksRes = get("/api/itineraries/" + id + "/checks");
    if (budgetRes.statusCode() != 200 || checksRes.statusCode() != 200) {
      System.out.println("预算/体检读取失败：HTTP " + budgetRes.statusCode() + " / "
          + checksRes.statusCode());
      return;
    }
    JsonNode budget = json.readTree(budgetRes.body());
    JsonNode checks = json.readTree(checksRes.body());

    System.out.println();
    System.out.println("== 预算与检查：" + title + " ==");
    System.out.println("主要币种：" + budget.get("primaryCurrency").asText()
        + "　计入条目：" + budget.get("itemCount").asInt()
        + "　待填写：" + budget.get("pendingCount").asInt());
    for (JsonNode t : budget.get("totals")) {
      System.out.println("  " + t.get("currency").asText() + "  " + money(t.get("amount"))
          + "（" + t.get("itemCount").asInt() + " 条）");
    }
    for (JsonNode t : budget.get("byCategory")) {
      System.out.println("  [" + t.get("currency").asText() + "] " + t.get("category").asText()
          + "  " + money(t.get("amount")) + "（" + t.get("itemCount").asInt() + " 条）");
    }
    for (JsonNode note : budget.get("notes")) {
      System.out.println("  · " + note.asText());
    }
    if (checks.get("ok").asBoolean()) {
      System.out.println("  ✓ 体检未发现问题");
    } else {
      for (JsonNode w : checks.get("warnings")) {
        System.out.println("  ✗ [" + w.get("code").asText() + "] " + w.get("message").asText());
      }
    }
  }

  /**
   * 金额按两位小数显示。
   *
   * <p>接口返回的是 1400.00，但客户端用 {@code readTree} 解析后成为 Double 节点，
   * 直接 {@code asText()} 会退化成 1400.0，金额显示必须补足两位。
   */
  private static String money(JsonNode amount) {
    return amount == null || amount.isNull() ? "未知" : String.format("%.2f", amount.asDouble());
  }

  private HttpResponse<String> get(String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(10)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postJson(String path, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
