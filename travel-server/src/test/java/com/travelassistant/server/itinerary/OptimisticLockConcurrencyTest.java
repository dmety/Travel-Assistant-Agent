package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 乐观锁并发回归测试。
 *
 * <p>为什么单独一个类：MockMvc 是单线程模拟，无法复现真实的并发竞态，
 * 必须起真实容器（随机端口）并发打 HTTP。
 *
 * <p>缺陷 D-6（已修复）：原实现中「读版本 → 校验 → 写回」不是原子操作，版本检查发生在加锁之前，
 * 导致多个同版本请求同时通过校验，后写覆盖前写（12 路并发全部 200，11 条静默丢失）。
 * 现由 {@code ItineraryService} 的行程维度写锁保证原子性，本用例为回归门禁。
 *
 * <p>两个切面各一条：**同一板块** 12 路并发（更新丢失），**不同板块** 2 路并发
 * （版本号必须全局单调，不能因为改的是不同字段就各自成功）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("D-6 乐观锁并发安全")
class OptimisticLockConcurrencyTest {

  private static final Path DATA_DIR = com.travelassistant.server.TestDataDirs.sub("concurrency");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int CONCURRENCY = 12;
  private static final String PASSWORD = "Passw0rd!2026";

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void dataDirectory(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
  }

  /** 一次竞态所需的上下文：已登录的客户端、服务地址、刚建好的行程。 */
  private record Race(HttpClient client, String base, String id) {
  }

  @Test
  @DisplayName("同一版本被并发提交时，只应有一个请求成功，其余全部 409")
  void onlyOneConcurrentUpdateSucceeds() throws Exception {
    Race race = race("并发测试");

    List<Future<Integer>> futures = runConcurrently(CONCURRENCY, index -> put(race,
        "/plan",
        JSON.writeValueAsString(Map.of(
            "destination", "杭州", "summary", "并发写入 " + index, "notes", "")),
        1));

    long success = 0;
    for (Future<Integer> future : futures) {
      if (future.get(20, TimeUnit.SECONDS) == 200) {
        success++;
      }
    }

    assertThat(success)
        .as("并发提交同一版本时，成功数必须恰好为 1，否则说明存在丢失更新")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("不同板块、同一版本并发：只一个成功，另一个 409，版本只前进一步")
  void crossSectionWritesWithSameVersionOnlyOneWins() throws Exception {
    Race race = race("跨板块并发");

    // 两条请求改的是不同字段，但版本号是整份行程共享的：不能因为「改的不是同一块」
    // 就各自成功，否则后写的一方会用自己的副本覆盖掉对方刚写进去的内容。
    List<Callable<Integer>> writers = List.of(
        () -> put(race, "/daily", "[{\"date\":\"2026-11-21\",\"activities\":[]}]", 1),
        () -> put(race, "/expenses",
            "[{\"category\":\"交通\",\"amount\":100,\"currency\":\"CNY\"}]", 1));

    List<Integer> statuses = collect(runConcurrently(writers));

    assertThat(statuses).as("两个请求都不该 500").doesNotContain(500);
    assertThat(statuses.stream().filter(code -> code == 200).count())
        .as("跨板块并发也必须有且只有一个成功")
        .isEqualTo(1);
    assertThat(statuses.stream().filter(code -> code == 409).count())
        .as("失败的一方是版本冲突，不是无权限或参数错误")
        .isEqualTo(1);

    JsonNode detail = getJson(race, "/api/itineraries/" + race.id());
    assertThat(detail.get("version").asLong())
        .as("版本只应前进一次；前进两次说明两个请求都写进去了")
        .isEqualTo(2);
  }

  // ---- 竞态脚手架 ----

  private Race race(String title) throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    String base = "http://127.0.0.1:" + port;
    String username = "race_" + System.nanoTime();

    postJson(client, base + "/api/account/register",
        JSON.writeValueAsString(Map.of("username", username, "password", PASSWORD)));
    client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> created = postJson(client, base + "/api/itineraries",
        JSON.writeValueAsString(Map.of("title", title, "plan", Map.of("destination", "杭州"))));
    String id = JSON.readTree(created.body()).get("id").asText();
    return new Race(client, base, id);
  }

  private int put(Race race, String section, String body, long version) throws Exception {
    HttpResponse<String> response = race.client().send(
        HttpRequest.newBuilder(
                URI.create(race.base() + "/api/itineraries/" + race.id() + section
                    + "?version=" + version))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }

  private JsonNode getJson(Race race, String path) throws Exception {
    HttpResponse<String> response = race.client().send(
        HttpRequest.newBuilder(URI.create(race.base() + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(response.body());
  }

  private interface IndexedCall {
    int run(int index) throws Exception;
  }

  /** 统一在门闩放行后并发发起，保证请求重叠而不是排队。 */
  private List<Future<Integer>> runConcurrently(int count, IndexedCall call) {
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int index = i;
      tasks.add(() -> call.run(index));
    }
    return runConcurrently(tasks);
  }

  private List<Future<Integer>> runConcurrently(List<Callable<Integer>> tasks) {
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (Callable<Integer> task : tasks) {
      futures.add(pool.submit(() -> {
        startGate.await(5, TimeUnit.SECONDS);
        return task.call();
      }));
    }
    startGate.countDown();
    pool.shutdown();
    return futures;
  }

  private List<Integer> collect(List<Future<Integer>> futures) throws Exception {
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get(20, TimeUnit.SECONDS));
    }
    return statuses;
  }

  private HttpResponse<String> postJson(HttpClient client, String url, String body)
      throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
