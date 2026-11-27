package com.travelassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.common.api.HealthResponse;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 旅行助手控制台客户端。
 * 默认只做连通性健康检查；传入用户名和密码时会演示完整的
 * 注册 → 登录（服务端会话 + JSESSIONID Cookie）→ 查询当前用户 流程。
 */
public class TravelClient {
  private static final Duration CHAT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

  private static final class ChatStreamState {
    private JsonNode completion;
    private String error;
    private boolean tokenSeen;
    private final StringBuilder text = new StringBuilder();
  }

  private record TranscriptEntry(String role, String text) {}

  private final String base;
  private final HttpClient client;
  private final ObjectMapper json = new ObjectMapper();
  private final ConsoleUI ui;
  private final List<Map<String, String>> chatHistory = new ArrayList<>();
  private final List<TranscriptEntry> transcriptHistory = new ArrayList<>();
  private JsonNode chatState;
  private JsonNode latestChatResult;

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
    // 有真实终端时默认进入 REPL；伪终端可用 JVM 属性显式启用，脚本用法保持不变。
    boolean forceInteractive = Boolean.getBoolean("travel.cli.forceInteractive");
    boolean repl = args.length <= 1 && (System.console() != null || forceInteractive);
    try (ConsoleUI ui = repl
        ? ConsoleUI.interactive("help", "exit", "q", "login", "register", "logout", "status",
            "attractions", "itineraries", "plan", "votes", "advice", "export", "ticket",
            "products", "orders", "order", "drive", "parking", "route", "weather", "chat",
            "reset", "api")
        : ConsoleUI.plain()) {
      var cli = new TravelClient(base, ui);
      String service = cli.checkHealth();
      if (repl) {
        ui.enterFullscreen();
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
      } else if (args.length >= 4 && "api".equalsIgnoreCase(args[1])) {
        cli.apiRequest(args[2], args[3], args.length > 4
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "");
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
        ui.println("交互式命令台：java -jar travel-client/target/travel-client-0.1.0-SNAPSHOT.jar " + base);
        ui.println("服务端 API 通用入口：java -jar travel-client.jar " + base + " api <METHOD> <路径> [JSON]");
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
    String[] commands = {"help", "/help", "exit", "/exit", "q", "quit", "login", "register", "logout", "status",
        "attractions", "itineraries", "plan", "votes", "advice", "export", "ticket",
        "products", "orders", "order", "drive", "parking", "route", "weather", "chat", "reset", "/reset",
        "details", "/details", "history", "/history", "search", "/search", "clear", "/clear", "api"};
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
      if (cmd.startsWith("/")) cmd = cmd.substring(1);
      if ("?".equals(cmd)) cmd = "help";
      if (cmd.equals("exit") || cmd.equals("q") || cmd.equals("quit")) {
        ui.note("已退出。");
        break;
      }
      try {
        switch (cmd) {
          case "help" -> printReplHelp(parts.length > 1 && "all".equalsIgnoreCase(parts[1]));
          case "details" -> showLatestDetails();
          case "history" -> showConversationHistory();
          case "search" -> {
            String query = line.substring(parts[0].length()).strip();
            if (query.isBlank()) ui.warn("用法：/search <关键词>，搜索本次会话记录。");
            else searchConversation(query);
          }
          case "login" -> {
            if (parts.length < 2) {
              ui.warn("用法：login 用户名（直接输入实际用户名，不要带尖括号）");
              break;
            }
            String loginName = parts[1];
            if (loginName.replace("\\", "").matches("<[^<>]+>")) {
              ui.warn("尖括号是帮助里的占位符，不要原样输入。示例：login demo_admin");
              break;
            }
            String password = ui.readMasked("  密码: ");
            if (password == null || password.isBlank()) {
              ui.warn("密码不能为空。");
              break;
            }
            loginOnly(loginName, password);
            if (!loginName.equals(username)) resetConversation();
            username = loginName;
          }
          case "register" -> {
            if (parts.length < 2) {
              ui.warn("用法：register 用户名（直接输入实际用户名，不要带尖括号；例如 register demo_admin）");
              break;
            }
            String newUsername = parts[1];
            if (newUsername.replace("\\", "").matches("<[^<>]+>")) {
              ui.warn("尖括号是帮助里的占位符，不要原样输入。示例：register demo_admin");
              break;
            }
            if (!newUsername.matches("[A-Za-z0-9_]{3,32}")) {
              ui.warn("用户名需为 3-32 位字母、数字或下划线；示例：register demo_admin");
              break;
            }
            String password = ui.readMasked("  新密码: ");
            if (password == null || password.isBlank()) {
              ui.warn("密码不能为空。");
              break;
            }
            if (password.length() < 8) {
              ui.warn("密码至少 8 位，请重新执行 register 并输入更长的密码。");
              break;
            }
            var response = postJson("/api/account/register",
                json.writeValueAsString(Map.of("username", newUsername, "password", password)));
            if (response.statusCode() == 201 || response.statusCode() == 200) {
              ui.success("注册成功，请执行 login " + newUsername);
            } else {
              String error = response.body();
              try {
                error = json.readTree(response.body()).path("error").asText(error);
              } catch (Exception ignored) {
                // 保留服务端原响应，便于排查非 JSON 错误。
              }
              ui.error("注册失败：" + error);
            }
          }
          case "logout" -> {
            var response = postEmpty("/api/logout");
            username = null;
            resetConversation();
            ui.note("已退出登录（HTTP " + response.statusCode() + "）。");
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
          case "products" -> showDemoProducts();
          case "orders" -> {
            if (requireLogin(username)) listDemoOrders();
          }
          case "order" -> {
            if (parts.length < 2) {
              ui.warn("用法：order products | create <景点ID或-> | <商品编码> | <数量> | <景点名> | show|pay|cancel|refund <订单ID>");
            } else if ("products".equalsIgnoreCase(parts[1])) {
              showDemoProducts();
            } else if ("create".equalsIgnoreCase(parts[1])) {
              if (requireLogin(username)) createDemoOrder(line.substring(line.indexOf(parts[1]) + parts[1].length()).strip());
            } else if (parts.length >= 3 && requireLogin(username)) {
              handleDemoOrder(parts[1], parts[2], parts.length >= 4 ? parts[3] : null);
            } else if (parts.length < 3) {
              ui.warn("请提供订单 ID。");
            }
          }
          case "drive" -> showDrivingSegment(line.substring(parts[0].length()).strip());
          case "parking" -> showParking(line.substring(parts[0].length()).strip());
          case "route" -> showRoute(parts);
          case "weather" -> {
            if (parts.length < 2) ui.warn("用法：weather <城市>");
            else showWeather(join(parts, 1));
          }
          case "chat" -> {
            if (requireLogin(username)) {
              if (parts.length < 2) ui.warn("用法：chat <问题>");
              else sendChat(line.substring(parts[0].length()).strip());
            }
          }
          case "reset" -> {
            resetConversation();
            ui.success("已清空当前 CLI 对话上下文。");
          }
          case "clear" -> {
            resetConversation();
            ui.success("已清空当前 CLI 对话上下文；本次会话记录仍可用 /history 查看。");
          }
          case "api" -> handleApiCommand(line);
          default -> {
            if (requireLogin(username)) sendChat(line);
          }
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

  private void printReplHelp(boolean all) {
    if (!all) {
      ui.title("快速帮助");
      ui.success("登录后直接说旅行需求即可对话，例如：我想去黄鹤楼，安排两天行程。");
      ui.kv("register", "用户名 注册账号，例如 register demo_admin");
      ui.kv("login", "用户名 登录账号");
      ui.kv("logout", "退出登录");
      ui.kv("attractions", "搜索景点；也可输入 attractions <关键词>");
      ui.kv("ticket", "景点名 查询购票信息");
      ui.kv("plan", "目的地 [天数] 生成行程草案（需登录）");
      ui.kv("status", "查看服务状态");
      ui.kv("/details", "展开最近一轮工具输出、完整攻略路线与费用来源");
      ui.kv("/search", "关键词 搜索本次进程内的对话记录");
      ui.kv("/history", "分页查看本次进程内的对话记录");
      ui.kv("help all", "查看全部命令");
      ui.kv("exit", "退出 CLI");
      ui.note("回答会逐段显示；有结构化结果时，末尾会附精简表格或行程摘要。");
      ui.note(ui.supportsLineEditing()
          ? "支持行内编辑、上下键历史、Ctrl+R 历史搜索；输入 / 后按 Tab 浏览命令，Alt+V 粘贴图片。"
          : "密码输入不回显（不显示星号），按 Enter 提交。");
      return;
    }
    ui.title("全部命令");
    ui.success("登录后可直接输入旅行需求开始对话；未识别的命令会作为自然语言发送给助手。");
    ui.kv("help", "快速帮助；help all 显示本说明");
    ui.kv("register", "用户名 新用户注册，密码掩码输入；例如 register demo_admin");
    ui.kv("login", "用户名 登录，密码掩码输入；尖括号仅为文档占位符");
    ui.kv("logout", "注销当前服务端会话");
    ui.kv("status", "服务状态与模型调用用量（免登录）");
    ui.kv("attractions", "进入景点查询；attractions <关键词> 直接查");
    ui.kv("itineraries", "列出我的行程（需登录）");
    ui.kv("plan", "<目的地> [天数] 生成规划草案，不落库（需登录）");
    ui.kv("votes", "<行程ID> 候选与投票看板（需登录）");
    ui.kv("advice", "<行程ID> 综合决策建议（需登录）");
    ui.kv("export", "<行程ID> 导出文本摘要（需登录）");
    ui.kv("ticket", "<景点名> 购票辅助信息（免登录）");
    ui.section("演示交易（需登录；全部为沙箱模拟）");
    ui.kv("products", "列出演示商品与固定演示金额");
    ui.kv("order create", "<景点ID或-> | <商品编码> | <数量> | <景点名> 创建订单并显示演示二维码");
    ui.kv("order pay", "<订单ID> [eventId] 模拟支付确认，服务端自动签发演示票");
    ui.kv("orders", "列出当前账号订单");
    ui.kv("order show/cancel/refund/fail", "查询或处理演示订单（退款和失败均为模拟）");
    ui.section("地图与路线");
    ui.kv("drive", "<起点> | <终点> | [城市] 驾车路段与目的地周边停车场");
    ui.kv("parking", "<目的地> | [城市] 查询停车 POI");
    ui.kv("route", "<步行|公交> <起点经度,纬度> <终点经度,纬度> [城市]");
    ui.kv("weather", "<城市> 查询天气");
    ui.kv("chat", "<问题> 对话 Agent（需登录）");
    ui.kv("reset", "清空本次 CLI 会话的对话历史和上下文");
    ui.kv("api", "<METHOD> </api/...> [JSON] 调用现有 REST API");
    ui.kv("exit / q", "退出");
    ui.note("输入 / 浏览快捷命令；Ctrl+R 搜索本次命令历史，/search 搜索本次对话；/details 展开最近一次工具输出。");
    ui.note("Alt+V 将剪贴板图片附加到问题；需要配置支持视觉输入的模型，图片只随当前请求发送、不写入本地记录。");
    ui.note("中文乱码时，请用项目脚本启动：.\\tools\\run-client.ps1；脚本会设置 UTF-8 终端编码。");
  }

  private void showDemoProducts() throws Exception {
    var response = get("/api/demo-orders/products");
    if (response.statusCode() != 200) {
      ui.error("读取演示商品失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    JsonNode products = json.readTree(response.body());
    var rows = new ArrayList<List<String>>();
    products.forEach(p -> rows.add(List.of(text(p, "code"), text(p, "name"),
        text(p, "demoUnitAmount") + " " + text(p, "currency"), text(p, "mode"))));
    ui.title("沙箱演示商品");
    ui.table(List.of("编码", "商品", "演示金额/件", "模式"), rows);
    ui.warn("金额仅用于沙箱交易演示，不是景区票价、库存报价或有效门票价格。");
  }

  private void createDemoOrder(String raw) throws Exception {
    String[] fields = raw.split("\\|", 4);
    if (fields.length < 4) {
      ui.warn("用法：order create <景点ID或-> | DEMO_ADMISSION | <数量> | <景点名称>");
      return;
    }
    var request = new java.util.LinkedHashMap<String, Object>();
    if (!fields[0].isBlank() && !fields[0].strip().equals("-")) request.put("attractionId", fields[0].strip());
    request.put("productCode", fields[1].strip());
    try {
      request.put("quantity", Integer.parseInt(fields[2].strip()));
    } catch (NumberFormatException e) {
      ui.warn("数量必须是整数。");
      return;
    }
    request.put("attractionName", fields[3].strip());
    var response = postJson("/api/demo-orders", json.writeValueAsString(request));
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      ui.error("创建订单失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    printDemoOrder(json.readTree(response.body()), true);
  }

  private void listDemoOrders() throws Exception {
    var response = get("/api/demo-orders");
    if (response.statusCode() != 200) {
      ui.error("读取订单失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    JsonNode orders = json.readTree(response.body());
    ui.title("我的演示订单");
    if (!orders.isArray() || orders.isEmpty()) {
      ui.note("暂无演示订单。");
      return;
    }
    var rows = new ArrayList<List<String>>();
    orders.forEach(o -> rows.add(List.of(text(o, "id"), text(o, "attractionName"),
        text(o, "amount") + " CNY", text(o, "orderStatus"), text(o, "paymentStatus"),
        text(o, "ticketStatus"))));
    ui.table(List.of("订单ID", "景点", "金额", "订单", "支付", "出票"), rows);
    ui.warn("全部为沙箱模拟状态。");
  }

  private void handleDemoOrder(String action, String id, String eventId) throws Exception {
    switch (action.toLowerCase(java.util.Locale.ROOT)) {
      case "show" -> {
        var response = get("/api/demo-orders/" + encode(id));
        if (response.statusCode() == 200) printDemoOrder(json.readTree(response.body()), false);
        else ui.error("订单查询失败：HTTP " + response.statusCode() + " " + response.body());
      }
      case "pay" -> {
        var current = get("/api/demo-orders/" + encode(id));
        if (current.statusCode() != 200) {
          ui.error("订单查询失败：HTTP " + current.statusCode() + " " + current.body());
          return;
        }
        JsonNode order = json.readTree(current.body());
        Map<String, Object> payment = Map.of("eventId", eventId == null ? "CLI-SANDBOX-" + UUID.randomUUID() : eventId,
            "amount", order.path("amount").decimalValue(), "currency", "CNY");
        var response = postJson("/api/demo-orders/" + encode(id) + "/simulate-payment",
            json.writeValueAsString(payment));
        if (response.statusCode() == 200) printDemoOrder(json.readTree(response.body()), false);
        else ui.error("沙箱支付确认失败：HTTP " + response.statusCode() + " " + response.body());
      }
      case "cancel", "refund" -> {
        var response = postEmpty("/api/demo-orders/" + encode(id) + "/" + action.toLowerCase());
        if (response.statusCode() == 200) printDemoOrder(json.readTree(response.body()), false);
        else ui.error("订单操作失败：HTTP " + response.statusCode() + " " + response.body());
      }
      case "fail" -> {
        var response = postEmpty("/api/demo-orders/" + encode(id) + "/simulate-failure");
        if (response.statusCode() == 200) printDemoOrder(json.readTree(response.body()), false);
        else ui.error("模拟支付失败状态写入失败：HTTP " + response.statusCode() + " " + response.body());
      }
      default -> ui.warn("用法：order show|pay|cancel|refund <订单ID>");
    }
  }

  private void printDemoOrder(JsonNode order, boolean showQr) {
    ui.title("演示订单");
    ui.kv("订单ID", text(order, "id"));
    ui.kv("景点", text(order, "attractionName"));
    ui.kv("商品", text(order, "productName") + " × " + text(order, "quantity"));
    ui.kv("金额", text(order, "amount") + " CNY（固定演示金额）");
    ui.kv("订单状态", text(order, "orderStatus"));
    ui.kv("支付状态", text(order, "paymentStatus"));
    if (order.hasNonNull("expiresAt")) ui.kv("支付截止", localTime(text(order, "expiresAt")));
    ui.kv("出票状态", text(order, "ticketStatus"));
    if (order.hasNonNull("ticketId")) ui.kv("演示票号", text(order, "ticketId"));
    if (showQr && order.hasNonNull("paymentUri")) {
      ui.section("沙箱支付二维码（不能扫码真实付款）");
      ui.note("载荷：" + text(order, "paymentUri"));
      printQr(text(order, "paymentUri"));
      ui.note("确认此订单的模拟支付：order pay " + text(order, "id"));
    }
    ui.warn(text(order, "notice"));
  }

  private void printQr(String payload) {
    try {
      BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 33, 33,
          Map.of(EncodeHintType.MARGIN, 1));
      for (int y = 0; y < matrix.getHeight(); y += 2) {
        StringBuilder row = new StringBuilder("  ");
        for (int x = 0; x < matrix.getWidth(); x++) {
          boolean top = matrix.get(x, y);
          boolean bottom = y + 1 < matrix.getHeight() && matrix.get(x, y + 1);
          row.append(top ? (bottom ? '█' : '▀') : (bottom ? '▄' : ' '));
        }
        ui.println(row.toString());
      }
    } catch (Exception e) {
      ui.warn("无法绘制二维码，请使用载荷文本核对沙箱流程。");
    }
  }

  private void showDrivingSegment(String raw) throws Exception {
    String[] fields = raw.split("\\|", 3);
    if (fields.length < 2) {
      ui.warn("用法：drive <起点> | <终点> | [城市]");
      return;
    }
    String path = "/api/location/drive?origin=" + encode(fields[0].strip())
        + "&destination=" + encode(fields[1].strip());
    if (fields.length == 3 && !fields[2].isBlank()) path += "&city=" + encode(fields[2].strip());
    var response = get(path);
    if (response.statusCode() != 200) {
      ui.error("驾车与停车查询失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    JsonNode result = json.readTree(response.body());
    JsonNode route = result.path("route");
    ui.title("自驾路段：" + text(result, "origin") + " → " + text(result, "destination"));
    ui.kv("预估距离", text(route, "distanceText"));
    ui.kv("预估耗时", text(route, "durationText"));
    ui.kv("路线来源", text(route, "source"));
    ui.kv("查询时间", localTime(text(route, "queriedAt")));
    var steps = new ArrayList<List<String>>();
    route.path("steps").forEach(s -> steps.add(List.of(text(s, "road"),
        text(s, "instruction"), text(s, "distanceMeters") + " m")));
    if (!steps.isEmpty()) ui.table(List.of("道路", "指引", "距离"), steps);
    printParkingResult(result.path("parking"));
  }

  private void showParking(String raw) throws Exception {
    String[] fields = raw.split("\\|", 2);
    if (fields.length == 0 || fields[0].isBlank()) {
      ui.warn("用法：parking <目的地> | [城市]");
      return;
    }
    String path = "/api/location/parking?destination=" + encode(fields[0].strip());
    if (fields.length == 2 && !fields[1].isBlank()) path += "&city=" + encode(fields[1].strip());
    var response = get(path);
    if (response.statusCode() != 200) {
      ui.error("停车场查询失败：HTTP " + response.statusCode() + " " + response.body());
      return;
    }
    printParkingResult(json.readTree(response.body()));
  }

  private void printParkingResult(JsonNode parking) {
    ui.section("目的地附近停车场（POI）");
    JsonNode lots = parking.path("parkingLots");
    var rows = new ArrayList<List<String>>();
    lots.forEach(p -> rows.add(List.of(text(p, "name"),
        p.hasNonNull("distanceMeters") ? text(p, "distanceMeters") + " m" : "未知",
        text(p, "address"), text(p, "price"), text(p, "openingHours"), text(p, "availableSpaces"))));
    if (rows.isEmpty()) ui.note("没有返回停车 POI。");
    else ui.table(List.of("停车场", "距离", "地址", "收费", "营业时间", "实时空位"), rows);
    if (lots.isArray()) for (JsonNode place : lots) {
      ui.note("来源：" + text(place, "source") + "；查询时间：" + localTime(text(place, "checkedAt")));
    }
    ui.warn(text(parking, "notice"));
    ui.note("实时空位数据：" + (parking.path("liveAvailabilityAvailable").asBoolean(false) ? "有" : "无"));
  }

  private void showRoute(String[] parts) throws Exception {
    if (parts.length < 4) {
      ui.warn("用法：route <步行|公交> <起点经度,纬度> <终点经度,纬度> [城市]；自驾用 drive <起点> | <终点> | [城市]");
      return;
    }
    String mode = switch (parts[1].toLowerCase(java.util.Locale.ROOT)) {
      case "walk", "walking", "步行" -> "WALKING";
      case "bus", "transit", "公交", "地铁" -> "TRANSIT";
      default -> null;
    };
    if (mode == null) {
      ui.warn("route 支持步行或公交；自驾请用 drive <起点> | <终点> | [城市]，以同时显示路线和停车 POI。");
      return;
    }
    String path = "/api/location/route?mode=" + mode + "&origin=" + encode(parts[2])
        + "&destination=" + encode(parts[3]);
    if (parts.length >= 5) path += "&city=" + encode(join(parts, 4));
    showJsonResponse("路线规划", get(path));
  }

  private void showWeather(String city) throws Exception {
    showJsonResponse("天气预报", get("/api/location/weather?city=" + encode(city)));
  }

  private void sendChat(String message) throws Exception {
    if (message == null || message.isBlank()) {
      ui.warn("请输入想聊的旅行需求。");
      return;
    }
    List<String> images = ui.takePendingImages();
    String prompt = message.replaceAll("\\[图片\\d+已附加\\]", "").strip();
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("message", prompt);
    requestBody.put("history", List.copyOf(chatHistory));
    requestBody.put("state", chatState);
    requestBody.put("images", images);
    var request = HttpRequest.newBuilder(URI.create(base + "/api/chat/stream"))
        .timeout(CHAT_REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
        .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      String body;
      try (InputStream stream = response.body()) {
        body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      showJsonResponse("对话 Agent", response.statusCode(), body);
      return;
    }

    ChatStreamState state = new ChatStreamState();
    ui.title("旅行助手");
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String eventName = "message";
      StringBuilder eventData = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          handleChatStreamEvent(eventName, eventData.toString(), state);
          eventName = "message";
          eventData.setLength(0);
        } else if (line.startsWith("event:")) {
          eventName = line.substring(6).strip();
        } else if (line.startsWith("data:")) {
          if (eventData.length() > 0) eventData.append('\n');
          eventData.append(line.substring(5).stripLeading());
        }
      }
      if (eventData.length() > 0) {
        handleChatStreamEvent(eventName, eventData.toString(), state);
      }
    } catch (java.io.IOException e) {
      if (state.tokenSeen) ui.println();
      ui.error("连接中断，回复可能未完整返回。请重试。");
      return;
    }

    if (state.error != null) {
      if (state.tokenSeen) ui.println();
      ui.error("回复生成中断：" + state.error);
      return;
    }
    if (state.completion == null) {
      if (state.tokenSeen) ui.println();
      ui.error("连接已结束，但助手没有返回完整答复。请重试。");
      return;
    }

    JsonNode result = state.completion;
    latestChatResult = result.deepCopy();
    String reply = result.path("reply").asText("");
    if (result.path("state").isObject()) chatState = result.path("state").deepCopy();
    String body = (state.text.length() > 0 ? state.text.toString() : reply)
        .replace("**", "").strip();
    if (!body.isEmpty()) {
      ui.paragraph(body);
    } else {
      String note = result.path("note").asText("");
      ui.warn(note.isBlank() ? "助手暂时没有返回内容。" : note);
    }
    printChatCards(result.path("cards"));
    rememberChat("user", prompt.isBlank() && !images.isEmpty() ? "[附图问题]" : prompt);
    if (!body.isEmpty()) rememberChat("assistant", body);
  }

  /** 将 Agent 的结构化结果压缩成终端可读的小卡片，不输出原始 JSON 或内部过程。 */
  private void printChatCards(JsonNode cards) {
    if (cards == null || !cards.isArray() || cards.isEmpty()) return;
    boolean heading = false;
    int shownCards = 0;
    for (JsonNode card : cards) {
      if (shownCards >= 4) break;
      String type = card.path("type").asText("");
      JsonNode data = card.path("data");
      if (!heading && ("places".equals(type) || "weather".equals(type)
          || "route".equals(type) || "vote".equals(type) || "envelope".equals(type))) {
        ui.section("相关资料");
        heading = true;
      }
      boolean rendered = switch (type) {
        case "intake" -> printPlanningIntakeCard();
        case "places" -> printPlacesCard(data);
        case "weather" -> printWeatherCard(data);
        case "route" -> printRouteCard(data);
        case "vote" -> printVoteCard(data);
        case "envelope" -> printEnvelopeCard(card.path("intent").asText(""), data);
        default -> false;
      };
      if (rendered) {
        shownCards++;
      }
    }
  }

  private boolean printPlacesCard(JsonNode data) {
    if (!data.isArray() || data.isEmpty()) return false;
    List<List<String>> rows = new ArrayList<>();
    List<JsonNode> shownPlaces = new ArrayList<>();
    for (JsonNode place : data) {
      if (rows.size() >= 5) break;
      String name = cardText(place, "name");
      if (name.isBlank()) continue;
      String ticket = cardText(place, "adultTicket", "");
      if (ticket.isBlank() || "未知".equals(ticket) || "未返回/未核验".equals(ticket)) {
        ticket = cardText(place, "ticket", "未返回/未核验");
      }
      if (!"verified".equalsIgnoreCase(place.path("ticketStatus").asText())
          && !ticket.contains("过期") && !ticket.contains("未返回")) {
        ticket += "（未核验）";
      }
      rows.add(List.of(name, cardText(place, "rating", "—"),
          shortCell(ticket, 28), shortCell(cardText(place, "openTime", "—"), 20)));
      shownPlaces.add(place);
    }
    if (rows.isEmpty()) return false;
    ui.subheading("景点（最多显示 5 个）");
    ui.table(List.of("名称", "评分", "门票/成人票", "开放时间"), rows);
    for (JsonNode place : shownPlaces) {
      String name = cardText(place, "name", "景点");
      ui.note("  " + name + " 地址：" + cardText(place, "address", "未返回/未核验"));
    }
    return true;
  }

  private boolean printWeatherCard(JsonNode data) {
    JsonNode days = data.path("days");
    if (!days.isArray() || days.isEmpty()) return false;
    List<List<String>> rows = new ArrayList<>();
    for (JsonNode day : days) {
      if (rows.size() >= 4) break;
      rows.add(List.of(cardText(day, "date", "—"),
          cardText(day, "dayWeather", "—") + " " + cardText(day, "dayTemp", "—") + "°",
          cardText(day, "nightWeather", "—") + " " + cardText(day, "nightTemp", "—") + "°",
          cardText(day, "dayWind", "—") + " " + cardText(day, "dayPower", "") + "级"));
    }
    ui.subheading("天气预报 · 非实时天气 · " + cardText(data, "city", "未知城市"));
    ui.table(List.of("日期", "白天", "夜间", "风"), rows);
    return true;
  }

  private boolean printRouteCard(JsonNode data) {
    String origin = cardText(data, "origin");
    String destination = cardText(data, "destination");
    if ("未知".equals(origin) || "未知".equals(destination)) return false;
    ui.subheading("路线 · " + origin + " → " + destination);
    ui.note("  " + cardText(data, "modeLabel", "出行") + " · "
        + cardText(data, "distanceText", "距离未知") + " · 约 "
        + cardText(data, "durationText", "耗时未知"));
    JsonNode steps = data.path("steps");
    int count = 0;
    if (steps.isArray()) {
      for (JsonNode step : steps) {
        if (count >= 3) break;
        String instruction = cardText(step, "instruction", "");
        if (!instruction.isBlank()) {
          ui.note("  · " + shortCell(instruction, 54));
          count++;
        }
      }
    }
    return true;
  }

  private boolean printVoteCard(JsonNode data) {
    JsonNode ranking = data.path("ranking");
    String winner = cardText(data, "winner", "");
    if (winner.isBlank() && (!ranking.isArray() || ranking.isEmpty())) return false;
    if (!winner.isBlank()) ui.subheading("投票推荐 · " + winner);
    if (ranking.isArray() && !ranking.isEmpty()) {
      List<List<String>> rows = new ArrayList<>();
      for (JsonNode item : ranking) {
        if (rows.size() >= 3) break;
        rows.add(List.of(cardText(item, "planId", "方案"),
            cardText(item, "score", "—"), cardText(item, "voterCount", "0")));
      }
      ui.table(List.of("方案", "加权分", "有效票"), rows);
    }
    return true;
  }

  private boolean printEnvelopeCard(String intent, JsonNode data) {
    JsonNode facts = data.path("result").path("facts");
    if (!facts.isArray()) return false;
    if ("FOOD_RECOMMEND".equals(intent)) {
      List<List<String>> rows = new ArrayList<>();
      List<JsonNode> shownFoods = new ArrayList<>();
      for (JsonNode fact : facts) {
        JsonNode value = fact.path("value");
        if (!"FOOD".equals(value.path("category").asText())) continue;
        if (rows.size() >= 4) break;
        String name = cardText(value, "name", "");
        if (name.isBlank()) continue; // 无名占位结果不进候选，避免「餐馆/未知元」假行
        rows.add(List.of(name,
            cardText(value, "pricePerPersonCny", "未知") + " 元/人",
            cardText(value, "rating", "—"), shortCell(cardText(value, "distanceMeters", "—"), 12) + " 米"));
        shownFoods.add(value);
      }
      if (rows.isEmpty()) return false;
      ui.subheading("餐馆候选（最多显示 4 家；距离为地图参考点直线距离）");
      ui.table(List.of("名称", "人均", "评分", "距离"), rows);
      for (JsonNode value : shownFoods) {
        JsonNode tags = value.path("tags");
        String tagText = tags.isArray()
            ? java.util.stream.StreamSupport.stream(tags.spliterator(), false)
                .map(JsonNode::asText).limit(4).collect(java.util.stream.Collectors.joining("、"))
            : "";
        if (!tagText.isBlank()) {
          ui.note("  " + cardText(value, "name", "餐馆") + " · 标签 " + tagText);
        }
      }
      return true;
    }
    if ("PLAN_DRAFT".equals(intent)) {
      for (JsonNode fact : facts) {
        JsonNode draft = fact.path("value");
        if (!"PLAN_DRAFT".equals(draft.path("category").asText())) continue;
        if (draft.path("guideText").isTextual()) {
          List<List<String>> rows = new ArrayList<>();
          JsonNode days = draft.path("dailySchedules");
          if (days.isArray()) {
            for (JsonNode day : days) {
              List<String> names = new ArrayList<>();
              JsonNode items = day.path("items");
              if (items.isArray()) {
                for (JsonNode item : items) {
                  String name = cardText(item, "name", "");
                  if (!name.isBlank()) names.add(name);
                }
              }
              rows.add(List.of("第 " + cardText(day, "dayIndex", String.valueOf(rows.size() + 1)) + " 天",
                  cardText(day, "date", "日期待定"), names.isEmpty() ? "暂无可排景点" : String.join("、", names)));
            }
          }
          ui.subheading("旅行攻略草案 · 逐日摘要");
          if (!rows.isEmpty()) ui.table(List.of("日期", "出行日", "安排"), rows);
          printSelectionBasis(days);
          JsonNode guide = draft.path("guide");
          if (guide.isObject()) {
            ui.note("费用参考：已确认 " + cardText(guide, "knownSubtotalCny", "未知")
                + " 元；含参考价格 " + cardText(guide, "referenceSubtotalCny", "未知")
                + " 元；另有 " + cardText(guide, "unknownCostCount", "未知") + " 项未覆盖。"
                + "未覆盖费用不按零元计算。");
          }
          printGuideBudgetSummary(guide);
          ui.note("输入 /details 展开完整路线、费用分类、天气覆盖和工具查询记录。");
          return true;
        }
        JsonNode days = draft.path("dailySchedules");
        if (!days.isArray() || days.isEmpty()) return false;
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode day : days) {
          JsonNode items = day.path("items");
          if (!items.isArray() || items.isEmpty()) continue;
          StringBuilder names = new StringBuilder();
          for (JsonNode item : items) {
            if (names.length() > 0) names.append("、");
            names.append(cardText(item, "name", "景点"));
            if (names.length() > 70) {
              names.append("…");
              break;
            }
          }
          rows.add(List.of("第 " + cardText(day, "dayIndex", String.valueOf(rows.size() + 1)) + " 天",
              shortCell(names.toString(), 42)));
          if (rows.size() >= 6) break;
        }
        if (rows.isEmpty()) return false;
        ui.subheading("逐日行程草案");
        ui.table(List.of("日期", "安排"), rows);
        return true;
      }
    }
    if ("TICKET_ADVICE".equals(intent)) {
      JsonNode guidance = data.path("ticketGuidance");
      JsonNode channels = guidance.path("channels");
      boolean rendered = false;
      String method = cardText(guidance, "method", "");
      if (!method.isBlank()) {
        ui.note("购票建议：" + shortCell(method, 70));
        rendered = true;
      }
      if (channels.isArray()) {
        for (JsonNode channel : channels) {
          if (!channel.path("name").asText("").isBlank()) {
            ui.note("官方入口：" + cardText(channel, "name")
                + "（CLI 不展开长链接，按名称到官方渠道打开）");
            rendered = true;
            if (rendered) break;
          }
        }
      }
      return rendered;
    }
    return false;
  }

  private void showLatestDetails() {
    if (latestChatResult == null) {
      ui.warn("本次会话还没有可展开的工具结果。");
      return;
    }
    StringBuilder detail = new StringBuilder("本轮工具查询记录");
    JsonNode traces = latestChatResult.path("trace");
    if (traces.isArray() && !traces.isEmpty()) {
      for (JsonNode trace : traces) detail.append("\n- ")
          .append(cardText(trace, "label", cardText(trace, "tool", "工具")))
          .append(trace.path("ok").asBoolean() ? "：已返回" : "：未成功")
          .append(trace.path("summary").asText("").isBlank() ? "" : "\n  " + trace.path("summary").asText(""));
    } else detail.append("\n- 本轮没有返回可展示的工具调用记录");

    JsonNode cards = latestChatResult.path("cards");
    if (cards.isArray()) {
      for (JsonNode card : cards) {
        String intent = card.path("intent").asText("");
        JsonNode data = card.path("data");
        if (!"PLAN_DRAFT".equals(intent)) continue;
        JsonNode draft = findPlanDraft(data);
        if (draft == null) continue;
        JsonNode guide = draft.path("guide");
        detail.append("\n\n費用分类（全团 CNY）");
        detail.append("\n类别 | 已确认 | 参考 | 实价 | 未知项");
        appendBudgetBreakdown(detail, guide.path("costs"));
        detail.append("\n\n完整逐日路线、来源和待核验事项\n")
            .append(draft.path("guideText").asText("本轮未返回完整攻略正文。"));
        JsonNode basisDays = draft.path("dailySchedules");
        StringBuilder basis = new StringBuilder("\n\n来源与筛选依据（全部）");
        boolean anyBasis = false;
        if (basisDays.isArray()) {
          for (JsonNode day : basisDays) {
            JsonNode items = day.path("items");
            if (!items.isArray()) continue;
            for (JsonNode item : items) {
              String reason = cardText(item, "selectionBasis", "");
              if (reason.isBlank()) continue;
              basis.append("\n- ").append(cardText(item, "name", "景点")).append("：").append(reason);
              anyBasis = true;
            }
          }
        }
        if (anyBasis) detail.append(basis);
        else detail.append("\n\n来源与筛选依据：本轮未返回逐景点筛选依据。");
        JsonNode weather = draft.path("weather");
        if (weather.isObject()) {
          detail.append("\n\n天气核验范围：来源 ").append(cardText(weather, "source", "未返回/未核验"))
              .append("；预报发布时间 ").append(cardText(weather, "reportTime", "未返回/未核验"))
              .append("；查询时间 ").append(cardText(weather, "queriedAt", "未返回/未核验"));
          JsonNode days = weather.path("days");
          if (days.isArray()) for (JsonNode day : days) detail.append("\n- ")
              .append(cardText(day, "date", "日期未知")).append("：")
              .append(cardText(day, "dayWeather", "白天天气未知")).append(" / ")
              .append(cardText(day, "nightWeather", "夜间天气未知"));
        } else detail.append("\n\n天气核验：本轮没有天气结果，不能据此推荐晴雨日期。");
      }
    }
    ui.viewText("旅行助手 · 最近一次详情", detail.toString());
  }

  private static JsonNode findPlanDraft(JsonNode envelope) {
    JsonNode facts = envelope.path("result").path("facts");
    if (!facts.isArray()) return null;
    for (JsonNode fact : facts) {
      JsonNode value = fact.path("value");
      if ("PLAN_DRAFT".equals(value.path("category").asText())) return value;
    }
    return null;
  }

  private static void appendBudgetBreakdown(StringBuilder out, JsonNode costs) {
    for (List<String> row : budgetRows(costs)) out.append("\n").append(String.join(" | ", row));
  }

  private static List<List<String>> budgetRows(JsonNode costs) {
    if (!costs.isArray()) return List.of(List.of("费用账本未返回", "—", "—", "—", "—"));
    class Sum { java.math.BigDecimal confirmed = java.math.BigDecimal.ZERO; java.math.BigDecimal reference = java.math.BigDecimal.ZERO; java.math.BigDecimal realtime = java.math.BigDecimal.ZERO; int unknown; }
    Map<String, Sum> grouped = new LinkedHashMap<>();
    for (JsonNode cost : costs) {
      String category = switch (cost.path("category").asText("")) {
        case "TRANSPORT" -> "交通"; case "HOTEL" -> "住宿"; case "FOOD" -> "餐饮";
        case "TICKET" -> "门票"; default -> "其他";
      };
      Sum sum = grouped.computeIfAbsent(category, ignored -> new Sum());
      JsonNode amount = cost.path("groupAmountCny");
      if (!amount.isNumber()) { sum.unknown++; continue; }
      java.math.BigDecimal value = amount.decimalValue();
      switch (cost.path("priceType").asText("")) {
        case "USER_CONFIRMED" -> sum.confirmed = sum.confirmed.add(value);
        case "REALTIME" -> sum.realtime = sum.realtime.add(value);
        case "REFERENCE" -> sum.reference = sum.reference.add(value);
        default -> sum.unknown++;
      }
    }
    List<List<String>> rows = new ArrayList<>();
    for (var entry : grouped.entrySet()) {
      Sum sum = entry.getValue();
      rows.add(List.of(entry.getKey(), amountOrDash(sum.confirmed), amountOrDash(sum.reference),
          amountOrDash(sum.realtime), String.valueOf(sum.unknown)));
    }
    return List.copyOf(rows);
  }

  private void printGuideBudgetSummary(JsonNode guide) {
    JsonNode costs = guide.path("costs");
    if (!costs.isArray() || costs.isEmpty()) return;
    ui.subheading("预算分类（全团；实价、参考价与未知分开）");
    ui.table(List.of("类别", "已确认", "参考", "实价", "未知项"), budgetRows(costs));
  }

  private void printSelectionBasis(JsonNode days) {
    if (!days.isArray()) return;
    int shown = 0;
    boolean headered = false;
    for (JsonNode day : days) {
      JsonNode items = day.path("items");
      if (!items.isArray()) continue;
      for (JsonNode item : items) {
        String reason = cardText(item, "selectionBasis", "");
        if (reason.isBlank()) continue;
        String shownReason = shortCell(stripSourceDisclosure(reason), 80);
        if (shownReason.isBlank()) continue;
        if (shown >= 8) { ui.note("  其余景点的排序依据详见 /details。"); return; }
        if (!headered) { ui.subheading("筛选依据 · 排序与匹配"); headered = true; }
        ui.note("  " + cardText(item, "name", "景点") + "：" + shownReason);
        shown++;
      }
    }
  }

  private static String amountOrDash(java.math.BigDecimal amount) {
    return amount.signum() == 0 ? "—" : amount.stripTrailingZeros().toPlainString() + " 元";
  }

  private void showConversationHistory() {
    if (transcriptHistory.isEmpty()) { ui.warn("本次进程还没有对话记录。"); return; }
    StringBuilder history = new StringBuilder();
    for (int i = 0; i < transcriptHistory.size(); i++) {
      TranscriptEntry entry = transcriptHistory.get(i);
      history.append(i + 1).append(". ").append("user".equals(entry.role()) ? "你" : "助手")
          .append("\n").append(entry.text()).append("\n\n");
    }
    ui.viewText("本次 CLI 会话历史 · /search 可检索", history.toString());
  }

  private void searchConversation(String query) {
    String needle = query.toLowerCase(java.util.Locale.ROOT);
    StringBuilder matches = new StringBuilder();
    int found = 0;
    for (int i = transcriptHistory.size() - 1; i >= 0; i--) {
      TranscriptEntry entry = transcriptHistory.get(i);
      if (!entry.text().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
      matches.append(i + 1).append(". ").append("user".equals(entry.role()) ? "你" : "助手")
          .append("\n").append(entry.text()).append("\n\n");
      if (++found >= 100) break;
    }
    ui.viewText("搜索「" + query + "」· " + found + " 条命中", found == 0 ? "没有找到匹配的本次会话记录。" : matches.toString());
  }

  private boolean printPlanningIntakeCard() {
    ui.section("生成攻略前，请补充这些条件");
    ui.table(List.of("信息", "填写内容"), List.of(
        List.of("出发地与日期", "从哪里出发；哪天或哪个月份，不确定写待定"),
        List.of("同行人员", "总人数；老人/儿童与步行限制"),
        List.of("预算", "全团大致上限"),
        List.of("交通方式", "自驾、公共交通或包车"),
        List.of("偏好", "兴趣、必去点；忌口和住宿可选")));
    ui.note("可分行回复，或写‘待定’；助手会先复述条件，等你确认后才查资料排攻略。"
        + "需要快速预览时可回复‘先出初稿’。");
    return true;
  }

  private static String cardText(JsonNode node, String field) {
    return cardText(node, field, "未知");
  }

  private static String cardText(JsonNode node, String field, String fallback) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) return fallback;
    String text = value.isValueNode() ? value.asText() : value.toString();
    return text.isBlank() ? fallback : text;
  }

  private static String shortCell(String value, int width) {
    return ConsoleUI.truncateToWidth(value == null ? "" : value, width);
  }

  /** 抹掉服务端筛选依据文本里的字段来源/信息日期披露，只留排序与匹配理由；完整依据仍见 /details。 */
  static String stripSourceDisclosure(String reason) {
    if (reason == null) return "";
    return reason
        // 来源「百度 …」/ 字段来源：「…」
        .replaceAll("(?:字段)?来源\\s*[=＝:：]?\\s*「[^」]*」", "")
        // 来源=百度 [值=…; 信息日期=…] 或 来源：高德
        .replaceAll("(?:字段)?来源\\s*[=＝:：]\\s*[^\\s，,；;、「」]+(?:\\s*\\[[^\\]]*\\])?", "")
        .replaceAll("信息日期\\s*[=＝:：]\\s*[^\\s，,；;、\\[\\]]*\\]?", "")
        .replaceAll("[，,]{2,}", "，")
        .replaceAll("[；;]{2,}", "；")
        .replaceAll("[，,]\\s*[；;]", "；")
        .replaceAll("[，,；;、]+\\s*$", "")
        .replaceAll("^[，,；;、]+\\s*", "")
        .strip();
  }

  private void handleChatStreamEvent(String eventName, String rawData, ChatStreamState state)
      throws java.io.IOException {
    if (rawData == null || rawData.isBlank()) return;
    JsonNode data = json.readTree(rawData);
    switch (eventName) {
      case "progress" -> {
        String message = data.path("message").asText("");
        if (!message.isBlank()) ui.note(message);
      }
      case "token" -> {
        String text = data.path("text").asText("");
        if (!text.isEmpty()) {
          // 缓冲而非逐 token 直绘：避免 Markdown 标记被截断成半截星号、正文连成一坨。
          // 结束后统一以段落渲染，全屏视图也能正确换行与加粗。
          state.text.append(text);
          state.tokenSeen = true;
        }
      }
      case "done" -> state.completion = data;
      case "error" -> state.error = data.path("message").asText("回复生成中断，请重试。");
      default -> {
        // 忽略未知事件，便于以后给流式接口增加兼容字段。
      }
    }
  }

  private void rememberChat(String role, String text) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.isEmpty()) return;
    transcriptHistory.add(new TranscriptEntry(role, trimmed));
    while (transcriptHistory.size() > 2000) transcriptHistory.removeFirst();
    if (trimmed.length() > 240) trimmed = trimmed.substring(0, 240);
    chatHistory.add(Map.of("role", role, "text", trimmed));
    while (chatHistory.size() > 8) chatHistory.remove(0);
  }

  private void resetConversation() {
    chatHistory.clear();
    chatState = null;
    latestChatResult = null;
  }

  private void handleApiCommand(String line) throws Exception {
    String[] fields = line.split("\\s+", 4);
    if (fields.length < 3) {
      ui.warn("用法：api <METHOD> </api/...> [JSON body]");
      return;
    }
    apiRequest(fields[1], fields[2], fields.length == 4 ? fields[3] : "");
  }

  private void apiRequest(String method, String path, String body) throws Exception {
    String verb = method.toUpperCase(java.util.Locale.ROOT);
    if (!path.startsWith("/api/") || path.contains("\r") || path.contains("\n")) {
      ui.warn("API 路径必须是相对的 /api/... 路径。");
      return;
    }
    if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(verb)) {
      ui.warn("支持 GET、POST、PUT、PATCH、DELETE。");
      return;
    }
    var builder = HttpRequest.newBuilder(URI.create(base + encodeUri(path)))
        .timeout(Duration.ofSeconds(30));
    if (!"GET".equals(verb) && !"DELETE".equals(verb)) builder.header("Content-Type", "application/json");
    HttpRequest request = ("GET".equals(verb) || "DELETE".equals(verb))
        ? builder.method(verb, HttpRequest.BodyPublishers.noBody()).build()
        : builder.method(verb, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
    showJsonResponse("API " + verb + " " + path,
        client.send(request, HttpResponse.BodyHandlers.ofString()));
  }

  private void showJsonResponse(String title, HttpResponse<String> response) throws Exception {
    showJsonResponse(title, response.statusCode(), response.body());
  }

  private void showJsonResponse(String title, int statusCode, String body) throws Exception {
    ui.title(title + " · HTTP " + statusCode);
    if (body == null || body.isBlank()) {
      ui.note("（空响应）");
      return;
    }
    try {
      ui.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(body)));
    } catch (Exception e) {
      ui.println(body);
    }
  }

  private static String encodeUri(String path) {
    StringBuilder encoded = new StringBuilder();
    for (int i = 0; i < path.length();) {
      int cp = path.codePointAt(i);
      if (cp <= 0x7f) encoded.append(cp == ' ' ? "%20" : Character.toString(cp));
      else for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
        encoded.append(String.format("%%%02X", b & 0xff));
      }
      i += Character.charCount(cp);
    }
    return encoded.toString();
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

  /** 只登录；注册由显式 register 命令负责。 */
  void loginOnly(String username, String password) throws Exception {
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
    ui.println("候选景点（共 " + candidates.size() + " 条，各自来源见攻略）：");
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
    if (!weather.isMissingNode() && !weather.isNull()) {
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
    return postJson(path, body, Duration.ofSeconds(10));
  }

  private HttpResponse<String> postJson(String path, String body, Duration timeout)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(timeout)
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
