package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.DecisionReport;
import com.travelassistant.server.application.agent.DecisionRequest;
import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 接口：规划草案（V2 第 2 节）与综合决策建议（V3 第 3 节）。
 *
 * <p>两者都**只出建议，不保存**。用户确认后要自己调用行程接口，
 * 这样「模型建议」与「落库数据」之间始终隔着一次明确的人工确认。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

  private final PlanningService service;
  private final DecisionService decisions;

  public AgentController(PlanningService service, DecisionService decisions) {
    this.service = service;
    this.decisions = decisions;
  }

  /** 生成规划草案（尚未有行程时使用）。 */
  @PostMapping("/plan")
  public PlanDraft plan(@RequestBody PlanningRequest request) {
    return service.plan(request);
  }

  /**
   * 对已有行程给出综合决策建议：投票、预算、体检、天气一起看。
   *
   * <p>放在 {@code /api/agent} 而不是行程接口下，是因为它的语义是「帮我分析」，
   * 不是「读我的行程」；越权校验仍走行程的查看权。
   */
  @PostMapping("/itineraries/{id}/advice")
  public DecisionReport advice(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestBody(required = false) DecisionRequest request) {
    return decisions.review(id, user.getUsername(), request);
  }

  /** 能力状态：模型是否可用。 */
  @GetMapping("/status")
  public Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    boolean configured = service.modelConfigured();
    body.put("modelConfigured", configured);
    body.put("capabilities", java.util.List.of("景点检索", "天气", "购票辅助", "文字建议",
        "综合决策分析"));
    body.put("note", configured
        ? "模型已配置；建议仍需用户确认后才写入正式方案"
        : "未配置 DEEPSEEK_API_KEY，仅返回检索结果与规则判断，不生成文字建议");
    return body;
  }
}
