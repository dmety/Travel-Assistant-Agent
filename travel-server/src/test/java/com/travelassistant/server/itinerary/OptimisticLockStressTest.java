package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 乐观锁并发<b>压力观察</b>用例（缺陷 D-6）。
 *
 * <p><b>本类不作为修复判定依据。</b>判定请看
 * {@link OptimisticLockAtomicityTest}：那个用栅栏把竞态变成必然，结果与线程调度无关。
 *
 * <p>为什么这条不能当判定：它靠真实 HTTP 并发去撞那个竞态窗口，而窗口很窄。
 * 实测数据（2026-11-05，12 路并发）：
 *
 * <table border="1">
 *   <caption>复现率</caption>
 *   <tr><th>场景</th><th>每轮 200 数</th><th>异常率</th></tr>
 *   <tr><td>冷 JVM 首轮</td><td>12 / 12</td><td>首轮内必然暴露</td></tr>
 *   <tr><td>预热后 12 并发 × 10 轮</td><td>1,1,1,1,2,1,1,1,1,1</td><td>10%</td></tr>
 *   <tr><td>预热后 30 并发 × 5 轮</td><td>1,2,1,1,1</td><td>20%</td></tr>
 * </table>
 *
 * <p>也就是说：缺陷还在时，单轮约 80%~90% 概率「看起来正常」。
 * 启用本类后即使变绿也<b>不能</b>说明已修复——这正是当初保留它当参考、
 * 另写确定性用例的原因。
 *
 * <p>注意：{@code ItineraryService} 的「读版本 → 校验 → 写回」不是原子操作，
 * 版本检查发生在加锁之前，多个同版本请求可同时通过校验，后写覆盖前写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Disabled("D-6 未修复。本类为概率复现的压力观察，不作为判定依据；判定见 OptimisticLockAtomicityTest")
@DisplayName("D-6 乐观锁并发压力观察（参考用例，不参与判定）")
class OptimisticLockStressTest {

  private static final Path DATA_DIR = com.travelassistant.server.TestDataDirs.sub("stress");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int CONCURRENCY = 12;
  private static final int ROUNDS = 10;

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void dataDirectory(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
  }

  @Test
  @DisplayName("连续 10 轮并发：每轮都只应有一个请求成功")
  void everyRoundHasExactlyOneWinner() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    String base = "http://127.0.0.1:" + port;
    String username = "stress_" + System.nanoTime();

    postJson(client, base + "/api/account/register",
        JSON.writeValueAsString(Map.of("username", username, "password", "Passw0rd!2026")));
    client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode("Passw0rd!2026", StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    List<Integer> winnersPerRound = new ArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      HttpResponse<String> created = postJson(client, base + "/api/itineraries",
          JSON.writeValueAsString(Map.of("title", "压力轮次 " + round,
              "plan", Map.of("destination", "杭州"))));
      String id = JSON.readTree(created.body()).get("id").asText();
      winnersPerRound.add(race(client, base, id));
    }

    assertThat(winnersPerRound)
        .as("每轮只允许一个请求成功；出现 >1 即为丢失更新（详见 OptimisticLockAtomicityTest）")
        .allMatch(count -> count == 1);
  }

  /** 发起 CONCURRENCY 个携带同一版本号的并发更新，返回其中返回 200 的个数。 */
  private int race(HttpClient client, String base, String id) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      int index = i;
      futures.add(pool.submit(() -> {
        startGate.await(5, TimeUnit.SECONDS);
        return client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/itineraries/" + id + "/plan?version=1"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                    "destination", "杭州", "summary", "并发写入 " + index, "notes", ""))))
                .build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
      }));
    }
    startGate.countDown();

    int winners = 0;
    for (Future<Integer> future : futures) {
      if (future.get(20, TimeUnit.SECONDS) == 200) {
        winners++;
      }
    }
    pool.shutdownNow();
    return winners;
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
