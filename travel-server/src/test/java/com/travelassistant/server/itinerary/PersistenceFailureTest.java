package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.TestDataDirs;
import java.io.IOException;
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
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 磁盘写失败时的行为（T-09）。
 *
 * <p>文件持久化没有事务：写失败时异常会抛给调用方，但**内存副本是否已经改变**是另一件事。
 * 原实现先改内存再落盘，于是写失败时用户拿到 500，行程却出现在列表里——刷新还在，重启就没了。
 * 这种「失败但看起来成功」比直接失败更难解释，所以这里把两件事一起钉住：
 * 用户看到 5xx，列表里也不留幽灵记录。
 *
 * <p>用真实容器而不是 MockMvc：未处理异常在 MockMvc 里会被直接 rethrow，
 * 看不到用户实际收到的状态码，那正是这条用例要验证的东西。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("持久化失败：写不进去时不能假装写成功")
class PersistenceFailureTest {

  private static final Path DATA_DIR = TestDataDirs.sub("persistence-failure");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Passw0rd!2026";

  @LocalServerPort
  private int port;

  @DynamicPropertySource
  static void dataDirectory(DynamicPropertyRegistry registry) {
    registry.add("travel.data-directory", () -> DATA_DIR.toString());
  }

  @AfterEach
  void restore() throws IOException {
    restoreDataDirectory();
  }

  @Test
  @DisplayName("数据目录写不进去时返回 5xx，列表里也不留下这条没存下来的行程")
  void writeFailureIsVisibleAndLeavesNoGhost() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    String base = "http://127.0.0.1:" + port;
    String username = "persist_" + System.nanoTime();
    String title = "写不进去的行程";

    post(client, base + "/api/account/register",
        JSON.writeValueAsString(Map.of("username", username, "password", PASSWORD)));
    client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    // 把数据目录换成一个同名普通文件：之后任何一次落盘都会失败
    blockDataDirectory();

    HttpResponse<String> created = post(client, base + "/api/itineraries",
        JSON.writeValueAsString(Map.of("title", title)));
    assertThat(created.statusCode())
        .as("落盘失败必须让调用方看见，而不是返回 201")
        .isBetween(500, 599);

    HttpResponse<String> list = client.send(
        HttpRequest.newBuilder(URI.create(base + "/api/itineraries")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body())
        .as("写失败的行程不能留在内存副本里——否则刷新还在、重启就没")
        .doesNotContain(title);

    // 目录恢复后必须重新可写：失败不应把 store 卡在坏状态
    restoreDataDirectory();
    HttpResponse<String> retry = post(client, base + "/api/itineraries",
        JSON.writeValueAsString(Map.of("title", title)));
    assertThat(retry.statusCode()).isEqualTo(201);
  }

  private static void blockDataDirectory() throws IOException {
    if (Files.exists(DATA_DIR)) {
      try (Stream<Path> paths = Files.walk(DATA_DIR)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
    Files.createFile(DATA_DIR);
  }

  private static void restoreDataDirectory() throws IOException {
    if (Files.isRegularFile(DATA_DIR)) {
      Files.delete(DATA_DIR);
    }
    Files.createDirectories(DATA_DIR);
  }

  private HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
