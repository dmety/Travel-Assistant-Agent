package com.travelassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 景点查询控制台。
 *
 * <p>交互流程：输入城市名（如"杭州"）或景点名（如"西湖"）→ 列出命中景点
 * （每条带序号、ID 与来源）→ 输入序号查看详情。
 *
 * <p>规则：
 * <ul>
 *   <li>先按城市/省份查询，无结果再退化为按名称查询；</li>
 *   <li>无结果时给出明确提示，不静默返回空；</li>
 *   <li>票价、开放时间等字段缺失时显示"未知"，不臆测为免费或全天开放；</li>
 *   <li>知识库不可用时显示服务端给出的原因。</li>
 * </ul>
 */
public class AttractionConsole {

  /** 单次列出的条数上限。 */
  private static final int PAGE_LIMIT = 20;
  private static final String UNKNOWN = "未知";

  private final String base;
  private final HttpClient client;
  private final ObjectMapper json = new ObjectMapper();

  /** 当前列表中的 ID，序号从 1 开始。 */
  private final List<String> currentIds = new ArrayList<>();

  public AttractionConsole(String base) {
    this.base = base.replaceAll("/+$", "");
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** 交互模式：循环读取关键词或序号。 */
  public void runInteractive() throws Exception {
    Charset consoleCharset = consoleCharset();
    System.out.println("=== 景点查询 ===");
    System.out.println("输入城市名（如 杭州）或景点名（如 西湖）查询；输入序号查看详情；q 退出。");
    try (Scanner scanner = new Scanner(System.in, consoleCharset)) {
      while (true) {
        System.out.print("\n> ");
        System.out.flush();
        if (!scanner.hasNextLine()) {
          break;
        }
        String input = scanner.nextLine().strip();
        if (input.isEmpty()) {
          continue;
        }
        if ("q".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)
            || "exit".equalsIgnoreCase(input)) {
          System.out.println("已退出景点查询。");
          return;
        }
        if (isIndex(input)) {
          showDetailByIndex(Integer.parseInt(input));
        } else {
          search(input);
        }
      }
    }
  }

  /** 一次性查询（非交互，便于脚本与测试）：打印列表后返回。 */
  public void searchAndPrint(String keyword) throws Exception {
    search(keyword);
  }

  /** 直接按 ID 打印详情（非交互）。 */
  public void detailByIdAndPrint(String id) throws Exception {
    JsonNode detail = fetchDetail(id);
    if (detail == null) {
      return;
    }
    printDetail(detail);
  }

  // ---- 查询 ----

  private void search(String keyword) throws Exception {
    currentIds.clear();

    JsonNode byCity = fetchQuery("city", keyword);
    if (byCity == null) {
      return;
    }
    String scope = "城市";
    JsonNode result = byCity;
    if (!isAvailable(byCity)) {
      printUnavailable(byCity);
      return;
    }
    if (byCity.path("matched").asInt(0) == 0) {
      JsonNode byName = fetchQuery("name", keyword);
      if (byName == null) {
        return;
      }
      if (!isAvailable(byName)) {
        printUnavailable(byName);
        return;
      }
      result = byName;
      scope = "名称";
    }

    int matched = result.path("matched").asInt(0);
    if (matched == 0) {
      String message = result.path("message").asText("");
      System.out.println(message.isEmpty()
          ? "未找到与「" + keyword + "」相关的景点。"
          : message);
      return;
    }

    JsonNode items = result.path("items");
    int returned = result.path("returned").asInt(items.size());
    System.out.printf("%n[按%s] 「%s」命中 %d 条，显示 %d 条：%n", scope, keyword, matched, returned);

    int index = 1;
    for (JsonNode item : items) {
      String id = item.path("id").asText("");
      currentIds.add(id);
      System.out.printf("  %2d. %s%n", index++, text(item, "name", UNKNOWN));
      System.out.printf("      ID: %s | 来源: %s | 城市: %s%n",
          blankTo(id, UNKNOWN),
          text(item, "source", UNKNOWN),
          text(item, "city", UNKNOWN));
    }

    if (matched > returned) {
      System.out.printf("  还有 %d 条未显示（单次最多 %d 条）。可输入更精确的名称继续查。%n",
          matched - returned, returned);
    }
    System.out.println("  输入序号查看详情，或输入新的城市/景点名继续查询。");
  }

  private void showDetailByIndex(int index) throws Exception {
    if (currentIds.isEmpty()) {
      System.out.println("当前没有可选的列表，请先输入城市名或景点名查询。");
      return;
    }
    if (index < 1 || index > currentIds.size()) {
      System.out.printf("序号超出范围：请输入 1 - %d 之间的数字。%n", currentIds.size());
      return;
    }
    String id = currentIds.get(index - 1);
    JsonNode detail = fetchDetail(id);
    if (detail != null) {
      printDetail(detail);
    }
  }

