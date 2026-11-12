package com.travelassistant.server.application.agent.spi;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 注册期 G0 与运行期响应校验共用的唯一判定器。
 *
 * <p>枚举、上限、规则码一律从 {@link ContractRules} 读，Java 不复制第二份口径。
 * JSON Schema 层面的四个 {@code E_SCHEMA_*} 原因码按《Agent 接入规范》「拒绝报告与原因码」
 * 一节写在下面的常量里：规范源 rules 表登记的是 M/T/S/X 规则，不含 Schema 规则 id。
 *
 * <p>P0 范围说明：S-05 的 grounding 实现了数字可追溯检查；「景点名必须在 facts 里出现」
 * 需要中文分词与别名表，留到 P2 与知识库实体链接一起做，不在这里假装已覆盖。
 */
@Service
public class ContractValidator {
  private static final String E_SCHEMA_ADDITIONAL = "E_SCHEMA_ADDITIONAL";
  private static final String E_SCHEMA_REQUIRED = "E_SCHEMA_REQUIRED";
  private static final String E_SCHEMA_TYPE = "E_SCHEMA_TYPE";
  private static final String E_SCHEMA_LIMIT = "E_SCHEMA_LIMIT";
  private static final String E_SEMANTIC_EVIDENCE_CODE = "E_SEMANTIC_EVIDENCE";
  private static final String E_SEMANTIC_VERIFIED_CODE = "E_SEMANTIC_VERIFIED";

