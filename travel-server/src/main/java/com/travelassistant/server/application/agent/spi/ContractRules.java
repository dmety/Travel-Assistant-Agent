package com.travelassistant.server.application.agent.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 规范单一源的 Java 视图。仓库里只有 {@code tools/agent/provider-contract.json} 一份，
 * 由 Maven 在构建时复制进 classpath 的 {@code agent/} 目录；Java 不硬编码任何枚举或上限，
 * 避免文档、Python 闸门与运行时判定出现三套口径。
 *
 * <p>资源缺失时直接抛异常让启动失败：规范读不到还继续放行 Provider，等于没有准入。
 */
@Component
public class ContractRules {
  private static final String RESOURCE = "agent/provider-contract.json";

  private final JsonNode root;
  private final java.util.Map<String, JsonNode> ruleIndex = new java.util.HashMap<>();

  public ContractRules() {
    this(loadDefault());
  }

  /** 供测试注入自定义规范源。 */
  ContractRules(JsonNode root) {
    this.root = root;
    for (JsonNode rule : root.path("rules")) {
      ruleIndex.put(rule.path("id").asText(), rule);
    }
  }

  private static JsonNode loadDefault() {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Agent 契约源缺失或不可解析：" + RESOURCE, e);
    }
  }

  public String contractVersion() {
    return root.path("contractVersion").asText();
  }

  public int majorVersion() {
    String version = contractVersion();
    int dot = version.indexOf('.');
    return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
  }

  /** 规范定义的枚举白名单，如 capability / sourceKind / factType / status。 */
  public Set<String> enums(String name) {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : root.path("enums").path(name)) {
      values.add(item.asText());
    }
    return values;
  }

  public int limit(String name) {
    return root.path("limits").path(name).asInt();
  }

  public int limitBound(String group, String bound) {
    return root.path("limits").path(group).path(bound).asInt();
  }

  public List<String> endpointAllowlist() {
    List<String> values = new ArrayList<>();
    for (JsonNode item : root.path("runtime").path("endpointAllowlist")) {
      values.add(item.asText());
    }
    return values;
  }

  public List<String> reservedPrefixes() {
    List<String> values = new ArrayList<>();
    for (JsonNode item : root.path("runtime").path("reservedPrefixes")) {
      values.add(item.asText());
    }
    return values;
  }

  public String ruleCode(String ruleId) {
    JsonNode rule = ruleIndex.get(ruleId);
    return rule == null ? "" : rule.path("code").asText();
  }

  public String ruleLevel(String ruleId) {
    JsonNode rule = ruleIndex.get(ruleId);
    return rule == null ? RuleFailure.BLOCK : rule.path("level").asText();
  }

  /** JSON Schema 的 properties 键集合，用于 additionalProperties: false 判定。 */
  public Set<String> schemaProperties(String schema) {
    return schemaProperties(schema, null);
  }

  /** 嵌套对象（如 fact.evidence）的 properties 键集合。 */
  public Set<String> schemaProperties(String schema, String nested) {
    JsonNode node = schemaNode(schema, nested);
    Set<String> values = new HashSet<>();
    node.path("properties").fieldNames().forEachRemaining(values::add);
    return values;
  }

  public Set<String> schemaRequired(String schema) {
    return schemaRequired(schema, null);
  }

  public Set<String> schemaRequired(String schema, String nested) {
    Set<String> values = new HashSet<>();
    for (JsonNode item : schemaNode(schema, nested).path("required")) {
      values.add(item.asText());
    }
    return values;
  }

  private JsonNode schemaNode(String schema, String nested) {
    JsonNode node = root.path("schemas").path(schema);
    return nested == null ? node : node.path("properties").path(nested);
  }
}
