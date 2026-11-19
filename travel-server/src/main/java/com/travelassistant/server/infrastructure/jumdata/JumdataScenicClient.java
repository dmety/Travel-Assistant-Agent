package com.travelassistant.server.infrastructure.jumdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Server-side client for the Jumdata scenic-spot API sold through Alibaba Cloud Marketplace. */
@Component
public class JumdataScenicClient {

  private static final String DEFAULT_BASE_URL = "https://jmqgjdcx.market.alicloudapi.com";
  private static final String SCENIC_SPOTS_PATH = "/area/scenic-spots";

  private final RestTemplate restTemplate;
  private final ObjectMapper mapper;
  private final String appCode;
  private final String baseUrl;

  @Autowired
  public JumdataScenicClient(RestTemplateBuilder builder, ObjectMapper mapper,
      @Value("${travel.jumdata.scenic.app-code:}") String appCode,
      @Value("${travel.jumdata.scenic.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
      @Value("${travel.jumdata.scenic.timeout-ms:8000}") long timeoutMs) {
    long timeout = Math.max(timeoutMs, 1000);
    this.restTemplate = builder.connectTimeout(Duration.ofMillis(timeout))
        .readTimeout(Duration.ofMillis(timeout)).build();
    this.mapper = mapper;
    this.appCode = appCode == null ? "" : appCode.strip();
    this.baseUrl = trimTrailingSlash(baseUrl == null || baseUrl.isBlank()
        ? DEFAULT_BASE_URL : baseUrl.strip());
  }

  /** Constructor for callers that provide a configured HTTP client directly. */
  public JumdataScenicClient(RestTemplate restTemplate, ObjectMapper mapper,
      String appCode, String baseUrl) {
    this.restTemplate = restTemplate;
    this.mapper = mapper;
    this.appCode = appCode == null ? "" : appCode.strip();
    this.baseUrl = trimTrailingSlash(baseUrl == null || baseUrl.isBlank()
        ? DEFAULT_BASE_URL : baseUrl.strip());
  }

  public boolean configured() {
    return !appCode.isEmpty();
  }

  /**
   * Search one page by keyword. When a city is supplied, records without a matching city are
   * discarded so an identically named scenic spot elsewhere cannot silently replace it.
   */
  public List<JumdataScenicSpot> search(String keyword, String city, int limit) {
    return search(keyword, city, null, limit);
  }

  /** Query a province by keyword and enforce the province field returned by the API. */
  public List<JumdataScenicSpot> searchInProvince(String keyword, String province, int limit) {
    return search(keyword, null, province, limit);
  }

  private List<JumdataScenicSpot> search(String keyword, String city, String province, int limit) {
    if (!configured()) {
      throw new JumdataScenicUnavailableException("未配置聚美智数景点 API AppCode");
    }
    if (keyword == null || keyword.isBlank()) {
      throw new JumdataScenicUnavailableException("景点查询需要 keyword 参数");
    }

    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("keyword", keyword.strip());
    body.add("page", "1");
    JsonNode response = post(SCENIC_SPOTS_PATH, body);
    int code = response.path("code").asInt(-1);
    if (code != 200) {
      // Keep upstream diagnostics out of user-visible errors and logs. Providers sometimes
      // echo request details, and the AppCode must never be propagated beyond this client.
      throw new JumdataScenicUnavailableException("聚美智数景点接口返回 code=" + code);
    }

    JsonNode rows = response.path("data").path("list");
    if (!rows.isArray()) {
      return List.of();
    }
    int safeLimit = Math.max(1, Math.min(limit, 20));
    Map<String, JumdataScenicSpot> unique = new LinkedHashMap<>();
    Instant retrievedAt = Instant.now();
    for (JsonNode row : rows) {
      JumdataScenicSpot spot = parseSpot(row, retrievedAt);
      if (spot == null || spot.name() == null || spot.name().isBlank()) {
        continue;
      }
      if (city != null && !city.isBlank() && !sameCity(city, spot.city())) {
        continue;
      }
      if (province != null && !province.isBlank() && !sameCity(province, spot.province())) {
        continue;
      }
      String key = normalized(cityOrEmpty(spot.city()) + "|" + spot.name());
      unique.putIfAbsent(key, spot);
      if (unique.size() >= safeLimit) {
        break;
      }
    }
    return List.copyOf(unique.values());
  }

  private JsonNode post(String path, MultiValueMap<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "APPCODE " + appCode);
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    try {
      URI uri = URI.create(baseUrl + path);
      ResponseEntity<String> response = restTemplate.postForEntity(
          uri, new HttpEntity<>(body, headers), String.class);
      String text = response.getBody();
      if (!response.getStatusCode().is2xxSuccessful() || text == null || text.isBlank()) {
        throw new JumdataScenicUnavailableException("聚美智数景点接口返回空响应");
      }
      return mapper.readTree(text);
    } catch (JumdataScenicUnavailableException e) {
      throw e;
    } catch (RestClientException | IOException | IllegalArgumentException e) {
      // Never propagate request headers or upstream diagnostics that could expose credentials.
      throw new JumdataScenicUnavailableException("聚美智数景点接口请求失败，请检查 AppCode、额度或网络");
    }
  }

