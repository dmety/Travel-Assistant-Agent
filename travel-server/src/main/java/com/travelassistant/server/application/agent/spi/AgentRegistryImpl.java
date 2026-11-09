package com.travelassistant.server.application.agent.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 注册表：内置 Bean 与配置式 HTTP Provider 合并成不可变快照，一次引用替换。
 *
 * <p>默认拒绝：G0 判 REJECTED 的不进注册表；过 G0 但未走完 G1 至 G4 的停在 PROBING / SHADOW，
 * 在 capabilities 里可见但不路由；只有 G4 已确认的 ACTIVE 参与 resolve。
 * 配置损坏时保留上一版快照并标 LAST_KNOWN_GOOD，绝不悄悄降级后继续用。
 * 内置 Bean 之间出现重复 id 直接 fail fast 拒绝启动；外部配置 id 与已注册项冲突只判该条 REJECTED。
 */
@Service
public class AgentRegistryImpl implements AgentRegistry {

  private record Snapshot(
      Map<String, AgentProvider> active,
      List<AgentDescriptor> descriptors,
      Map<String, AdmissionReport> reports,
      Map<String, Resolution> routes,
      String version,
      String note) {

    static Snapshot empty() {
      return new Snapshot(Map.of(), List.of(), Map.of(), Map.of(), "empty", "NOT_LOADED");
    }
  }

  private final List<AgentProvider> beans;
  private final ContractValidator validator;
  private final ContractRules rules;
  private final AgentProviderConfigLoader loader;
  private final AdmissionController admission;
  private final ObjectMapper json;
  private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

  @Autowired
  public AgentRegistryImpl(List<AgentProvider> beans, ContractValidator validator,
      ContractRules rules, AgentProviderConfigLoader loader, ObjectMapper json,
      AdmissionController admission) {
    this.beans = List.copyOf(beans);
    this.validator = validator;
    this.rules = rules;
    this.loader = loader;
    this.json = json;
    this.admission = admission;
    reload();
  }

  @Override
  public synchronized void reload() {
    AgentProviderConfigLoader.Document doc = loader.load();
    if (doc.broken()) {
      Snapshot old = current.get();
      current.set(new Snapshot(old.active(), old.descriptors(), old.reports(), old.routes(),
          old.version(), "LAST_KNOWN_GOOD: " + doc.error()));
      return;
    }

    Map<String, AgentProvider> active = new LinkedHashMap<>();
    List<AgentDescriptor> descriptors = new ArrayList<>();
    Map<String, AdmissionReport> reports = new LinkedHashMap<>();

    for (int i = 0; i < beans.size(); i++) {
      AgentProvider bean = beans.get(i);
      String id = bean.descriptor().id();
      if (active.containsKey(id)) {
        throw new IllegalStateException("重复的内置 Provider id，拒绝启动：" + id);
      }
      AdmissionReport report = validator.validateConfig(beanConfig(bean.descriptor()), i, false, true);
      reports.put(id, report);
      if (report.blocked()) {
        descriptors.add(rejected(bean.descriptor(), report));
      } else {
        active.put(id, bean);
        descriptors.add(bean.descriptor());
      }
    }

    // 删掉配置再恢复时不能沿用旧的 ACTIVE：只保留本次配置里仍存在的 id 的准入进度。
    Set<String> configuredIds = new HashSet<>();
    for (Map<String, Object> config : doc.providers()) {
      String id = string(config, "id");
      if (id != null) {
        configuredIds.add(id);
      }
    }
    admission.resetAbsent(configuredIds);

    // 冲突组内全部拒绝：只拒后一条会留下「第一条可路由、报告却显示 REJECTED」的不一致状态。
    Set<String> conflicting = conflictingIds(doc.providers(), active.keySet());
    for (int i = 0; i < doc.providers().size(); i++) {
      Map<String, Object> config = doc.providers().get(i);
      String id = string(config, "id");
      String key = id == null ? "providers[" + i + "]" : id;
      AdmissionReport report = validator.validateConfig(config, i, false);
      if (id != null && conflicting.contains(id)) {
        report = withDuplicateFailure(report, key);
      }
      reports.put(key, report);
      AgentDescriptor descriptor = descriptorFromConfig(config, key);
      if (report.blocked()) {
        descriptors.add(rejected(descriptor, report));
      } else if (admission.active(key)) {
        // 只有过了 G4 的 Provider 才进 active 快照；运行期违规回执接进熔断计数。
        String providerId = key;
        AgentProvider adapter = new HttpAgentAdapter(config, validator, rules, json,
            code -> {
              if (code == null) {
                admission.recordRuntimeSuccess(providerId);
              } else {
                admission.recordRuntimeViolation(providerId, code);
              }
            });
        active.put(key, adapter);
        descriptors.add(adapter.descriptor());
      } else {
        // G0 通过但未走完 G1 至 G4：状态由准入控制器给出，绝不参与路由。
        descriptors.add(withStage(descriptor, admission.stage(key)));
      }
    }

    String version = doc.loadedAt() + "/" + active.size() + "/" + descriptors.size();
    current.set(new Snapshot(Map.copyOf(active), List.copyOf(descriptors),
        Map.copyOf(reports), buildRoutes(doc.routes(), active), version, "OK"));
  }

