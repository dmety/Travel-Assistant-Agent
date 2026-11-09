package com.travelassistant.server.application.agent.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 读取运行态配置 {@code data/agent_providers.json}。文件在 /data/* 黑名单内，不入库；
 * 仓库只保留 {@code data/agent_providers.example.json} 脱敏种子。
 *
 * <p>环境变量覆盖按《Agent 运行时架构设计》「注册与发现」的最高优先级：
 * {@code TRAVEL_AGENT_PROVIDERS_ENABLED=false} 关闭外部配置，
 * {@code AGENT_PROVIDER_ENDPOINT_<ID>} 覆盖单个 endpoint 便于临时灰度。
 * 覆盖后的配置仍要过 G0，不能借环境变量绕开 M-08。
 */
@Component
public class AgentProviderConfigLoader {

  /** 一次加载结果。error 非空表示配置损坏，注册表保留上一版快照。 */
  public record Document(
      List<Map<String, Object>> providers,
      List<Map<String, Object>> routes,
      String error,
      Instant loadedAt,
      boolean present) {

    public static Document empty() {
      return new Document(List.of(), List.of(), null, Instant.now(), false);
    }

    public boolean broken() {
      return error != null;
    }
  }

  private final ObjectMapper json;
  private final Path configPath;
  private final boolean enabled;

  public AgentProviderConfigLoader(ObjectMapper json,
      @Value("${travel.agent-providers-file:${travel.data-directory}/agent_providers.json}") String file,
      @Value("${travel.agent-providers-enabled:true}") boolean enabled) {
    this.json = json;
    this.configPath = Path.of(file);
    this.enabled = enabled;
  }

  public Path configPath() {
    return configPath;
  }

  public Document load() {
    if (!enabled) {
      return Document.empty();
    }
    if (!Files.isRegularFile(configPath)) {
      return Document.empty();
    }
    try {
      JsonNode root = json.readTree(Files.readAllBytes(configPath));
      if (root == null || !root.isObject()) {
        return new Document(List.of(), List.of(), "配置根节点必须是对象", Instant.now(), true);
      }
      List<Map<String, Object>> providers = objectList(root, "providers");
      List<Map<String, Object>> routes = objectList(root, "routes");
      if (providers == null || routes == null) {
        return new Document(List.of(), List.of(), "providers 与 routes 必须是数组", Instant.now(), true);
      }
      applyEndpointOverrides(providers);
      return new Document(providers, routes, null, Instant.now(), true);
    } catch (IOException | IllegalArgumentException e) {
      return new Document(List.of(), List.of(), "配置无法解析：" + e.getMessage(), Instant.now(), true);
    }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> objectList(JsonNode root, String field) {
    JsonNode array = root.get(field);
    if (array == null || !array.isArray()) {
      return null;
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (JsonNode item : array) {
      if (!item.isObject()) {
        return null;
      }
      result.add(json.convertValue(item, LinkedHashMap.class));
    }
    return result;
  }

  private void applyEndpointOverrides(List<Map<String, Object>> providers) {
    for (Map<String, Object> provider : providers) {
      Object id = provider.get("id");
      if (!(id instanceof String s) || s.isEmpty()) {
        continue;
      }
      String key = "AGENT_PROVIDER_ENDPOINT_" + s.toUpperCase().replace('.', '_');
      String override = System.getenv(key);
      if (override != null && !override.isBlank()) {
        provider.put("endpoint", override);
      }
    }
  }
}
