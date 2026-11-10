package com.travelassistant.server.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.Usage;
import com.travelassistant.server.interfaces.rest.AgentInvokeController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** 编排 REST 入口：参数校验、信封结构与脱敏。 */
@DisplayName("编排 REST 入口")
class AgentInvokeControllerTest {

  private final ObjectMapper json = new ObjectMapper();
  private AgentOrchestrator orchestrator;
  private AgentInvokeController controller;

  @BeforeEach
  void setUp() {
    orchestrator = mock(AgentOrchestrator.class);
    controller = new AgentInvokeController(orchestrator, new ContractRules());
  }

  private DispatchOutcome outcome() {
    AgentResult result = new AgentResult("OK", List.of(), "已按已核实事实整理建议",
        List.of(), List.of(), new Usage(0L, 0));
    return new DispatchOutcome("FOOD_RECOMMEND", "OK", "builtin.food", result,
        List.of(new DispatchOutcome.Attempt("builtin.food", "OK", "OK", List.of())), "v-test");
  }

  @Test
  @DisplayName("缺 intent 或 intent 不在枚举里都返回 400")
  void rejectsBadIntent() {
    assertThat(controller.invoke(null).getStatusCode().value()).isEqualTo(400);
    assertThat(controller.invoke(new AgentInvokeController.InvokeRequest("", Map.of(), Map.of()))
        .getStatusCode().value()).isEqualTo(400);
    ResponseEntity<Map<String, Object>> bad = controller.invoke(
        new AgentInvokeController.InvokeRequest("NOT_AN_INTENT", Map.of(), Map.of()));
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
    assertThat(bad.getBody()).containsKey("error");
  }

  @Test
  @DisplayName("合规请求返回信封，不含 endpoint 与 authRef")
  void returnsSanitizedEnvelope() throws Exception {
    DispatchOutcome outcome = outcome();
    when(orchestrator.dispatch(any())).thenReturn(outcome);
    when(orchestrator.contractBody(outcome)).thenReturn(Map.of(
        "schemaVersion", "1.0", "providerId", "builtin.food", "status", "OK"));

    ResponseEntity<Map<String, Object>> response = controller.invoke(
        new AgentInvokeController.InvokeRequest("FOOD_RECOMMEND",
            Map.of("city", "杭州"), Map.of()));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    Map<String, Object> body = response.getBody();
    assertThat(body).containsKeys("intent", "status", "providerId", "result",
        "attempts", "registryVersion");
    assertThat(body.get("registryVersion")).isEqualTo("v-test");
    String serialized = json.writeValueAsString(body);
    assertThat(serialized).doesNotContain("endpoint").doesNotContain("authRef");
  }

  @Test
  @DisplayName("编排层拒绝时转成 400，不抛 500")
  void orchestratorRejectionBecomesBadRequest() {
    when(orchestrator.dispatch(any())).thenThrow(
        new IllegalArgumentException("不支持的 intent"));
    ResponseEntity<Map<String, Object>> response = controller.invoke(
        new AgentInvokeController.InvokeRequest("FOOD_RECOMMEND", Map.of(), Map.of()));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }
}
