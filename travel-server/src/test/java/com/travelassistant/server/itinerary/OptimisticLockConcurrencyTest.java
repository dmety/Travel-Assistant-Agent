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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("D-6 乐观锁并发安全")
class OptimisticLockConcurrencyTest {

  private static final Path DATA_DIR = createTempDir();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int CONCURRENCY = 12;

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void dataDirectory(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("travel-concurrency-test-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("同一版本被并发提交时，只应有一个请求成功，其余全部 409")
  void onlyOneConcurrentUpdateSucceeds() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    String base = "http://127.0.0.1:" + port;
    String username = "race_" + System.nanoTime();

    postJson(client, base + "/api/account/register",
        JSON.writeValueAsString(Map.of("username", username, "password", "Passw0rd!2026")));
    client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode("Passw0rd!2026", StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    HttpResponse<String> created = postJson(client, base + "/api/itineraries",
        JSON.writeValueAsString(Map.of("title", "并发测试", "plan", Map.of("destination", "杭州"))));
    String id = JSON.readTree(created.body()).get("id").asText();

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      int index = i;
      futures.add(pool.submit(() -> {
        startGate.await(5, TimeUnit.SECONDS);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/itineraries/" + id + "/plan?version=1"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                    "destination", "杭州", "summary", "并发写入 " + index, "notes", ""))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
      }));
    }

    startGate.countDown();

    long success = 0;
    for (Future<Integer> future : futures) {
      if (future.get(20, TimeUnit.SECONDS) == 200) {
        success++;
      }
    }
    pool.shutdownNow();

    assertThat(success)
        .as("并发提交同一版本时，成功数必须恰好为 1，否则说明存在丢失更新")
        .isEqualTo(1);
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
