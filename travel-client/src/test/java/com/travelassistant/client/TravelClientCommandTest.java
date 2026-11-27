package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
class TravelClientCommandTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path historyDir;
  private String previousHistory;
  @org.junit.jupiter.api.BeforeEach void isolateHistory() {
    previousHistory=System.getProperty("travel.cli.history-file");
    System.setProperty("travel.cli.history-file",historyDir.resolve("history.json").toString());
  }
  @org.junit.jupiter.api.AfterEach void restoreHistory() {
    if(previousHistory==null) System.clearProperty("travel.cli.history-file");
    else System.setProperty("travel.cli.history-file",previousHistory);
  }

  @Test
  void unknownCommandDoesNotRegisterAnAccount() throws Exception {
    var registrations = new AtomicInteger();
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.countJson("/api/account/register", 400, Map.of("error", "unexpected"), registrations);

      assertEquals(2, runCaptured(server.base(), "statsu", "extra").exitCode());
      assertEquals(0, registrations.get());
    }
  }

  @Test
  void unauthenticatedApiReturnsAuthenticationExitCode() throws Exception {
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.json("/api/itineraries", 401, Map.of("authenticated", false));

      assertEquals(3, runCaptured(server.base(), "api", "GET", "/api/itineraries").exitCode());
    }
  }

  @Test
  void invalidPlanDaysAreRejectedBeforePlanning() throws Exception {
    var plans = new AtomicInteger();
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 200);
      server.countJson("/api/agent/plan", 200, Map.of(), plans);

      assertEquals(2, runCaptured(server.base(), "plan", "tester", "test-password", "杭州", "abc").exitCode());
      assertEquals(0, plans.get());
    }
  }

  @Test
  void attractionKeywordAndIndexPrintDetails() throws Exception {
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.json("/api/attractions", 200, Map.of("matched", 1, "returned", 1,
          "items", List.of(Map.of("id", "west-lake", "name", "西湖", "city", "杭州", "source", "测试源"))));
      server.json("/api/attractions/west-lake", 200,
          Map.of("id", "west-lake", "name", "西湖", "city", "杭州", "source", "测试源"));

      var result = runCaptured(server.base(), "attractions", "西湖", "1");
      assertEquals(0, result.exitCode());
      assertTrue(result.output().contains("景点详情"));
      assertTrue(result.output().contains("西湖"));
      assertFalse(result.output().contains("输入序号查看详情，或输入新的城市/景点名继续查询。"));
    }
  }

  @Test
  void planDefaultsToReadableSummaryAndSupportsJson() throws Exception {
    var candidates = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 12; i++) {
      candidates.add(Map.of("id", "id-" + i, "name", "景点-" + i, "city", "杭州",
          "ticket", "未知", "openTime", "未知", "source", "测试源"));
    }
    var plan = Map.of(
        "request", Map.of("destination", "杭州", "days", 2, "travelers", 2, "budget", 1000, "currency", "CNY"),
        "candidates", candidates,
        "dailySchedules", List.of(Map.of("dayIndex", 1, "date", "2026-12-01",
            "items", List.of(Map.of("name", "景点-1")))),
        "questions", List.of(),
        "notices", List.of("票价与开放时间仍需核验"),
        "sources", List.of("测试源"),
        "advice", "第一行建议\n第二行建议\n第三行建议\n第四行建议\n第五行建议");
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 200);
      server.json("/api/agent/plan", 200, plan);

      var summary = runCaptured(server.base(), "plan", "tester", "test-password", "杭州", "2");
      assertEquals(0, summary.exitCode());
      assertTrue(summary.output().contains("候选景点 · 共 12 个"));
      assertTrue(summary.output().contains("另有 4 个候选"));
      assertTrue(summary.output().contains("逐日安排"));
      assertTrue(summary.output().contains("--json"));
      assertFalse(summary.output().contains("/details"));
      assertFalse(summary.output().contains("景点-9"));

      var json = runCaptured(server.base(), "plan", "tester", "test-password", "杭州", "2", "--json");
      assertEquals(0, json.exitCode());
      var parsed = JSON.readTree(json.output());
      assertEquals(12, parsed.path("candidates").size());
      assertEquals("景点-9", parsed.path("candidates").get(8).path("name").asText());
    }
  }

  @Test
  void helpAndUnknownCommandDoNotRequireServer() throws Exception {
    var help = runCaptured("http://127.0.0.1:1", "help");
    assertEquals(0, help.exitCode());
    assertTrue(help.output().contains("Travel Assistant"));

    var unknown = runCaptured("http://127.0.0.1:1", "statsu");
    assertEquals(2, unknown.exitCode());
    assertTrue(unknown.output().contains("未知命令"));
  }

  @Test
  void exportWritesOnlyDocumentBodyToStdout() throws Exception {
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 200);
      server.context("/api/itineraries/trip-1/export", exchange -> {
        TestServer.respond(exchange, 200, "行程正文\n第二行".getBytes(StandardCharsets.UTF_8));
      });

      var result = runCaptured(server.base(), "export", "tester", "test-password", "trip-1");
      assertEquals(0, result.exitCode());
      assertEquals("行程正文\n第二行\n", result.output());
    }
  }

  @Test
  void slashCommandSuggestionFindsTypo() {
    assertEquals("details", TravelClient.nearestCommand("detials"));
    assertEquals("history", TravelClient.nearestCommand("histroy"));
  }

  @Test
  void invitationTypoIsRejectedWithoutCallingServer() throws Exception {
    var invitationActions = new AtomicInteger();
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 200);
      server.countJson("/api/invitations/inv-1/reject", 200, Map.of(), invitationActions);

      assertEquals(2, runCaptured(server.base(), "invitations", "tester", "test-password", "accect", "inv-1").exitCode());
      assertEquals(0, invitationActions.get());
    }
  }

  @Test
  void attractionServerFailureKeepsHttpExitCode() throws Exception {
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.json("/api/attractions", 500, Map.of("error", "probe failure"));

      assertEquals(1, runCaptured(server.base(), "attractions", "西湖", "1").exitCode());
    }
  }

  @Test
  void failedAttractionSearchKeepsPreviousSelection() throws Exception {
    var queries = new AtomicInteger();
    var details = new AtomicInteger();
    try (var server = new TestServer()) {
      server.context("/api/attractions", exchange -> {
        if (queries.incrementAndGet() == 1) {
          TestServer.respond(exchange, 200, JSON.writeValueAsBytes(Map.of("matched", 1, "returned", 1,
              "items", List.of(Map.of("id", "old-place", "name", "旧景点", "city", "杭州", "source", "测试源")))));
        } else {
          TestServer.respond(exchange, 500, JSON.writeValueAsBytes(Map.of("error", "probe failure")));
        }
      });
      server.context("/api/attractions/old-place", exchange -> {
        details.incrementAndGet();
        TestServer.respond(exchange, 200, JSON.writeValueAsBytes(Map.of("id", "old-place", "name", "旧景点")));
      });
      var output = new ByteArrayOutputStream();
      var original = System.out;
      try (var print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(print);
        try (var ui = ConsoleUI.plain()) {
          var console = new AttractionConsole(server.base(), ui);
          console.searchAndPrint("第一次");
          console.searchAndPrint("失败查询");
          var show = AttractionConsole.class.getDeclaredMethod("showDetailByIndex", int.class);
          show.setAccessible(true);
          show.invoke(console, 1);
        }
      } finally {
        System.setOut(original);
      }
      assertEquals(1, details.get());
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("旧景点"));
    }
  }

  @Test
  void budgetCardWarnsWhenReferenceExceedsLimit() throws Exception {
    var output = new ByteArrayOutputStream();
    var original = System.out;
    try (var print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(print);
      try (var ui = ConsoleUI.plain()) {
        var client = new TravelClient("http://127.0.0.1:1", ui);
        var warn = TravelClient.class.getDeclaredMethod("printBudgetWarning", JsonNode.class, JsonNode.class);
        warn.setAccessible(true);
        warn.invoke(client,
            JSON.valueToTree(Map.of("budget", 1000)),
            JSON.valueToTree(Map.of("referenceSubtotalCny", 3786.0)));
      }
    } finally {
      System.setOut(original);
    }
    String text = output.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("参考小计 3786.0 元已超过预算 1000 元"));
    assertTrue(text.contains("超出 2786.0 元"));
  }

  @Test
  void planDetailsKeepIndividualCostEvidence() {
    var plan = JSON.valueToTree(Map.of(
        "request", Map.of("destination", "杭州"),
        "candidates", List.of(),
        "dailySchedules", List.of(),
        "guide", Map.of(
            "costs", List.of(Map.of("description", "第1天午餐", "groupAmountCny", 120,
                "priceType", "USER_CONFIRMED", "source", "用户确认", "informationDate", "2026-11-25")),
            "knownSubtotalCny", 120, "referenceSubtotalCny", 0, "unknownCostCount", 0),
        "notices", List.of(), "sources", List.of()));

    String details = TravelClient.planDetails(plan);
    assertTrue(details.contains("第1天午餐"));
    assertTrue(details.contains("用户确认"));
    assertTrue(details.contains("2026-11-25"));
  }

  @Test
  void clearingSessionRemovesTranscriptHistory() throws Exception {
    try (var ui = ConsoleUI.plain()) {
      var client = new TravelClient("http://127.0.0.1:1", ui);
      var remember = TravelClient.class.getDeclaredMethod("rememberChat", String.class, String.class);
      remember.setAccessible(true);
      remember.invoke(client, "user", "上一个账号的行程");
      remember.invoke(client, "assistant", "上一个账号的建议");
      var historyField = TravelClient.class.getDeclaredField("transcriptHistory");
      historyField.setAccessible(true);
      assertEquals(2, ((List<?>) historyField.get(client)).size());

      var clear = TravelClient.class.getDeclaredMethod("clearSessionHistory");
      clear.setAccessible(true);
      clear.invoke(client);
      assertTrue(((List<?>) historyField.get(client)).isEmpty());
    }
  }

  @Test
  void imageOnlyMessageIsSentWithoutText() throws Exception {
    var requestBody = new AtomicReference<String>();
    try (var server = new TestServer()) {
      server.context("/api/chat/stream", exchange -> {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        TestServer.respond(exchange, 200,
            "event: done\ndata: {\"reply\":\"收到图片\",\"cards\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
      });
      var output = new ByteArrayOutputStream();
      var original = System.out;
      try (var print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(print);
        try (var ui = ConsoleUI.plain()) {
          var client = new TravelClient(server.base(), ui);
          var send = TravelClient.class.getDeclaredMethod("sendChat", String.class, List.class);
          send.setAccessible(true);
          send.invoke(client, "[图片1已附加]", List.of("data:image/jpeg;base64,AA=="));
        }
      } finally {
        System.setOut(original);
      }
      JsonNode body = JSON.readTree(requestBody.get());
      assertEquals("", body.path("message").asText());
      assertEquals(1, body.path("images").size());
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("收到图片"));
    }
  }

  @Test
  void stalledChatStreamStopsAfterIdleTimeout() throws Exception {
    try (var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.context("/api/chat/stream", exchange -> {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().flush();
        try {
          Thread.sleep(1500);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
      });
      var output = new ByteArrayOutputStream();
      var original = System.out;
      TravelClient client = null;
      long started = System.nanoTime();
      try (var print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(print);
        try (var ui = ConsoleUI.plain()) {
          client = new TravelClient(server.base(), ui);
          var remember = TravelClient.class.getDeclaredMethod("rememberChat", String.class, String.class);
          remember.setAccessible(true);
          remember.invoke(client, "user", "原有问题");
          remember.invoke(client, "assistant", "原有回答");
          client.sendChat("测试", Duration.ofMillis(150), Duration.ofSeconds(2));
        }
      } finally {
        System.setOut(original);
      }
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      assertTrue(elapsedMillis < 1200, "stream should stop promptly, elapsed=" + elapsedMillis);
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("对话响应超时"));
      var historyField = TravelClient.class.getDeclaredField("transcriptHistory");
      historyField.setAccessible(true);
      var history = (List<?>) historyField.get(client);
      assertEquals(4, history.size());
      assertTrue(history.stream().map(Object::toString).anyMatch(text -> text.contains("[未完成]")));
      var modelHistoryField = TravelClient.class.getDeclaredField("chatHistory");
      modelHistoryField.setAccessible(true);
      var modelHistory = (List<?>) modelHistoryField.get(client);
      assertEquals(4, modelHistory.size());
      var rollback = TravelClient.class.getDeclaredMethod("removeFailedChatAttempt");
      rollback.setAccessible(true);
      rollback.invoke(client);
      var restored = (List<?>) modelHistoryField.get(client);
      assertEquals(2, restored.size());
      assertTrue(restored.getFirst().toString().contains("原有问题"));
    }
  }

  @Test void missingArgumentsDoNotContactServer() throws Exception {
    assertEquals(2, runCaptured("http://127.0.0.1:1", "plan").exitCode());
    assertEquals(2, runCaptured("http://127.0.0.1:1", "invitations", "tester", "test-password", "accept").exitCode());
  }

  @Test void rejectedLoginHasAuthenticationExitCode() throws Exception {
    try(var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 401);
      assertEquals(3, runCaptured(server.base(), "votes", "tester", "test-password", "trip").exitCode());
    }
  }

  @Test void failedPlanningKeepsHttpExitCode() throws Exception {
    try(var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      server.empty("/api/login", 200);
      server.json("/api/agent/plan", 503, Map.of("error", "unavailable"));
      assertEquals(1, runCaptured(server.base(), "plan", "tester", "test-password", "杭州", "2").exitCode());
    }
  }

  @Test void invalidApiPathHasUsageExitCode() throws Exception {
    try(var server = new TestServer()) {
      server.json("/api/health", 200, Map.of("status", "UP", "application", "test"));
      assertEquals(2, runCaptured(server.base(), "api", "GET", "https://example.com/api/itineraries").exitCode());
    }
  }

  private static Result runCaptured(String base, String... args) throws Exception {
    var original = System.out;
    var output = new ByteArrayOutputStream();
    try (var print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(print);
      String[] commandArgs = new String[args.length + 1];
      commandArgs[0] = base;
      System.arraycopy(args, 0, commandArgs, 1, args.length);
      int exitCode = TravelClient.run(commandArgs);
      return new Result(exitCode, output.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
  }

  private record Result(int exitCode, String output) {}

  private interface Handler {
    void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private TestServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.start();
    }

    private String base() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void context(String path, Handler handler) {
      server.createContext(path, handler::handle);
    }

    private void json(String path, int status, Object body) throws IOException {
      context(path, exchange -> TestServer.respond(exchange, status, JSON.writeValueAsBytes(body)));
    }

    private void countJson(String path, int status, Object body, AtomicInteger counter) throws IOException {
      context(path, exchange -> {
        counter.incrementAndGet();
        TestServer.respond(exchange, status, JSON.writeValueAsBytes(body));
      });
    }

    private void empty(String path, int status) {
      context(path, exchange -> TestServer.respond(exchange, status, new byte[0]));
    }

    static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
        throws IOException {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length > 0) exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
