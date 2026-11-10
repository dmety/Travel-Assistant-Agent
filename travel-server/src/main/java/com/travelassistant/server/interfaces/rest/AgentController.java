package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.ConfirmDraftRequest;
import com.travelassistant.server.application.agent.DecisionReport;
import com.travelassistant.server.application.agent.DecisionRequest;
import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.MaterializedPlan;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanMaterializer;
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
 * <p>规划、综合决策与「确认载荷」都**只出建议，不保存**。用户确认后要自己调用行程接口，
 * 这样「模型建议」与「落库数据」之间始终隔着一次明确的人工确认。
 *
 * <p>{@code /plan/materialize} 也不写库：它只把用户确认过的草案转成
 * {@code POST /api/itineraries} 与 {@code PUT /api/itineraries/{id}/daily} 能直接用的载荷。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

  private final PlanningService service;
  private final DecisionService decisions;
  private final PlanMaterializer materializer;

  public AgentController(PlanningService service, DecisionService decisions,
      PlanMaterializer materializer) {
    this.service = service;
    this.decisions = decisions;
    this.materializer = materializer;
  }

  /** 生成规划草案（尚未有行程时使用）。 */
  @PostMapping("/plan")
  public PlanDraft plan(@RequestBody PlanningRequest request) {
    return service.plan(request);
  }

  /**
   * 用户确认草案后生成行程载荷（**只读**，不创建行程）。
   *
   * <p>景点事实按 ID 从知识库重新读取，不接受客户端回传的票价或来源；
   * 真正落库由调用方接着调用既有行程接口完成，并带上创建响应里的版本号。
   */
  @PostMapping("/plan/materialize")
  public MaterializedPlan materialize(@RequestBody ConfirmDraftRequest request) {
    return materializer.materialize(request);
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

  /**
   * 能力状态：模型是否可用。
   *
   * <p>规划接口目前是确定性检索流水线，模型只做可选的文字润色。未配置密钥时
   * 不得宣称具备「文字建议」能力，否则会把降级路径说成已实现的 Agent 能力。
   */
  @GetMapping("/status")
  public Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    boolean configured = service.modelConfigured();
    body.put("modelConfigured", configured);
    // 规则能力与模型能力分开列：补问、逐日草案、检查、确认载荷都不依赖密钥，
    // 未配置模型时不能把它们一起说成「没有」。
    java.util.List<String> capabilities = new java.util.ArrayList<>(java.util.List.of(
        "景点检索", "天气", "购票辅助", "结构化补问", "逐日草案", "草案日期与预算检查",
        "确认载荷", "综合决策分析"));
    if (configured) {
      capabilities.add("文字建议");
    }
    body.put("capabilities", capabilities);
    body.put("note", configured
        ? "模型已配置；文字建议仅为措辞润色，建议仍需用户确认后才写入正式方案"
        : "未配置 DEEPSEEK_API_KEY：规划接口只返回确定性检索结果与规则判断，不生成文字建议");
    return body;
  }
}