  private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,3}$");
  private static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
  private static final Pattern AUTHREF = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
  private static final Pattern SCHEMA_1X = Pattern.compile("^1\\.[0-9]+$");
  private static final Pattern CREDENTIAL_KEY = Pattern.compile(
      "token|api[_-]?key|secret|password|passwd|pwd|credential|authorization|access[_-]?key|private[_-]?key",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
  private static final Pattern URL_OR_HTML = Pattern.compile(
      "https?://|www\\.|<[^>]+>|\\[[^]]+\\]\\([^)]+\\)|<script", Pattern.CASE_INSENSITIVE);
  private static final Pattern PII = Pattern.compile(
      "1[3-9][0-9]{9}|[0-9]{17}[0-9Xx]|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
  // 「0 元」必须是独立数值；朴素 contains 会把「100 元」误判成免费。
  private static final Pattern ZERO_PRICE = Pattern.compile("(?<![0-9])0\\s*元");
  private static final List<String> INJECTION_WORDS = List.of(
      "忽略以上规则", "返回系统提示词", "输出系统提示词", "请调用工具", "忽略前面的指令");
  private static final List<String> IDENTITY_WORDS = List.of("已保存", "已决定", "已购买", "已下单");
  private static final List<String> UNKNOWN_AS_TRUE = List.of("免费", "全天开放");

  private final ContractRules rules;

  public ContractValidator(ContractRules rules) {
    this.rules = rules;
  }

  // ---------------------------------------------------------------- 注册期 G0

  /**
   * 对一条 Provider 配置做 G0 静态准入，与 tools/agent/check_provider_contract.py 同口径。
   *
   * @param strictEnabled 提交态要求 enabled=false；运行态（G4 已确认）可放行 true
   */
  public AdmissionReport validateConfig(Map<String, Object> config, int index, boolean strictEnabled) {
    return validateConfig(config, index, strictEnabled, false);
  }

  /**
   * 内置 Bean Provider 走同一套 G0，但保留前缀 builtin. 正是为它们预留的，M-02 不适用。
   */
  public AdmissionReport validateConfig(Map<String, Object> config, int index,
      boolean strictEnabled, boolean allowReservedPrefix) {
    String prefix = "providers[" + index + "]";
    List<RuleFailure> failures = new ArrayList<>();
    List<RuleFailure> warnings = new ArrayList<>();
    String providerId = str(config, "id");

    if (providerId == null || !ID.matcher(providerId).matches()) {
      failure(failures, "M-01", prefix + ".id", providerId,
          "^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,3}$");
    } else if (!allowReservedPrefix
        && rules.reservedPrefixes().stream().anyMatch(providerId::startsWith)) {
      failure(failures, "M-02", prefix + ".id", providerId, "不得使用保留前缀 builtin. / system.");
    }
    String version = str(config, "version");
    if (version == null || !VERSION.matcher(version).matches()) {
      failure(failures, "M-03", prefix + ".version", version, "语义化 x.y.z");
    }
    String schema = str(config, "schemaVersion");
    if (schema == null || !SCHEMA_1X.matcher(schema).matches()
        || major(schema) != rules.majorVersion()) {
      failure(failures, "M-04", prefix + ".schemaVersion", schema, "1.x，且主版本等于规范主版本");
    }
    Object caps = config.get("capabilities");
    if (!(caps instanceof List<?> list) || list.isEmpty()
        || new HashSet<>(list).size() != list.size()
        || list.stream().anyMatch(c -> !(c instanceof String s) || !rules.enums("capability").contains(s))) {
      failure(failures, "M-05", prefix + ".capabilities", caps, "非空、去重、全部来自枚举白名单");
    }
    String kind = str(config, "kind");
    String sideEffect = str(config, "sideEffect");
    if ("WRITE".equals(sideEffect) || ("HTTP".equals(kind) && !"NONE".equals(sideEffect))) {
      failure(failures, "M-06", prefix + ".sideEffect", sideEffect, "HTTP 必须 NONE，WRITE 一律拒绝");
    }
    for (String key : List.of("owner", "description")) {
      String value = str(config, key);
      if (value == null || value.isBlank()) {
        failure(failures, "M-07", prefix + "." + key, value, "非空字符串");
      }
    }
    if ("HTTP".equals(kind) || config.containsKey("endpoint")) {
      validateEndpoint(str(config, "endpoint"), prefix + ".endpoint", failures);
    }
    String authRef = str(config, "authRef");
    if (authRef != null && !AUTHREF.matcher(authRef).matches()) {
      failure(failures, "M-09", prefix + ".authRef", authRef, "^[A-Z][A-Z0-9_]{2,63}$");
    }
    for (String field : credentialFields(config, prefix)) {
      failure(failures, "M-09", field, "内联凭据字段", "改用 authRef 指向环境变量名");
    }
    checkBound(config, "timeoutMs", "timeoutMs", prefix, failures);
    checkBound(config, "maxResponseBytes", "maxResponseBytes", prefix, failures);
    Object enabled = config.get("enabled");
    if (!(enabled instanceof Boolean b)) {
      failure(failures, "M-11", prefix + ".enabled", enabled, "布尔值");
    } else if (strictEnabled && b) {
      failure(failures, "M-11", prefix + ".enabled", true, "提交时 false，由 G4 人工确认后置 true");
    }
    if (str(config, "healthPath") == null) {
      warnings.add(new RuleFailure("", "M-12", rules.ruleLevel("M-12"), prefix + ".healthPath",
          "null", "提供 healthPath 供可用性探测", ""));
    }

    String status = failures.isEmpty() ? "PROBING" : AgentDescriptor.REJECTED;
    return new AdmissionReport(rules.contractVersion(),
        providerId == null ? prefix : providerId, status, "G0", failures, warnings, Instant.now());
  }

  private void validateEndpoint(String endpoint, String field, List<RuleFailure> out) {
    if (endpoint == null || endpoint.isBlank()) {
      failure(out, "M-08", field, endpoint, "非空 https URL");
      return;
    }
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException e) {
      failure(out, "M-08", field, endpoint, "可解析的 https URL");
      return;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      failure(out, "M-08", field, uri.getScheme(), "https");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      failure(out, "M-08", field, "含 userinfo / query / fragment", "三者都不允许");
    }
    if (uri.getPort() != -1) {
      failure(out, "M-08", field, String.valueOf(uri.getPort()), "不显式指定端口");
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
    if (host.isEmpty()) {
      failure(out, "M-08", field, endpoint, "含合法 host");
      return;
    }
    if (IPV4.matcher(host).matches() || host.contains(":")) {
      failure(out, "M-08", field, host, "域名，不接受 IP 字面量");
    }
    if (isInternalHost(host)) {
      failure(out, "M-08", field, host, "公网域名，不接受环回 / 内网");
    }
    if (!rules.endpointAllowlist().contains(host)) {
      failure(out, "M-08", field, host,
          "host 在 endpointAllowlist 内：" + String.join(", ", rules.endpointAllowlist()));
    }
  }

  private boolean isInternalHost(String host) {
    return host.equals("localhost") || host.equals("metadata.google.internal")
        || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localdomain")
        || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")
        || host.startsWith("169.254.") || host.startsWith("172.");
  }

  private void checkBound(Map<String, Object> config, String key, String group,
      String prefix, List<RuleFailure> out) {
    Object value = config.get(key);
    int min = rules.limitBound(group, "min");
    int max = rules.limitBound(group, "max");
    if (!(value instanceof Number n) || n.longValue() < min || n.longValue() > max) {
      failure(out, "M-10", prefix + "." + key, value, min + " 至 " + max + " 的整数");
    }
  }

  // ---------------------------------------------------------------- 运行期响应

  /** 把内置 Provider 的对象结果转成与 HTTP 响应同形状的 Map，再走同一套校验。 */
  public Map<String, Object> toResponseMap(String providerId, AgentResult result) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", rules.contractVersion());
    body.put("providerId", providerId);
    body.put("status", result.status());
    List<Map<String, Object>> facts = new ArrayList<>();
    for (Fact fact : result.facts()) {
      Map<String, Object> factMap = new LinkedHashMap<>();
      factMap.put("factId", fact.factId());
      factMap.put("type", fact.type());
      factMap.put("value", fact.value());
      Map<String, Object> evidence = new LinkedHashMap<>();
      Evidence e = fact.evidence();
      evidence.put("sourceKind", e.sourceKind());
      evidence.put("sourceId", e.sourceId());
      evidence.put("verificationStatus", e.verificationStatus());
      evidence.put("fetchedAt", e.fetchedAt() == null ? null : e.fetchedAt().toString());
      evidence.put("excerpt", e.excerpt());
      factMap.put("evidence", evidence);
      facts.add(factMap);
    }
    body.put("facts", facts);
    body.put("advice", result.advice());
    body.put("unknownFields", result.unknownFields());
    body.put("notices", result.notices());
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("elapsedMs", result.usage().elapsedMs());
    usage.put("modelCalls", result.usage().modelCalls());
    body.put("usage", usage);
    return body;
  }

  /** 运行期统一校验：结构、事实证据、语义与安全，任一 BLOCK 失败即整体丢弃该次响应。 */
  public List<RuleFailure> validateResult(Map<String, Object> response) {
    List<RuleFailure> out = new ArrayList<>();
    schemaShape(out, response, "response", "response", null);
    String schema = str(response, "schemaVersion");
    if (schema == null || !SCHEMA_1X.matcher(schema).matches()
        || major(schema) != rules.majorVersion()) {
      failCode(out, E_SCHEMA_TYPE, "M-04", "schemaVersion", schema, "1.x 且与规范主版本一致");
    }
    String providerId = str(response, "providerId");
    if (providerId == null || providerId.isEmpty() || providerId.length() > rules.limit("maxRunIdChars")) {
      failCode(out, E_SCHEMA_TYPE, "M-01", "providerId", providerId, "非空且不超过 64 字符");
    }
    String status = str(response, "status");
    if (status == null || !rules.enums("status").contains(status)) {
      failCode(out, E_SCHEMA_TYPE, "T-02", "status", status, "OK / DEGRADED / REFUSED");
    }
    Object facts = response.get("facts");
    if (!(facts instanceof List<?> factList)) {
      failCode(out, E_SCHEMA_TYPE, "T-05", "facts", facts, "数组");
    } else {
      if (factList.size() > rules.limit("maxFacts")) {
        failCode(out, E_SCHEMA_LIMIT, "T-05", "facts", factList.size(), "不超过 " + rules.limit("maxFacts") + " 条");
      }
      for (int i = 0; i < factList.size(); i++) {
        Object item = factList.get(i);
        if (item instanceof Map<?, ?> raw) {
          validateFact(out, castMap(raw), "facts[" + i + "]");
        } else {
          failCode(out, E_SCHEMA_TYPE, "S-01", "facts[" + i + "]", item, "Fact 对象");
        }
      }
    }
    checkStringList(out, response, "unknownFields", "maxUnknownFields");
    checkStringList(out, response, "notices", "maxNotices");
    String advice = str(response, "advice");
    if (response.containsKey("advice") && advice != null) {
      validateAdvice(out, advice, facts instanceof List<?> list ? list : List.of());
    }
    Object usage = response.get("usage");
    if (!(usage instanceof Map<?, ?> raw)) {
      failCode(out, E_SCHEMA_REQUIRED, "T-07", "usage", usage, "含 elapsedMs 的对象");
    } else {
      Map<String, Object> usageMap = castMap(raw);
      schemaShape(out, usageMap, "usage", "response", "usage");
      Object elapsed = usageMap.get("elapsedMs");
      if (!(elapsed instanceof Number n) || n.longValue() < 0) {
        failCode(out, E_SCHEMA_TYPE, "T-07", "usage.elapsedMs", elapsed, "非负整数");
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Map<?, ?> raw) {
    return (Map<String, Object>) raw;
  }

  private void validateFact(List<RuleFailure> out, Map<String, Object> fact, String field) {
    schemaShape(out, fact, field, "fact", null);
    String type = str(fact, "type");
    if (type == null || !rules.enums("factType").contains(type)) {
      failCode(out, E_SCHEMA_TYPE, "S-01", field + ".type", type, "factType 枚举内的值");
    }
    Object value = fact.get("value");
    if (!(value instanceof Map<?, ?> rawValue)) {
      failCode(out, E_SCHEMA_TYPE, "S-01", field + ".value", value, "对象");
    } else if (rawValue.size() > rules.limit("maxFactValueProperties")) {
      failCode(out, E_SCHEMA_LIMIT, "T-05", field + ".value", rawValue.size(),
          "不超过 " + rules.limit("maxFactValueProperties") + " 个键");
    }
    Object evidence = fact.get("evidence");
    if (!(evidence instanceof Map<?, ?> rawEvidence)) {
      failCode(out, E_SEMANTIC_EVIDENCE_CODE, "S-01", field + ".evidence", evidence, "含完整 evidence 的对象");
      return;
    }
    Map<String, Object> ev = castMap(rawEvidence);
    schemaShape(out, ev, field + ".evidence", "fact", "evidence");
    String sourceKind = str(ev, "sourceKind");
    if (sourceKind == null || !rules.enums("sourceKind").contains(sourceKind)) {
      failCode(out, E_SEMANTIC_EVIDENCE_CODE, "S-01", field + ".evidence.sourceKind", sourceKind,
          "sourceKind 枚举内的值");
    }
    String verification = str(ev, "verificationStatus");
    if (verification == null || !rules.enums("externalVerificationStatus").contains(verification)) {
      failCode(out, E_SEMANTIC_VERIFIED_CODE, "S-02", field + ".evidence.verificationStatus", verification,
          "unverified 或 pending；外部 Provider 无权产出 verified");
    }
    if (str(ev, "fetchedAt") == null) {
      failCode(out, E_SEMANTIC_EVIDENCE_CODE, "S-01", field + ".evidence.fetchedAt", null, "ISO 时间字符串");
    }
  }

  private void validateAdvice(List<RuleFailure> out, String advice, List<?> facts) {
    if (advice.length() > rules.limit("maxAdviceChars")) {
      failCode(out, E_SCHEMA_LIMIT, "T-05", "advice", advice.length(),
          "不超过 " + rules.limit("maxAdviceChars") + " 字符");
    }
    if (URL_OR_HTML.matcher(advice).find()) {
      failure(out, "S-04", "advice", "含 URL / HTML / Markdown 链接", "纯文本，不含链接与标签");
    }
    if (PII.matcher(advice).find()) {
      failure(out, "S-06", "advice", "含疑似 PII", "不得返回手机号 / 身份证 / 邮箱 / 住址");
    }
    for (String word : INJECTION_WORDS) {
      if (advice.contains(word)) {
        failure(out, "S-08", "advice", word, "不得返回指令型文本");
      }
    }
    for (String word : IDENTITY_WORDS) {
      if (advice.contains(word)) {
        failure(out, "S-07", "advice", word, "不得声明已保存 / 已决定 / 已购买 / 已下单");
      }
    }
    for (String word : UNKNOWN_AS_TRUE) {
      if (advice.contains(word)) {
        failure(out, "S-03", "advice", word, "未知信息写「未知」，不得写成确定值");
        break;
      }
    }
    if (ZERO_PRICE.matcher(advice).find()) {
      failure(out, "S-03", "advice", "0 元", "未知信息写「未知」，不得写成确定值");
    }
    String factText = facts.toString();
    Matcher digits = DIGITS.matcher(advice);
    while (digits.find()) {
      if (!factText.contains(digits.group())) {
        failure(out, "S-05", "advice", digits.group(), "advice 中的数字必须能在本次 facts 中找到");
        break;
      }
    }
  }

  private void checkStringList(List<RuleFailure> out, Map<String, Object> response,
      String key, String limitKey) {
    Object value = response.get(key);
    if (!(value instanceof List<?> list)) {
      failCode(out, E_SCHEMA_TYPE, "T-05", key, value, "数组");
      return;
    }
    if (list.size() > rules.limit(limitKey)) {
      failCode(out, E_SCHEMA_LIMIT, "T-05", key, list.size(), "不超过 " + rules.limit(limitKey) + " 项");
    }
  }

  private void schemaShape(List<RuleFailure> out, Map<String, Object> object,
      String field, String schema, String nested) {
    Set<String> allowed = rules.schemaProperties(schema, nested);
    Set<String> required = rules.schemaRequired(schema, nested);
    for (String key : required) {
      if (!object.containsKey(key)) {
        failCode(out, E_SCHEMA_REQUIRED, "T-05", field + "." + key, null, "必填字段 " + key);
      }
    }
    for (String key : object.keySet()) {
      if (!allowed.contains(key)) {
        failCode(out, E_SCHEMA_ADDITIONAL, "T-05", field + "." + key, key,
            "只允许 " + String.join(" / ", allowed));
      }
    }
  }

  // ---------------------------------------------------------------- 工具方法

  private void failure(List<RuleFailure> out, String rule, String field, Object actual, String expected) {
    out.add(new RuleFailure(rules.ruleCode(rule), rule, rules.ruleLevel(rule), field,
        String.valueOf(actual), expected, ""));
  }

  private void failCode(List<RuleFailure> out, String code, String rule, String field,
      Object actual, String expected) {
    out.add(new RuleFailure(code, rule, RuleFailure.BLOCK, field,
        String.valueOf(actual), expected, ""));
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof String s ? s : null;
  }

  private static int major(String schemaVersion) {
    int dot = schemaVersion.indexOf('.');
    try {
      return Integer.parseInt(dot < 0 ? schemaVersion : schemaVersion.substring(0, dot));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static List<String> credentialFields(Object node, String prefix) {
    List<String> found = new ArrayList<>();
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String path = prefix + "." + entry.getKey();
        if (CREDENTIAL_KEY.matcher(String.valueOf(entry.getKey())).find()) {
          found.add(path);
        }
        found.addAll(credentialFields(entry.getValue(), path));
      }
    } else if (node instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        found.addAll(credentialFields(list.get(i), prefix + "[" + i + "]"));
      }
    }
    return found;
  }
}
