package com.travelassistant.server.infrastructure.baidu;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 百度地图 Agent Plan 的语义地点检索客户端。 */
@Component
public class BaiduAgentPlanClient {

  private static final String PLACE_ENDPOINT = "https://api.map.baidu.com/agent_plan/v1/place";

  private final RestTemplate restTemplate;
  private final String authToken;

  @Autowired
  public BaiduAgentPlanClient(RestTemplateBuilder builder,
      @Value("${travel.baidu.agent-plan.auth-token:}") String authToken,
      @Value("${travel.baidu.agent-plan.timeout-ms:20000}") long timeoutMs) {
    long timeout = Math.max(timeoutMs, 1000);
    this.restTemplate = builder
        .connectTimeout(Duration.ofMillis(timeout))
        .readTimeout(Duration.ofMillis(timeout))
        .build();
    this.authToken = authToken == null ? "" : authToken.strip();
  }

  /** 供测试或调用方注入自定义 HTTP 客户端。 */
  public BaiduAgentPlanClient(RestTemplate restTemplate, String authToken) {
    this.restTemplate = restTemplate;
    this.authToken = authToken == null ? "" : authToken.strip();
  }

  public boolean configured() {
    return !authToken.isEmpty();
  }

  /**
   * 将完整用户需求交给 Agent Plan 语义检索。
   *
   * <p>Token 只通过 Authorization Header 传输，不进入 URL、日志或异常信息。
   */
  public String searchPlaces(String userRawRequest, String region) {
    if (!configured()) {
      throw new BaiduAgentPlanUnavailableException("未配置百度地图 Agent Plan Token");
    }
    if (userRawRequest == null || userRawRequest.isBlank()) {
      throw new BaiduAgentPlanUnavailableException("Agent Plan 地点检索需要完整的用户问题");
    }
    if (region == null || region.isBlank()) {
      throw new BaiduAgentPlanUnavailableException("Agent Plan 地点检索需要城市或区域");
    }

    var uri = UriComponentsBuilder.fromHttpUrl(PLACE_ENDPOINT)
        .queryParam("user_raw_request", userRawRequest)
        .queryParam("region", region.strip())
        .queryParam("sort", "relevance")
        .build().encode().toUri();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(authToken);
    headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));

    try {
      ResponseEntity<String> response = restTemplate.exchange(
          uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
      String body = response.getBody();
      if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
        throw new BaiduAgentPlanUnavailableException("百度地图 Agent Plan 返回空结果");
      }
      return body.strip();
    } catch (BaiduAgentPlanUnavailableException e) {
      throw e;
    } catch (RestClientException e) {
      // Do not propagate HTTP diagnostics: they can include the full user query or upstream body.
      throw new BaiduAgentPlanUnavailableException("百度地图 Agent Plan 请求失败，请检查 Token、额度或网络");
    }
  }
}
