package com.travelassistant.server.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * DeepSeek 对话补全客户端（OpenAI 兼容协议）。
 *
 * <p>为什么手写 HTTP 而不用 Spring AI 的 {@code ChatClient}：本项目的模型调用必须能在
 * **没有密钥时正常启动、正常降级**，而 Spring AI 的自动配置在缺少密钥时会导致上下文启动失败。
 * 这里改为显式 HTTP 调用，密钥为空就整体停用，行程、景点、预算、协作都不受影响。
 * 依赖仍然保留 {@code spring-ai-deepseek}，后续要换回 ChatClient 只需替换本类。
 *
 * <p>模型只负责措辞，不负责事实：调用方传给它的全部是已经查到的数据，
 * 返回的文本也不会被解析成结构化指令。
 */
@Component
public class DeepSeekClient {
  private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

  private final RestTemplate restTemplate;
  private final String apiKey;
  private final String baseUrl;
  private final String model;

  @org.springframework.beans.factory.annotation.Autowired
  public DeepSeekClient(RestTemplateBuilder builder,
      @Value("${travel.ai.api-key:}") String apiKey,
      @Value("${travel.ai.base-url:https://api.deepseek.com}") String baseUrl,
      @Value("${travel.ai.model:deepseek-chat}") String model,
      @Value("${travel.ai.timeout-ms:20000}") long timeoutMs) {
    this(builder
        .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
        .readTimeout(Duration.ofMillis(timeoutMs))
        .build(), apiKey, baseUrl, model);
  }

  /** 供测试注入自定义 RestTemplate（以便挂 MockRestServiceServer）。 */
  public DeepSeekClient(RestTemplate restTemplate, String apiKey, String baseUrl, String model) {
    this.restTemplate = restTemplate;
    this.apiKey = apiKey == null ? "" : apiKey.strip();
    this.baseUrl = baseUrl == null || baseUrl.isBlank()
        ? "https://api.deepseek.com" : baseUrl.strip();
    this.model = model == null || model.isBlank() ? "deepseek-chat" : model.strip();
  }

  /** 是否已配置密钥。 */
  public boolean configured() {
    return !apiKey.isEmpty();
  }

  public String model() {
    return model;
  }

  /**
   * 发起一次对话补全，返回助手文本。
   *
   * @throws AgentUnavailableException 未配置密钥、调用失败或响应无法解析
   */
  public String complete(String systemPrompt, String userPrompt) {
    if (!configured()) {
      throw new AgentUnavailableException(
          "未配置模型密钥（环境变量 DEEPSEEK_API_KEY），本次结果不含模型建议");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", userPrompt)));
    body.put("temperature", 0.3);
    body.put("max_tokens", 800);
    body.put("stream", false);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(apiKey);

    try {
      ResponseEntity<JsonNode> response = restTemplate.postForEntity(
          baseUrl + "/chat/completions", new HttpEntity<>(body, headers), JsonNode.class);
      JsonNode root = response.getBody();
      if (root == null) {
        throw new AgentUnavailableException("模型返回空响应");
      }
      JsonNode content = root.path("choices").path(0).path("message").path("content");
      if (content.isMissingNode() || content.asText("").isBlank()) {
        throw new AgentUnavailableException("模型返回内容为空");
      }
      return content.asText().strip();
    } catch (HttpStatusCodeException e) {
      throw new AgentUnavailableException(explain(e), e);
    } catch (RestClientException e) {
      throw new AgentUnavailableException("模型服务请求失败：" + brief(e.getMessage()), e);
    }
  }

  /** 把常见 HTTP 状态翻成可操作的中文，密钥原文不进日志和异常。 */
  private String explain(HttpStatusCodeException e) {
    int status = e.getStatusCode().value();
    log.warn("DeepSeek 调用失败，HTTP {}", status);
    return switch (status) {
      case 401 -> "模型密钥无效或已失效，请检查 DEEPSEEK_API_KEY";
      case 402 -> "模型账户余额不足";
      case 429 -> "模型调用频率超限，请稍后重试";
      default -> {
        if (status >= 500) {
          yield "模型服务暂时不可用（HTTP " + status + "）";
        }
        yield "模型调用被拒绝（HTTP " + status + "）";
      }
    };
  }

  private String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 160 ? message.substring(0, 160) + "…" : message;
  }
}
