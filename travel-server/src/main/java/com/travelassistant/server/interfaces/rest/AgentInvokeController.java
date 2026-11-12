package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.orchestration.AgentEnvelope;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.spi.ContractRules;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L1 编排层的独立入口：{@code POST /api/agent/invoke}。
 *
 * <p>刻意不复用也不改动 {@code /api/agent/plan}：旧接口仍直连 PlanningService，行为不变；
 * 新入口验证「按 intent 走注册表、逐个 invoke、响应过契约校验、失败切兜底」这条链路。
 * 需要登录：餐饮链路会触发地图 API 配额，匿名放行等于把配额暴露给任意请求。
 *
 * <p>响应是信封 + 契约形状的 result，不含 endpoint、authRef 或任何凭据。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentInvokeController {

  private final AgentOrchestrator orchestrator;
  private final ContractRules rules;

  public AgentInvokeController(AgentOrchestrator orchestrator, ContractRules rules) {
    this.orchestrator = orchestrator;
    this.rules = rules;
  }

  public record InvokeRequest(String intent, Map<String, Object> context,
      Map<String, String> hints) {
  }

  @PostMapping("/invoke")
  public ResponseEntity<Map<String, Object>> invoke(
      @RequestBody(required = false) InvokeRequest request) {
    if (request == null || request.intent() == null || request.intent().isBlank()) {
      return badRequest("缺少 intent");
    }
    if (!rules.enums("capability").contains(request.intent())) {
      return badRequest("不支持的 intent：" + request.intent());
    }
    DispatchOutcome outcome;
    try {
      outcome = orchestrator.dispatch(new DispatchRequest(
          request.intent(), request.context(), request.hints()));
    } catch (IllegalArgumentException e) {
      return badRequest(e.getMessage());
    }
    return ResponseEntity.ok(AgentEnvelope.view(outcome, orchestrator));
  }

  private static ResponseEntity<Map<String, Object>> badRequest(String message) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
  }
}
