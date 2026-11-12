package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.spi.AdmissionController;
import com.travelassistant.server.application.agent.spi.AdmissionReport;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentRegistry;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.Resolution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力协商与准入报告接口。capabilities 只返回状态与能力标签，不含 endpoint、authRef 或凭据，
 * 因此可以匿名访问；前端据此动态生成入口与禁用态，不硬编码可用列表。
 *
 * <p>providers / admission / reload 含注册表内部信息，沿用默认鉴权（需登录）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentCapabilityController {

  private final AgentRegistry registry;
  private final ContractRules rules;
  private final AdmissionController admission;

  public AgentCapabilityController(AgentRegistry registry, ContractRules rules,
      AdmissionController admission) {
    this.registry = registry;
    this.rules = rules;
    this.admission = admission;
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contractVersion", rules.contractVersion());
    body.put("registryVersion", registry.version());
    body.put("registryNote", registry.note());
    List<Map<String, Object>> providers = new ArrayList<>();
    for (AgentDescriptor descriptor : registry.descriptors()) {
      providers.add(view(descriptor));
    }
    body.put("providers", providers);
    return body;
  }

  @GetMapping("/providers")
  public Map<String, Object> providers() {
    Map<String, Object> body = capabilities();
    List<Map<String, Object>> routes = new ArrayList<>();
    for (String intent : rules.enums("capability")) {
      Resolution resolution = registry.resolve(intent, null);
      Map<String, Object> route = new LinkedHashMap<>();
      route.put("intent", resolution.intent());
      route.put("primary", resolution.primary());
      route.put("fallback", resolution.fallback());
      routes.add(route);
    }
    body.put("routes", routes);
    return body;
  }

  @GetMapping("/providers/{id}/admission")
  public ResponseEntity<AdmissionReport> admission(@PathVariable String id) {
    AdmissionReport report = registry.admission(id);
    return report == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(report);
  }

  @PostMapping("/providers/reload")
  public Map<String, Object> reload() {
    registry.reload();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reloaded", true);
    body.put("registryVersion", registry.version());
    body.put("registryNote", registry.note());
    body.put("providerCount", registry.descriptors().size());
    return body;
  }

  /** G1 契约探针加 G2 安全探针。生产路径按配置发真实请求，不注入桩。 */
  @PostMapping("/providers/{id}/probe")
  public Map<String, Object> probe(@PathVariable String id) {
    return gateResponse(id, admission.probe(id, null));
  }

  /** G3 影子。samples 缺省读规范源 shadowSamples（20）。 */
  @PostMapping("/providers/{id}/shadow")
  public Map<String, Object> shadow(@PathVariable String id,
      @RequestParam(defaultValue = "0") int samples) {
    return gateResponse(id, admission.shadow(id, samples, null));
  }

  /** G4 人工启用：需 G3 通过且配置里 enabled=true。 */
  @PostMapping("/providers/{id}/activate")
  public Map<String, Object> activate(@PathVariable String id) {
    return gateResponse(id, admission.activate(id));
  }

  private Map<String, Object> gateResponse(String id, AdmissionReport report) {
    registry.reload();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("report", report);
    body.put("status", registry.descriptors().stream()
        .filter(item -> item.id().equals(id))
        .findFirst().map(AgentDescriptor::status).orElse("UNREGISTERED"));
    body.put("registryVersion", registry.version());
    body.put("shadow", admission.shadowStats(id));
    return body;
  }

  private static Map<String, Object> view(AgentDescriptor descriptor) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", descriptor.id());
    item.put("version", descriptor.version());
    item.put("schemaVersion", descriptor.schemaVersion());
    item.put("capabilities", descriptor.capabilities());
    item.put("kind", descriptor.kind());
    item.put("sideEffect", descriptor.sideEffect());
    item.put("enabled", descriptor.enabled());
    item.put("status", descriptor.status());
    item.put("lastRejectionCode", descriptor.lastRejectionCode());
    return item;
  }
}