  @Override
  public Optional<AgentProvider> find(String providerId) {
    return Optional.ofNullable(current.get().active().get(providerId));
  }

  @Override
  public List<AgentDescriptor> descriptors() {
    return current.get().descriptors();
  }

  @Override
  public Resolution resolve(String intent, String userIdHash) {
    Snapshot snapshot = current.get();
    Resolution configured = snapshot.routes().get(intent);
    if (configured != null) {
      return configured;
    }
    List<String> fallback = snapshot.active().containsKey("builtin.rules")
        ? List.of("builtin.rules") : List.of();
    return new Resolution(intent, null, fallback);
  }

  @Override
  public List<AdmissionReport> reports() {
    return List.copyOf(current.get().reports().values());
  }

  @Override
  public AdmissionReport admission(String providerId) {
    return current.get().reports().get(providerId);
  }

  public String version() {
    return current.get().version();
  }

  public String note() {
    return current.get().note();
  }

  // ---------------------------------------------------------------- 内部

  private static Set<String> conflictingIds(List<Map<String, Object>> providers,
      Set<String> registeredIds) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Map<String, Object> provider : providers) {
      String id = string(provider, "id");
      if (id != null) {
        counts.merge(id, 1, Integer::sum);
      }
    }
    Set<String> conflicting = new HashSet<>(registeredIds);
    counts.forEach((id, count) -> {
      if (count > 1) {
        conflicting.add(id);
      }
    });
    return conflicting;
  }

  private static Map<String, Object> beanConfig(AgentDescriptor descriptor) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("id", descriptor.id());
    config.put("kind", "BEAN");
    config.put("version", descriptor.version());
    config.put("schemaVersion", descriptor.schemaVersion());
    config.put("capabilities", new ArrayList<>(descriptor.capabilities()));
    config.put("sideEffect", descriptor.sideEffect());
    config.put("owner", "builtin");
    config.put("description", "内置 Bean Provider");
    config.put("healthPath", "/healthz");
    config.put("timeoutMs", 4000);
    config.put("maxResponseBytes", 65536);
    config.put("enabled", true);
    return config;
  }

  private static AgentDescriptor descriptorFromConfig(Map<String, Object> config, String key) {
    Set<String> capabilities = new HashSet<>();
    if (config.get("capabilities") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof String s) {
          capabilities.add(s);
        }
      }
    }
    return new AgentDescriptor(key,
        String.valueOf(config.getOrDefault("version", "0.0.0")),
        String.valueOf(config.getOrDefault("schemaVersion", "1.0")),
        capabilities,
        String.valueOf(config.getOrDefault("kind", "HTTP")),
        String.valueOf(config.getOrDefault("sideEffect", "NONE")),
        Boolean.TRUE.equals(config.get("enabled")),
        AgentDescriptor.PROBING, null);
  }

  private static AgentDescriptor rejected(AgentDescriptor descriptor, AdmissionReport report) {
    return new AgentDescriptor(descriptor.id(), descriptor.version(), descriptor.schemaVersion(),
        descriptor.capabilities(), descriptor.kind(), descriptor.sideEffect(), false,
        AgentDescriptor.REJECTED, report.lastRejectionCode());
  }

  private static AgentDescriptor withStage(AgentDescriptor descriptor, String stage) {
    return new AgentDescriptor(descriptor.id(), descriptor.version(), descriptor.schemaVersion(),
        descriptor.capabilities(), descriptor.kind(), descriptor.sideEffect(),
        descriptor.enabled(), stage, null);
  }

  private static AdmissionReport withDuplicateFailure(AdmissionReport report, String key) {
    List<RuleFailure> failures = new ArrayList<>(report.failures());
    failures.add(new RuleFailure("E_META_ID", "M-01", RuleFailure.BLOCK,
        "providers.id", key, "全局唯一，与已注册 Provider 冲突", "删除重复条目或更换 id"));
    return new AdmissionReport(report.contractVersion(), report.providerId(),
        AgentDescriptor.REJECTED, report.stage(), failures, report.warnings(), Instant.now());
  }

  private Map<String, Resolution> buildRoutes(List<Map<String, Object>> configured,
      Map<String, AgentProvider> active) {
    Map<String, Resolution> routes = new LinkedHashMap<>();
    List<String> defaultFallback = active.containsKey("builtin.rules")
        ? List.of("builtin.rules") : List.of();
    // 内置 Bean 按声明的能力自动成为默认 primary；否则编排层对每个 intent 都只会拿到
    // builtin.rules，SPI 基座等于没接进业务链路。builtin.rules 声明了全部能力，必须排除。
    // 多个 Bean 声明同一能力时按 id 字典序取第一个，保证 reload 结果可复现。
    Map<String, String> defaultPrimary = new LinkedHashMap<>();
    for (String id : new java.util.TreeSet<>(active.keySet())) {
      AgentProvider provider = active.get(id);
      AgentDescriptor descriptor = provider.descriptor();
      if ("builtin.rules".equals(id) || !"BEAN".equals(descriptor.kind())) {
        continue;
      }
      for (String capability : descriptor.capabilities()) {
        defaultPrimary.putIfAbsent(capability, id);
      }
    }
    for (String intent : rules.enums("capability")) {
      routes.put(intent, new Resolution(intent, defaultPrimary.get(intent), defaultFallback));
    }
    for (Map<String, Object> route : configured) {
      String intent = string(route, "intent");
      if (intent == null || !rules.enums("capability").contains(intent)) {
        continue;
      }
      String primary = string(route, "primary");
      if (primary == null || !active.containsKey(primary)) {
        primary = null;
      }
      List<String> fallback = new ArrayList<>();
      if (route.get("fallback") instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof String s && active.containsKey(s) && !s.equals(primary)) {
            fallback.add(s);
          }
        }
      }
      // 配置了外部 primary 时，仍把该 intent 的内置 Bean 留在 fallback 链上：
      // 外部服务不可用不等于本地能力也不可用，否则一次超时就直接撞 builtin.rules。
      String builtinBean = defaultPrimary.get(intent);
      if (builtinBean != null && !builtinBean.equals(primary)
          && !fallback.contains(builtinBean) && active.containsKey(builtinBean)) {
        fallback.add(builtinBean);
      }
      if (defaultFallback.contains("builtin.rules") && !fallback.contains("builtin.rules")
          && !"builtin.rules".equals(primary)) {
        fallback.add("builtin.rules");
      }
      routes.put(intent, new Resolution(intent, primary, fallback));
    }
    return Map.copyOf(routes);
  }

  private static String string(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof String s ? s : null;
  }
}
