package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.PlanMaterializer;
import com.travelassistant.server.application.agent.PlanningService;
import com.travelassistant.server.application.ticket.TicketAssistantService;
import com.travelassistant.server.application.ticket.TicketCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST 控制器分支补充：覆盖 HTTP 集成测试走不到的两种状态。
 *
 * <p>收紧 {@code /api/agent/tickets/advice} 的鉴权后，匿名请求在 Spring Security 层就被拒绝，
 * 控制器里的 null principal 防御分支不再由 HTTP 路径触发；模型密钥在测试环境也始终为空，
 * {@code AgentController.status()} 的「已配置」分支同样到不了。这里用纯单元测试固定这两处契约，
 * 避免把覆盖率缺口当成代码删除的理由。
 */
@DisplayName("REST 控制器分支")
class RestControllerBranchTest {

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("模型已配置时 status 才宣称文字建议，说明文字同步切换")
  void statusReflectsConfiguredModel() {
    PlanningService planning = mock(PlanningService.class);
    when(planning.modelConfigured()).thenReturn(true);
    AgentController controller = new AgentController(planning,
        mock(DecisionService.class), mock(PlanMaterializer.class),
        mock(com.travelassistant.server.infrastructure.ai.DeepSeekClient.class));

    Map<String, Object> body = controller.status();
    assertThat(body.get("modelConfigured")).isEqualTo(true);
    assertThat((List<String>) body.get("capabilities")).contains("文字建议");
    assertThat(body.get("note").toString()).contains("模型已配置");
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("未配置模型时不宣称文字建议，但规则能力仍逐项列出")
  void statusKeepsRuleCapabilitiesWithoutModel() {
    PlanningService planning = mock(PlanningService.class);
    when(planning.modelConfigured()).thenReturn(false);
    AgentController controller = new AgentController(planning,
        mock(DecisionService.class), mock(PlanMaterializer.class),
        mock(com.travelassistant.server.infrastructure.ai.DeepSeekClient.class));

    Map<String, Object> body = controller.status();
    assertThat(body.get("modelConfigured")).isEqualTo(false);
    List<String> capabilities = (List<String>) body.get("capabilities");
    assertThat(capabilities).doesNotContain("文字建议")
        .contains("结构化补问", "逐日草案", "草案日期与预算检查", "确认载荷");
    assertThat(body.get("note").toString()).contains("未配置 DEEPSEEK_API_KEY");
  }

  @Test
  @DisplayName("缺 AuthenticationPrincipal 时购票建议按匿名用户处理，不取用户名")
  void ticketAdviceAcceptsMissingPrincipal() {
    TicketAssistantService service = mock(TicketAssistantService.class);
    TicketAssistantService.Request request =
        new TicketAssistantService.Request(null, null, null, null);

    new TicketAssistantController(service, mock(TicketCatalog.class)).advice(null, request);

    verify(service).advise(request, null);
  }
}