  private void printDetail(JsonNode d) {
    System.out.println("\n=== 景点详情 ===");
    line("名称", text(d, "name", UNKNOWN));
    line("ID", text(d, "id", UNKNOWN));
    line("城市", text(d, "city", UNKNOWN) + formatProvince(d));
    line("类型", text(d, "type", UNKNOWN));
    line("等级", text(d, "level", UNKNOWN));
    line("评分", text(d, "rating", UNKNOWN));
    // 票价与开放时间：缺失时明确显示"未知"，不做任何推断
    line("开放时间", text(d, "openTimeText", UNKNOWN));
    line("票价", text(d, "ticketText", UNKNOWN));
    line("地址", text(d, "address", UNKNOWN));
    line("电话", text(d, "tel", UNKNOWN));
    line("官网", text(d, "website", UNKNOWN));
    line("来源", text(d, "source", UNKNOWN));
    line("坐标", formatCoordinate(d));
    String description = text(d, "description", UNKNOWN);
    line("简介", description);
    System.out.println("（提示：票价与开放时间为离线快照，缺失即显示未知，请以景区公告为准。）");
  }

  // ---- HTTP ----

  private JsonNode fetchQuery(String param, String keyword) throws Exception {
    String url = base + "/api/attractions?" + param + "=" + encode(keyword)
        + "&limit=" + PAGE_LIMIT;
    HttpResponse<String> response = send(url);
    if (response.statusCode() == 503) {
      printUnavailable(json.readTree(response.body()));
      return null;
    }
    if (response.statusCode() != 200) {
      System.out.println("查询失败：HTTP " + response.statusCode() + " " + response.body());
      return null;
    }
    return json.readTree(response.body());
  }

  private JsonNode fetchDetail(String id) throws Exception {
    HttpResponse<String> response = send(base + "/api/attractions/" + encodePath(id));
    if (response.statusCode() == 503) {
      printUnavailable(json.readTree(response.body()));
      return null;
    }
    if (response.statusCode() == 404) {
      System.out.println("景点不存在：ID " + id);
      return null;
    }
    if (response.statusCode() != 200) {
      System.out.println("查询详情失败：HTTP " + response.statusCode() + " " + response.body());
      return null;
    }
    return json.readTree(response.body());
  }

  private HttpResponse<String> send(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  // ---- 输出辅助 ----

  private void printUnavailable(JsonNode body) {
    String reason = body == null ? "" : body.path("reason").asText("");
    System.out.println("景点查询当前不可用。");
    if (!reason.isEmpty()) {
      System.out.println("原因：" + reason);
    }
    System.out.println("（知识库缺失或损坏时查询会被停用，且不会覆盖原库；请先运行离线构建。）");
  }

  private static boolean isAvailable(JsonNode body) {
    return body != null && !body.has("reason");
  }

  /** 按显示宽度对齐标签（中文占 2 格），避免 %-8s 把中文标签算窄导致错位。 */
  private static void line(String label, String value) {
    int labelWidth = displayWidth(label);
    int padding = Math.max(0, 9 - labelWidth);
    System.out.println("  " + label + " ".repeat(padding) + ": " + value);
  }

  private static int displayWidth(String text) {
    int width = 0;
    for (int i = 0; i < text.length(); i++) {
      width += text.charAt(i) > 127 ? 2 : 1;
    }
    return width;
  }

  private static String formatProvince(JsonNode d) {
    String province = text(d, "province", "");
    return province.isEmpty() ? "" : "（" + province + "）";
  }

  private static String formatCoordinate(JsonNode d) {
    JsonNode lat = d.get("lat");
    JsonNode lon = d.get("lon");
    if (lat == null || lat.isNull() || lon == null || lon.isNull()) {
      return UNKNOWN;
    }
    return String.format("%.5f, %.5f", lat.asDouble(), lon.asDouble());
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    String s = value.asText("").strip();
    return s.isEmpty() ? fallback : s;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static boolean isIndex(String input) {
    if (input.isEmpty() || input.length() > 3) {
      return false;
    }
    for (int i = 0; i < input.length(); i++) {
      if (!Character.isDigit(input.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** 路径段编码：空格用 %20 而非 +。 */
  private static String encodePath(String value) {
    return encode(value).replace("+", "%20");
  }

  /** 控制台输入编码：Windows 下为 GBK，避免中文输入乱码。 */
  private static Charset consoleCharset() {
    String nativeEncoding = System.getProperty("native.encoding");
    try {
      return nativeEncoding == null ? StandardCharsets.UTF_8 : Charset.forName(nativeEncoding);
    } catch (Exception e) {
      return StandardCharsets.UTF_8;
    }
  }
}