  private JumdataScenicSpot parseSpot(JsonNode row, Instant retrievedAt) {
    if (row == null || !row.isObject()) {
      return null;
    }
    JsonNode location = property(row, "location", "coordinate", "coordinates");
    List<JumdataScenicSpot.TicketPrice> tickets = parseTickets(row);
    return new JumdataScenicSpot(
        text(row, "id", "scenicId", "spotId"),
        text(row, "name", "scenicName", "spotName"),
        text(row, "proName", "provinceName", "province"),
        text(row, "cityName", "city"),
        text(row, "areaName", "district", "countyName"),
        text(row, "address", "detailAddress"),
        text(row, "star", "level", "grade", "qualityLevel"),
        text(row, "opentime", "openTime", "open_time"),
        text(row, "summary", "content", "description"),
        decimal(location, "lat", "latitude"),
        decimal(location, "lon", "lng", "longitude"),
        tickets, retrievedAt,
        text(row, "informationUpdatedAt", "updatedAt", "updateTime", "dataTime"));
  }

  /** The published sample uses both priceList and picList for nested data; identify by shape. */
  private List<JumdataScenicSpot.TicketPrice> parseTickets(JsonNode row) {
    Map<String, JumdataScenicSpot.TicketPrice> unique = new LinkedHashMap<>();
    for (String listName : List.of("priceList", "picList", "ticketList", "tickets")) {
      JsonNode groups = property(row, listName);
      if (!(groups instanceof ArrayNode array)) {
        continue;
      }
      for (JsonNode group : array) {
        String category = text(group, "type", "category", "ticketType", "TicketType");
        JsonNode entities = property(group, "entityList", "tickets", "items");
        if (entities != null && entities.isArray()) {
          for (JsonNode entity : entities) {
            addTicket(unique, category, entity);
          }
        } else {
          addTicket(unique, category, group);
        }
      }
    }
    return List.copyOf(unique.values());
  }

  private void addTicket(Map<String, JumdataScenicSpot.TicketPrice> unique,
      String category, JsonNode node) {
    String name = text(node, "TicketName", "ticketName", "name", "PriceName");
    String face = text(node, "Amount", "amount", "facePrice", "price");
    String channel = text(node, "AmountAdvice", "amountAdvice", "channelPrice", "salePrice");
    String begin = text(node, "BeginDate", "beginDate", "startDate");
    String end = text(node, "EndDate", "endDate", "expireDate");
    if (name == null && face == null && channel == null) {
      return;
    }
    JumdataScenicSpot.TicketPrice ticket = new JumdataScenicSpot.TicketPrice(
        category, name, face, channel, begin, end);
    String key = normalized(String.join("|", nullToEmpty(category), nullToEmpty(name),
        nullToEmpty(face), nullToEmpty(channel), nullToEmpty(begin), nullToEmpty(end)));
    unique.putIfAbsent(key, ticket);
  }

  private static JsonNode property(JsonNode node, String... names) {
    if (node == null || !node.isObject()) {
      return null;
    }
    for (String name : names) {
      JsonNode direct = node.get(name);
      if (direct != null) {
        return direct;
      }
    }
    for (var field = node.fields(); field.hasNext();) {
      Map.Entry<String, JsonNode> entry = field.next();
      String candidate = compact(entry.getKey());
      for (String name : names) {
        if (candidate.equals(compact(name))) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private static String text(JsonNode node, String... names) {
    JsonNode value = property(node, names);
    if (value == null || value.isNull() || value.isContainerNode()) {
      return null;
    }
    String text = value.asText("").strip();
    return text.isEmpty() ? null : text;
  }

  private static Double decimal(JsonNode node, String... names) {
    String raw = text(node, names);
    if (raw == null) {
      return null;
    }
    try {
      return Double.parseDouble(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean sameCity(String expected, String actual) {
    return actual != null && normalized(expected).equals(normalized(actual));
  }

  private static String normalized(String value) {
    if (value == null) {
      return "";
    }
    String text = value.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s　]+", "");
    for (String suffix : List.of("特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区",
        "自治区", "自治州", "地区", "市", "县")) {
      if (text.endsWith(suffix)) {
        text = text.substring(0, text.length() - suffix.length());
        break;
      }
    }
    return text;
  }

  private static String compact(String value) {
    return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  private static String cityOrEmpty(String city) {
    return city == null ? "" : city;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String trimTrailingSlash(String url) {
    String result = url == null ? "" : url.strip();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
