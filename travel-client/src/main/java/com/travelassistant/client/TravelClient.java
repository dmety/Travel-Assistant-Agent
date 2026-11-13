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
import java.util.ArrayList;
import java.util.List;
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
  private final ConsoleUI ui;

  public TravelClient(String base) {
    this(base, ConsoleUI.plain());
  }

  public TravelClient(String base, ConsoleUI ui) {
    this.base = base.replaceAll("/+$", "");
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    this.client = HttpClient.newBuilder().cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5)).build();
    this.ui = ui;
  }

  public static void main(String[] args) {
    // 默认端口与 tools/run-server.sh 保持一致。
    String base = args.length > 0 ? args[0] : "http://localhost:8080";
    // 有真实终端且没有指定子命令时进入交互式主入口；带参数或走管道时保持原有脚本用法。
    boolean repl = args.length <= 1 && System.console() != null;
    try (ConsoleUI ui = repl
        ? ConsoleUI.interactive("help", "exit", "q", "login", "status", "attractions",
            "itineraries", "plan", "votes", "advice", "export", "ticket")
        : ConsoleUI.plain()) {
      var cli = new TravelClient(base, ui);
      String service = cli.checkHealth();
      if (repl) {
        cli.runRepl(service);
        return;
      }
      if (args.length >= 2 && "attractions".equalsIgnoreCase(args[1])) {
        // 景点查询：attractions（交互） / attractions 杭州（一次性） / attractions --id <ID>
        var console = new AttractionConsole(base, ui);
        if (args.length >= 4 && "--id".equalsIgnoreCase(args[2])) {
          console.detailByIdAndPrint(args[3]);
        } else if (args.length >= 3) {
          console.searchAndPrint(args[2]);
        } else {
          console.runInteractive();
        }
      } else if (args.length >= 4 && "invitations".equalsIgnoreCase(args[1])) {
        // 我的待处理邀请：invitations <用户名> <密码> [accept|reject <邀请ID>]
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        if (args.length >= 6) {
          cli.respondInvitation(args[4], args[5]);
        } else {
          cli.listInvitations();
        }
      } else if (args.length >= 6 && "invite".equalsIgnoreCase(args[1])) {
        // 发起邀请：invite <用户名> <密码> <行程ID> <受邀账号> [板块...]
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        String[] sections = args.length > 6
            ? java.util.Arrays.copyOfRange(args, 6, args.length)
            : new String[0];
        cli.sendInvitation(args[4], args[5], sections);
      } else if (args.length >= 3 && "ticket".equalsIgnoreCase(args[1])) {
        // 购票辅助（V2）：ticket <景点名称>
        cli.showTicket(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
      } else if (args.length >= 5 && "timeline".equalsIgnoreCase(args[1])) {
        // 协作记录（V2 §4）：timeline <用户名> <密码> <行程ID>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.showTimeline(args[4]);
      } else if (args.length >= 6 && "comment".equalsIgnoreCase(args[1])) {
        // 留言（V2 §4）：comment <用户名> <密码> <行程ID> <内容…>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.addComment(args[4], String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length)));
      } else if (args.length >= 5 && "plan".equalsIgnoreCase(args[1])) {
        // 规划草案（V2 §2）：plan <用户名> <密码> <目的地> [天数]
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        Integer days = args.length >= 6 ? parseIntOrNull(args[5]) : null;
        cli.showPlan(args[4], days);
      } else if (args.length >= 5 && "votes".equalsIgnoreCase(args[1])) {
        // 候选与投票（V3 §1）：votes <用户名> <密码> <行程ID>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.showBoard(args[4]);
      } else if (args.length >= 6 && "candidate".equalsIgnoreCase(args[1])) {
        // 添加候选：candidate <用户名> <密码> <行程ID> <名称> [ATTRACTION|LODGING|OTHER]
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.addCandidate(args[4], args[5],
            args.length >= 7 ? args[6].toUpperCase() : "ATTRACTION");
      } else if (args.length >= 7 && "vote".equalsIgnoreCase(args[1])) {
        // 投票：vote <用户名> <密码> <行程ID> <候选ID> support|oppose|abstain
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.castVote(args[4], args[5], args[6]);
      } else if (args.length >= 5 && "advice".equalsIgnoreCase(args[1])) {
        // 综合决策建议（V3 §3）：advice <用户名> <密码> <行程ID>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.showAdvice(args[4]);
      } else if (args.length >= 5 && "export".equalsIgnoreCase(args[1])) {
        // 导出文本摘要（V3 §4）：export <用户名> <密码> <行程ID>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.exportText(args[4]);
      } else if (args.length >= 5 && ("archive".equalsIgnoreCase(args[1])
          || "unarchive".equalsIgnoreCase(args[1]))) {
        // 归档/取消归档（V3 §4）：archive|unarchive <用户名> <密码> <行程ID>
        cli.loginOnly(args[2], resolvePassword(args[3], ui));
        cli.setArchived(args[4], "archive".equalsIgnoreCase(args[1]));
      } else if (args.length >= 3) {
        cli.demoAuth(args[1], resolvePassword(args[2], ui));
      } else {
        ui.println("景点查询（交互）：java -jar travel-client.jar " + base + " attractions");
        ui.println("按城市查询：      java -jar travel-client.jar " + base + " attractions 杭州");
        ui.println("按 ID 查详情：    java -jar travel-client.jar " + base + " attractions --id <景点ID>");
        ui.println("登录演示（含预算与检查）：java -jar travel-client.jar " + base + " <用户名> <密码>");
        ui.println("我的待处理邀请：  java -jar travel-client.jar " + base
            + " invitations <用户名> <密码>");
        ui.println("接受/拒绝邀请：   java -jar travel-client.jar " + base
            + " invitations <用户名> <密码> accept|reject <邀请ID>");
        ui.println("发起邀请：        java -jar travel-client.jar " + base
            + " invite <用户名> <密码> <行程ID> <受邀账号> [PLAN DAILY TRANSPORT LODGING EXPENSE]");
        ui.println("购票辅助：        java -jar travel-client.jar " + base + " ticket 西湖");
        ui.println("协作记录与留言：  java -jar travel-client.jar " + base
            + " timeline <用户名> <密码> <行程ID>");
        ui.println("发表留言：        java -jar travel-client.jar " + base
            + " comment <用户名> <密码> <行程ID> <内容…>");
        ui.println("规划草案：        java -jar travel-client.jar " + base
            + " plan <用户名> <密码> <目的地> [天数]");
        ui.println("候选与投票：      java -jar travel-client.jar " + base
            + " votes <用户名> <密码> <行程ID>");
        ui.println("添加候选：        java -jar travel-client.jar " + base
            + " candidate <用户名> <密码> <行程ID> <名称> [ATTRACTION|LODGING|OTHER]");
        ui.println("投票：            java -jar travel-client.jar " + base
            + " vote <用户名> <密码> <行程ID> <候选ID> support|oppose|abstain");
        ui.println("综合建议：        java -jar travel-client.jar " + base
            + " advice <用户名> <密码> <行程ID>");
        ui.println("导出摘要：        java -jar travel-client.jar " + base
            + " export <用户名> <密码> <行程ID>");
        ui.println("归档/取消归档：   java -jar travel-client.jar " + base
            + " archive|unarchive <用户名> <密码> <行程ID>");
        ui.println("密码安全：把 <密码> 写成 env:TRAVEL_CLI_PASSWORD（从环境变量读）或 -（掩码/管道输入），"
            + "避免明文出现在进程列表与 shell 历史。");
      }
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("连接已中断");
      System.exit(1);
    } catch (Exception e) {
      System.err.println("无法连接旅行服务：" + e.getMessage());
      System.err.println("请先启动服务：bash tools/run-server.sh [端口]，"
          + "或用 java -jar travel-client.jar http://<地址>:<端口> 指定地址。");
      System.exit(1);
    }
  }

  private String checkHealth() throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + "/api/health"))
        .timeout(Duration.ofSeconds(10)).GET().build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException("HTTP " + response.statusCode());
    }
    HealthResponse health = json.readValue(response.body(), HealthResponse.class);
    // 交互模式下由横幅展示连接信息，避免重复一行。
    if (!ui.isInteractive()) {
      ui.println("旅行助手：已连接服务 " + health.application() + "，状态 " + health.status());
    }
    return health.application();
  }

  // ---- 交互式主入口 ----

  /**
   * 无子命令且有真实终端时进入会话式 REPL。
   *
   * <p>与参数化用法共用同一套请求方法，登录态由本实例的 CookieManager 维持；
   * 命令异常只提示不中断会话。
   */
  private void runRepl(String service) throws Exception {
    ui.banner(service, base);
    String[] commands = {"help", "exit", "q", "quit", "login", "status", "attractions",
        "itineraries", "plan", "votes", "advice", "export", "ticket"};
    String username = null;
    while (true) {
      String line = ui.readLine("\n" + (username == null ? "未登录" : username) + " > ");
      if (line == null) {
        break;
      }
      line = line.strip();
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split("\\s+");
      String cmd = parts[0].toLowerCase(java.util.Locale.ROOT);
      if (cmd.equals("exit") || cmd.equals("q") || cmd.equals("quit")) {
        ui.note("已退出。");
        break;
      }
      try {
        switch (cmd) {
          case "help" -> printReplHelp();
          case "login" -> {
            if (parts.length < 2) {
              ui.warn("用法：login <用户名>");
              break;
            }
            String password = ui.readMasked("  密码: ");
            if (password == null || password.isBlank()) {
              ui.warn("密码不能为空。");
              break;
            }
            loginOnly(parts[1], password);
            username = parts[1];
          }
          case "status" -> showStatus();
          case "attractions" -> {
            var console = new AttractionConsole(base, ui);
            if (parts.length >= 2) {
              console.searchAndPrint(join(parts, 1));
            } else {
              console.runInteractive();
            }
          }
          case "itineraries" -> {
            if (requireLogin(username)) {
              listItineraries();
            }
          }
          case "plan" -> {
            if (requireLogin(username)) {
              if (parts.length < 2) {
                ui.warn("用法：plan <目的地> [天数]");
              } else {
                showPlan(parts[1], parts.length >= 3 ? parseIntOrNull(parts[2]) : null);
              }
            }
          }
          case "votes" -> {
            if (requireLogin(username)) {
              if (parts.length < 2) {
                ui.warn("用法：votes <行程ID>");
              } else {
                showBoard(parts[1]);
              }
            }
          }
          case "advice" -> {
            if (requireLogin(username)) {
              if (parts.length < 2) {
                ui.warn("用法：advice <行程ID>");
              } else {
                showAdvice(parts[1]);
              }
            }
          }
          case "export" -> {
            if (requireLogin(username)) {
              if (parts.length < 2) {
                ui.warn("用法：export <行程ID>");
              } else {
                exportText(parts[1]);
              }
            }
          }
          case "ticket" -> {
            if (parts.length < 2) {
              ui.warn("用法：ticket <景点名>");
            } else {
              showTicket(join(parts, 1));
            }
          }
          default -> ui.warn("未知命令：" + parts[0] + "，输入 help 查看可用命令。");
        }
      } catch (Exception e) {
        // 单条命令失败不应终止整个会话。
        ui.error("命令执行失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
      }
      // 景点查询子循环会替换补全候选，这里恢复主命令集。
      ui.setSuggestions(java.util.List.of(commands));
    }
  }

  private boolean requireLogin(String username) {
    if (username == null) {
      ui.warn("该命令需要登录，请先执行 login <用户名>。");
      return false;
    }
    return true;
  }

  private void printReplHelp() {
    ui.title("可用命令");
    ui.kv("help", "显示本说明");
    ui.kv("login", "<用户名> 登录，密码掩码输入");
    ui.kv("status", "服务状态与模型调用用量（免登录）");
    ui.kv("attractions", "进入景点查询；attractions <关键词> 直接查");
    ui.kv("itineraries", "列出我的行程（需登录）");
    ui.kv("plan", "<目的地> [天数] 生成规划草案，不落库（需登录）");
    ui.kv("votes", "<行程ID> 候选与投票看板（需登录）");
    ui.kv("advice", "<行程ID> 综合决策建议（需登录）");
    ui.kv("export", "<行程ID> 导出文本摘要（需登录）");
    ui.kv("ticket", "<景点名> 购票辅助信息（免登录）");
    ui.kv("exit / q", "退出");
    ui.note("Tab 补全、上下键翻历史；NO_COLOR 或 -Dtravel.cli.noColor=true 可关闭颜色。");
    ui.note("中文编码：保持 Windows 默认代码页即可；若执行了 chcp 65001，"
        + "需给 java 加 -Dstdout.encoding=UTF-8，否则中文会乱码。");
  }

  /**
   * 服务状态与模型用量。字段用 path 读取，服务端没有提供时显示缺省值而不是报错。
   */
  private void showStatus() throws Exception {
    var res = get("/api/agent/status");
    if (res.statusCode() != 200) {
      ui.error("读取状态失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    JsonNode status = json.readTree(res.body());
    ui.title("服务状态");
    ui.kv("模型已配置", status.path("modelConfigured").asBoolean(false) ? "是" : "否");
    ui.kv("服务地址", base);
    JsonNode usage = status.path("usage");
    if (usage.isObject()) {
      ui.section("模型调用用量（进程内计数，重启归零）");
      ui.kv("调用次数", usage.path("calls").asText("0"));
      ui.kv("输入 tokens", usage.path("promptTokens").asText("0"));
      ui.kv("输出 tokens", usage.path("completionTokens").asText("0"));
      ui.kv("缓存命中", usage.path("cachedPromptTokens").asText("0"));
      ui.kv("合计 tokens", usage.path("totalTokens").asText("0"));
      ui.kv("最近调用", usage.path("lastCall").asText("无"));
      ui.note("口径：只统计成功返回的请求，超时与报错不计数，不等同于账单。");
    } else {
      ui.note("服务端未提供用量字段。");
    }
  }

  private void listItineraries() throws Exception {
    var res = get("/api/itineraries");
    if (res.statusCode() != 200) {
      ui.error("读取行程失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    JsonNode items = json.readTree(res.body());
    ui.title("我的行程");
    if (!items.isArray() || items.isEmpty()) {
      ui.note("暂无行程。");
      return;
    }
    var rows = new ArrayList<List<String>>();
    for (JsonNode item : items) {
      rows.add(java.util.List.of(
          text(item, "id"),
          text(item, "title"),
          item.path("archived").asBoolean(false) ? "已归档" : "可编辑"));
    }
    ui.table(java.util.List.of("ID", "标题", "状态"), rows);
  }

  private static String join(String[] parts, int from) {
    return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
  }

  /** 演示注册（已存在则忽略）、登录（拿到会话 Cookie）、查询当前用户。 */
  private void demoAuth(String username, String password) throws Exception {
    // 1) 注册：账号写入服务端 JSON
    var regBody = json.writeValueAsString(Map.of("username", username, "password", password));
    var reg = postJson("/api/account/register", regBody);
    ui.println("注册：HTTP " + reg.statusCode() + " " + reg.body());

    // 2) 登录：服务端建立会话，JSESSIONID 由 CookieManager 自动保存
    String form = "username=" + encode(username) + "&password=" + encode(password);
    var loginReq = HttpRequest.newBuilder(URI.create(base + "/api/login"))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    var login = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
    ui.println("登录：HTTP " + login.statusCode() + " " + login.body());

    // 3) 携带 Cookie 查询当前用户
    var meReq = HttpRequest.newBuilder(URI.create(base + "/api/account/me"))
        .timeout(Duration.ofSeconds(10)).GET().build();
    var me = client.send(meReq, HttpResponse.BodyHandlers.ofString());
    ui.println("当前用户：HTTP " + me.statusCode() + " " + me.body());
    if (me.statusCode() == 200) {
      JsonNode node = json.readTree(me.body());
      ui.println("已以 " + node.get("username").asText() + " 的身份通过会话登录。");
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
      ui.println("行程列表：HTTP " + list.statusCode() + "（跳过预算汇总）");
      return;
    }
    JsonNode items = json.readTree(list.body());
    if (!items.isArray() || items.isEmpty()) {
      ui.println("尚未创建行程，暂无预算可汇总。");
      return;
    }
    String id = items.get(0).get("id").asText();
    String title = items.get(0).get("title").asText();

    var budgetRes = get("/api/itineraries/" + id + "/budget");
    var checksRes = get("/api/itineraries/" + id + "/checks");
    if (budgetRes.statusCode() != 200 || checksRes.statusCode() != 200) {
      ui.println("预算/体检读取失败：HTTP " + budgetRes.statusCode() + " / "
          + checksRes.statusCode());
      return;
    }
    JsonNode budget = json.readTree(budgetRes.body());
    JsonNode checks = json.readTree(checksRes.body());

    ui.println();
    ui.println("== 预算与检查：" + title + " ==");
    ui.println("主要币种：" + budget.get("primaryCurrency").asText()
        + "　计入条目：" + budget.get("itemCount").asInt()
        + "　待填写：" + budget.get("pendingCount").asInt());
    for (JsonNode t : budget.get("totals")) {
      ui.println("  " + t.get("currency").asText() + "  " + money(t.get("amount"))
          + "（" + t.get("itemCount").asInt() + " 条）");
    }
    for (JsonNode t : budget.get("byCategory")) {
      ui.println("  [" + t.get("currency").asText() + "] " + t.get("category").asText()
          + "  " + money(t.get("amount")) + "（" + t.get("itemCount").asInt() + " 条）");
    }
    for (JsonNode note : budget.get("notes")) {
      ui.println("  · " + note.asText());
    }
    if (checks.get("ok").asBoolean()) {
      ui.println("  ✓ 体检未发现问题");
    } else {
      for (JsonNode w : checks.get("warnings")) {
        ui.println("  ✗ [" + w.get("code").asText() + "] " + w.get("message").asText());
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

  // ---- 邀请（V1 §4：邀请 → 接受 / 拒绝） ----

  /** 注册（已存在则忽略）并登录，只建立会话，不打印演示信息。 */
  private void loginOnly(String username, String password) throws Exception {
    postJson("/api/account/register",
        json.writeValueAsString(Map.of("username", username, "password", password)));
    String form = "username=" + encode(username) + "&password=" + encode(password);
    var request = HttpRequest.newBuilder(URI.create(base + "/api/login"))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException("登录失败：HTTP " + response.statusCode() + " " + response.body());
    }
    ui.println("已登录：" + username);
  }

  /** 列出「我收到的待处理邀请」，编号可直接用于 accept / reject。 */
  private void listInvitations() throws Exception {
    var response = get("/api/invitations");
    if (response.statusCode() != 200) {
      ui.println("读取邀请失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    JsonNode list = json.readTree(response.body());
    if (!list.isArray() || list.isEmpty()) {
      ui.println("没有待处理的邀请。");
      return;
    }
    ui.println();
    ui.println("== 我收到的邀请（共 " + list.size() + " 条）==");
    int index = 1;
    for (JsonNode inv : list) {
      String sections = joinSections(inv.get("editSections"));
      ui.println("  " + index + ". " + text(inv, "itineraryTitle") + "　来自 "
          + text(inv, "inviter"));
      ui.println("     可编辑：" + sections + "　状态：" + text(inv, "status"));
      ui.println("     邀请 ID：" + text(inv, "id"));
      index++;
    }
  }

  /** 接受或拒绝一条邀请。 */
  private void respondInvitation(String action, String invitationId) throws Exception {
    String verb = "accept".equalsIgnoreCase(action) ? "accept" : "reject";
    var response = postEmpty("/api/invitations/" + invitationId + "/" + verb);
    JsonNode body = response.body() == null || response.body().isBlank()
        ? null
        : json.readTree(response.body());
    if (response.statusCode() == 200) {
      ui.println("■ " + ("accept".equals(verb) ? "已接受邀请：现在可以查看并编辑 "
          + text(body, "itineraryTitle") : "已拒绝邀请：" + text(body, "itineraryTitle")));
      ui.println("  状态：" + text(body, "status"));
    } else {
      ui.println("操作失败：HTTP " + response.statusCode() + " " + text(body, "error"));
      if (body != null && body.has("status")) {
        ui.println("  邀请当前状态：" + body.get("status").asText());
      }
    }
  }

  /** 创建者发起邀请。sections 为空表示只能查看。 */
  private void sendInvitation(String itineraryId, String invitee, String[] sections)
      throws Exception {
    String body = json.writeValueAsString(Map.of(
        "username", invitee,
        "editSections", sections == null ? new String[0] : sections));
    var response = postJson("/api/itineraries/" + itineraryId + "/invitations", body);
    JsonNode node = response.body() == null || response.body().isBlank()
        ? null
        : json.readTree(response.body());
    if (response.statusCode() == 201 || response.statusCode() == 200) {
      ui.println("■ 已" + (response.statusCode() == 201 ? "发出" : "更新") + "邀请："
          + text(node, "invitee") + " → 「" + text(node, "itineraryTitle") + "」");
      ui.println("  可编辑：" + joinSections(node.get("editSections"))
          + "　状态：" + text(node, "status"));
      ui.println("  邀请 ID：" + text(node, "id"));
      ui.println("  等待对方登录后执行 accept 或 reject；邀请不改变行程版本号。");
    } else {
      ui.println("邀请失败：HTTP " + response.statusCode() + " " + text(node, "error"));
    }
  }

  // ---- V2：协作记录、购票辅助、规划草案 ----

  /** 打印留言与修改记录（V2 第 4 节）。 */
  private void showTimeline(String itineraryId) throws Exception {
    var commentsRes = get("/api/itineraries/" + itineraryId + "/comments");
    var changelogRes = get("/api/itineraries/" + itineraryId + "/changelog?limit=20");
    if (commentsRes.statusCode() != 200 || changelogRes.statusCode() != 200) {
      ui.println("读取协作信息失败：HTTP " + commentsRes.statusCode() + " / "
          + changelogRes.statusCode() + " " + text(json.readTree(
              commentsRes.body().isBlank() ? "{}" : commentsRes.body()), "error"));
      return;
    }

    JsonNode comments = json.readTree(commentsRes.body());
    JsonNode changelog = json.readTree(changelogRes.body());

    ui.println();
    ui.println("== 留言（共 " + comments.size() + " 条）==");
    if (comments.isEmpty()) {
      ui.println("  （暂无留言）");
    } else {
      comments.forEach(c -> ui.println("  [" + localTime(text(c, "createdAt")) + "] "
          + text(c, "author") + "：" + text(c, "content")));
    }

    ui.println();
    ui.println("== 修改记录（最近 " + changelog.size() + " 条，倒序）==");
    if (changelog.isEmpty()) {
      ui.println("  （暂无记录）");
    } else {
      changelog.forEach(e -> ui.println("  [" + localTime(text(e, "at")) + "] v"
          + e.get("version").asLong() + " · " + text(e, "actor") + " · "
          + text(e, "section") + "：" + text(e, "summary")));
    }
    ui.println();
    ui.println("留言与记录都不改变行程版本号。");
  }

  /** 发表留言。 */
  private void addComment(String itineraryId, String content) throws Exception {
    if (content == null || content.isBlank()) {
      ui.println("留言内容不能为空。");
      return;
    }
    var response = postJson("/api/itineraries/" + itineraryId + "/comments",
        json.writeValueAsString(Map.of("content", content.trim())));
    JsonNode body = response.body() == null || response.body().isBlank()
        ? null : json.readTree(response.body());
    if (response.statusCode() == 201) {
      ui.println("■ 留言已发表：" + text(body, "content"));
      ui.println("  作者 " + text(body, "author") + "　" + localTime(text(body, "createdAt")));
      ui.println("  留言不改变行程版本号。");
    } else {
      ui.println("发表失败：HTTP " + response.statusCode() + " " + text(body, "error"));
    }
  }

  /**
   * 打印某景点的购票辅助信息。
   *
   * <p>没有收录入口时如实说明，不输出任何猜测链接；余票永远标注「未核验」。
   */
  private void showTicket(String name) throws Exception {
    if (name == null || name.isBlank()) {
      ui.println("请提供景点名称，例如：ticket 西湖");
      return;
    }
    var response = get("/api/tickets?name=" + encode(name.trim()));
    JsonNode body = response.body() == null || response.body().isBlank()
        ? null : json.readTree(response.body());
    if (response.statusCode() != 200) {
      ui.println("查询失败：HTTP " + response.statusCode() + " " + text(body, "error"));
      return;
    }

    ui.println();
    ui.println("== 购票辅助：" + text(body, "attractionName") + " ==");
    ui.println("状态：" + text(body, "status")
        + "　余票已核验：" + (body.path("stockVerified").asBoolean() ? "是" : "否"));
    ui.println("说明：" + text(body, "notice"));
    ui.println("来源：" + text(body, "source") + "　查询时间：" + localTime(text(body, "queriedAt")));

    JsonNode channels = body.path("channels");
    if (channels.isArray() && !channels.isEmpty()) {
      channels.forEach(ch -> {
        ui.println();
        ui.println("  · " + text(ch, "channelName"));
        ui.println("    入口：" + text(ch, "officialUrl"));
        ui.println("    核验日期：" + (ch.hasNonNull("verifiedAt")
            ? ch.get("verifiedAt").asText() : "未记录（使用前请再次确认）"));
        JsonNode types = ch.path("ticketTypes");
        if (types.isArray() && !types.isEmpty()) {
          types.forEach(t -> ui.println("    票种：" + text(t, "name") + "　"
              + (t.hasNonNull("price")
                  ? money(t.get("price")) + " " + text(t, "currency")
                  : "价格未知")
              + (t.hasNonNull("priceNote") ? "（" + t.get("priceNote").asText() + "）" : "")));
        } else {
          ui.println("    票种：未查询到");
        }
        JsonNode rules = ch.path("rules");
        if (rules.isArray() && !rules.isEmpty()) {
          var list = new java.util.ArrayList<String>();
          rules.forEach(r -> list.add(r.asText()));
          ui.println("    规则：" + String.join("；", list));
        }
      });
    } else {
      ui.println("  没有经核验的官方入口，请自行进入景区官方渠道购买。");
    }
    ui.println();
    ui.println("本系统不生成猜测链接，也不承诺余票。");
  }

  /** 打印一份规划草案（不落库，需用户另行确认保存）。 */
  private void showPlan(String destination, Integer days) throws Exception {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("destination", destination);
    if (days != null) {
      payload.put("days", days);
    }
    var response = postJson("/api/agent/plan", json.writeValueAsString(payload));
    JsonNode body = response.body() == null || response.body().isBlank()
        ? null : json.readTree(response.body());
    if (response.statusCode() != 200) {
      ui.println("生成草案失败：HTTP " + response.statusCode() + " " + text(body, "error"));
      return;
    }

    ui.println();
    ui.println("== 规划草案：" + destination + " ==");

    JsonNode candidates = body.path("candidates");
    ui.println("候选景点（共 " + candidates.size() + " 条，来自本地知识库）：");
    if (candidates.isEmpty()) {
      ui.println("  （无）");
    } else {
      int index = 1;
      for (JsonNode a : candidates) {
        ui.println("  " + index + ". " + text(a, "name") + "　"
            + (a.hasNonNull("city") ? a.get("city").asText() : "")
            + "　票价：" + (a.hasNonNull("ticket") ? a.get("ticket").asText() : "未知")
            + "　开放：" + (a.hasNonNull("openTime") ? a.get("openTime").asText() : "未知")
            + "　ID：" + text(a, "id"));
        index++;
      }
    }

    JsonNode weather = body.path("weather");
    ui.println();
    if (weather.isMissingNode() || weather.isNull()) {
      ui.println("天气预报：未获取");
    } else {
      ui.println("天气预报：" + text(weather, "city") + "（"
          + text(weather, "reportTime") + " 发布）");
      weather.path("days").forEach(d -> ui.println("  " + text(d, "date") + " "
          + text(d, "dayWeather") + " " + text(d, "dayTemp") + "°C "
          + text(d, "dayWind") + text(d, "dayPower")));
    }

    ui.println();
    ui.println("建议：");
    ui.println(body.hasNonNull("advice")
        ? "  " + body.get("advice").asText().replace("\n", "\n  ")
        : "  （未生成文字建议）");

    JsonNode notices = body.path("notices");
    if (notices.isArray() && !notices.isEmpty()) {
      ui.println();
      ui.println("需要你知道：");
      notices.forEach(n -> ui.println("  · " + n.asText()));
    }

    JsonNode sources = body.path("sources");
    if (sources.isArray() && !sources.isEmpty()) {
      var list = new java.util.ArrayList<String>();
      sources.forEach(s -> list.add(s.asText()));
      ui.println();
      ui.println("数据来源：" + String.join("、", list));
    }
    ui.println();
    ui.println("草案未保存。确认后再写入正式方案。");
  }

  // ---- V3：候选投票、综合建议、归档导出 ----

  /** 候选与投票结果。票数并列时不给「最高」，与接口的口径一致。 */
  private void showBoard(String itineraryId) throws Exception {
    HttpResponse<String> res = get("/api/itineraries/" + itineraryId + "/candidates");
    if (res.statusCode() != 200) {
      ui.println("读取候选失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    JsonNode board = json.readTree(res.body());
    ui.println();
    ui.println("候选与投票（版本 v" + board.get("itineraryVersion").asLong()
        + "，参与者 " + board.get("participantCount").asInt()
        + " 人，已投 " + board.get("voterCount").asInt()
        + " 人，共 " + board.get("totalVotes").asInt() + " 票）");

    String leaderId = board.hasNonNull("leaderId") ? board.get("leaderId").asText() : "";
    JsonNode list = board.get("candidates");
    if (!list.isArray() || list.isEmpty()) {
      ui.println("  还没有候选项。");
    } else {
      int index = 1;
      for (JsonNode tally : list) {
        JsonNode c = tally.get("candidate");
        boolean leading = c.get("id").asText().equals(leaderId);
        ui.println("  " + index++ + ". " + text(c, "name")
            + "（" + text(c, "kind") + " · " + text(c, "proposedBy") + " 提出）"
            + "支持 " + tally.get("support").asInt()
            + " / 反对 " + tally.get("oppose").asInt()
            + " / 弃权 " + tally.get("abstain").asInt()
            + " · 净支持 " + tally.get("score").asInt()
            + (leading ? "  ← 当前最高" : ""));
        ui.println("     ID: " + c.get("id").asText());
      }
    }

    JsonNode notes = board.get("notes");
    if (notes.isArray() && !notes.isEmpty()) {
      ui.println("说明：");
      notes.forEach(n -> ui.println("  · " + n.asText()));
    }
  }

  /** 添加候选项。任何能看该行程的人都能提，不需要板块编辑权。 */
  private void addCandidate(String itineraryId, String name, String kind) throws Exception {
    var body = json.writeValueAsString(Map.of("kind", kind, "name", name));
    HttpResponse<String> res = postJson("/api/itineraries/" + itineraryId + "/candidates", body);
    JsonNode node = json.readTree(res.body());
    if (res.statusCode() == 201) {
      ui.println("已添加候选：" + text(node, "name") + "（ID " + text(node, "id") + "）");
    } else {
      ui.println("添加失败：HTTP " + res.statusCode() + " " + text(node, "error"));
    }
  }

  /** 投票或改票。 */
  private void castVote(String itineraryId, String candidateId, String choice) throws Exception {
    String value = choice.toUpperCase();
    if (!value.equals("SUPPORT") && !value.equals("OPPOSE") && !value.equals("ABSTAIN")) {
      ui.println("投票选项只能是 support / oppose / abstain");
      return;
    }
    var body = json.writeValueAsString(Map.of("choice", value));
    HttpResponse<String> res = postJson(
        "/api/itineraries/" + itineraryId + "/candidates/" + candidateId + "/votes", body);
    JsonNode board = json.readTree(res.body());
    if (res.statusCode() == 200) {
      ui.println("已投票：" + value + "。当前共 " + board.get("totalVotes").asInt() + " 票");
      showBoard(itineraryId);
    } else {
      ui.println("投票失败：HTTP " + res.statusCode() + " " + text(board, "error"));
    }
  }

  /** 综合决策建议：风险与建议由服务端规则给出，模型文字可能为空。 */
  private void showAdvice(String itineraryId) throws Exception {
    HttpResponse<String> res = postJson("/api/agent/itineraries/" + itineraryId + "/advice", "{}");
    if (res.statusCode() != 200) {
      ui.println("生成建议失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    JsonNode report = json.readTree(res.body());
    ui.println();
    ui.println("综合建议（版本 v" + report.get("version").asLong()
        + "，生成于 " + localTime(text(report, "generatedAt")) + "）");

    JsonNode risks = report.get("risks");
    ui.println();
    ui.println("风险：" + (risks == null ? 0 : risks.size()) + " 条");
    if (risks != null) {
      risks.forEach(r -> {
        ui.println("  [" + text(r, "severity") + "] " + text(r, "message"));
        ui.println("        依据：" + text(r, "basis")
            + (r.hasNonNull("suggestion") ? "　建议：" + text(r, "suggestion") : ""));
      });
    }

    JsonNode suggestions = report.get("suggestions");
    ui.println();
    ui.println("调整建议（规则）：");
    if (suggestions == null || suggestions.isEmpty()) {
      ui.println("  （暂无）");
    } else {
      suggestions.forEach(s -> ui.println("  · " + s.asText()));
    }

    ui.println();
    ui.println("模型建议：");
    ui.println(report.hasNonNull("advice")
        ? indent(report.get("advice").asText())
        : "  （本次没有模型文字建议，原因见下方提示）");

    JsonNode notices = report.get("notices");
    if (notices != null && !notices.isEmpty()) {
      ui.println();
      ui.println("提示：");
      notices.forEach(n -> ui.println("  · " + n.asText()));
    }
    ui.println();
    ui.println("建议不会自动保存。确认后再修改正式行程。");
  }

  /** 导出文本摘要：原样打印，由用户自行重定向到文件。 */
  private void exportText(String itineraryId) throws Exception {
    HttpResponse<String> res = get("/api/itineraries/" + itineraryId + "/export");
    if (res.statusCode() != 200) {
      ui.println("导出失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    ui.println();
    ui.println(res.body());
  }

  /** 归档或取消归档。 */
  private void setArchived(String itineraryId, boolean archived) throws Exception {
    HttpResponse<String> res = postEmpty(
        "/api/itineraries/" + itineraryId + (archived ? "/archive" : "/unarchive"));
    if (res.statusCode() != 200) {
      ui.println("操作失败：HTTP " + res.statusCode() + " " + res.body());
      return;
    }
    JsonNode node = json.readTree(res.body());
    ui.println(node.get("archived").asBoolean()
        ? "已归档（只读），版本 v" + node.get("version").asLong()
        : "已取消归档，可继续编辑，版本 v" + node.get("version").asLong());
  }

  /** 给多行文本加两格缩进，便于和上面的标题区分。 */
  private static String indent(String block) {
    if (block == null || block.isBlank()) {
      return "  （空）";
    }
    StringBuilder sb = new StringBuilder();
    for (String line : block.split("\n")) {
      sb.append("  ").append(line).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  private String joinSections(JsonNode sections) {
    if (sections == null || !sections.isArray() || sections.isEmpty()) {
      return "（仅查看）";
    }
    var parts = new java.util.ArrayList<String>();
    sections.forEach(s -> parts.add(s.asText()));
    return String.join(", ", parts);
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return "未知";
    }
    return node.get(field).asText();
  }

  /** ISO 时间转本地可读格式；无法解析时原样返回。 */
  private static String localTime(String iso) {
    if (iso == null || iso.isBlank() || "未知".equals(iso)) {
      return "未知";
    }
    try {
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
          .withZone(java.time.ZoneId.systemDefault())
          .format(java.time.Instant.parse(iso));
    } catch (RuntimeException e) {
      return iso;
    }
  }

  private static Integer parseIntOrNull(String raw) {
    try {
      return Integer.valueOf(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private HttpResponse<String> postEmpty(String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.noBody()).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
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

  /**
   * 解析密码参数，避免明文密码出现在进程列表与 shell 历史（F8）。
   *
   * <p>支持三种写法：{@code env:VAR} 从环境变量读；{@code -} 或 {@code stdin} 优先取
   * {@code TRAVEL_CLI_PASSWORD}、否则掩码/管道输入；字面量仅为兼容旧脚本保留，不推荐。
   * 纯逻辑与输入端解耦，便于脱离终端做单元测试。
   */
  static String resolvePassword(String arg, java.util.function.Function<String, String> env,
      java.util.function.Supplier<String> input) {
    if (arg == null || arg.equals("-") || arg.equalsIgnoreCase("stdin")) {
      String fromEnv = env.apply("TRAVEL_CLI_PASSWORD");
      if (fromEnv != null && !fromEnv.isEmpty()) {
        return fromEnv;
      }
      String typed = input.get();
      if (typed == null || typed.isEmpty()) {
        throw new IllegalArgumentException(
            "未提供密码：请设置环境变量 TRAVEL_CLI_PASSWORD，或通过 stdin 传入");
      }
      return typed;
    }
    if (arg.startsWith("env:")) {
      String var = arg.substring(4);
      if (var.isEmpty()) {
        var = "TRAVEL_CLI_PASSWORD";
      }
      String value = env.apply(var);
      if (value == null || value.isEmpty()) {
        throw new IllegalArgumentException("环境变量 " + var + " 未设置或为空，无法读取密码");
      }
      return value;
    }
    return arg;
  }

  /** 一次性命令用：输入端按是否交互选择掩码读取或管道一行。 */
  static String resolvePassword(String arg, ConsoleUI ui) {
    return resolvePassword(arg, System::getenv, () -> {
      if (ui.isInteractive()) {
        return ui.readMasked("  密码: ");
      }
      try {
        return new java.io.BufferedReader(
            new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      } catch (java.io.IOException e) {
        return null;
      }
    });
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
