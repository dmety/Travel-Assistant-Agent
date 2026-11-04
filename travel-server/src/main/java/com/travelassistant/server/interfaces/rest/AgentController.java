package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规划草案接口（V2 第 2 节）。
 *
 * <p>只生成草案，不保存。用户确认后要自己调用行程接口创建或修改，
 * 这样「模型建议」和「落库数据」之间始终隔着一次明确的人工确认。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

  private final PlanningService service;

  public AgentController(PlanningService service) {
    this.service = service;
  }

  /** 生成规划草案。 */
  @PostMapping("/plan")
  public PlanDraft plan(@RequestBody PlanningRequest request) {
    return service.plan(request);
  }

  /** 能力状态：模型是否可用。 */
  @GetMapping("/status")
  public Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    boolean configured = service.modelConfigured();
    body.put("modelConfigured", configured);
    body.put("capabilities", java.util.List.of("景点检索", "天气", "购票辅助", "文字建议"));
    body.put("note", configured
        ? "模型已配置；建议仍需用户确认后才写入正式方案"
        : "未配置 DEEPSEEK_API_KEY，仅返回检索结果，不生成文字建议");
    return body;
  }
}
