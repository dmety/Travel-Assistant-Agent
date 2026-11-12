package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两轮投票的独立入口：{@code POST /api/agent/vote}。
 *
 * <p>与 {@code /api/agent/invoke} 并列，不改动 {@code /api/agent/plan}，也不复用
 * V3 的人投票接口。路径落在 {@code /api/agent/**} 且不在安全白名单里，因此自动继承
 * {@code .anyRequest().authenticated()}，匿名返回 401，无需改安全配置。
 *
 * <p>响应是信封加结构化计票结果，不含 endpoint、authRef 或任何凭据。三条
 * {@code E_VOTE_*} 只出现在 {@code result.rejections} 里，不进准入报告。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentVoteController {

  private final VoteOrchestrator orchestrator;

  public AgentVoteController(VoteOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @PostMapping("/vote")
  public ResponseEntity<Map<String, Object>> vote(
      @RequestBody(required = false) VoteOrchestrator.VoteRequest request) {
    if (request == null || request.intent() == null || request.intent().isBlank()) {
      return badRequest("缺少 intent");
    }
    VoteOrchestrator.VoteRun run;
    try {
      run = orchestrator.vote(request);
    } catch (IllegalArgumentException e) {
      return badRequest(e.getMessage());
    }
    return ResponseEntity.ok(view(run));
  }

  private static Map<String, Object> view(VoteOrchestrator.VoteRun run) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("intent", run.intent());
    body.put("status", run.status());
    body.put("registryVersion", run.registryVersion());
    body.put("candidateSource", run.candidateSource());
    body.put("judges", run.judges());
    body.put("plans", run.plans());
    body.put("draft", run.draft());
    body.put("collectedFactCount", run.collectedFactCount());
    body.put("result", result(run.outcome()));
    body.put("notices", run.notices());
    return body;
  }

  private static Map<String, Object> result(PlanVoteTally.VoteOutcome outcome) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", outcome.schemaVersion());
    body.put("intent", outcome.intent());
    body.put("winner", outcome.winner());
    List<Map<String, Object>> ranking = new ArrayList<>();
    for (PlanVoteTally.Ranking item : outcome.ranking()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("planId", item.planId());
      row.put("score", item.score());
      row.put("voterCount", item.voterCount());
      row.put("weightSum", item.weightSum());
      row.put("abstained", item.abstained());
      List<Map<String, Object>> breakdown = new ArrayList<>();
      for (PlanVoteTally.Breakdown part : item.breakdown()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("providerId", part.providerId());
        entry.put("normalized", part.normalized());
        entry.put("weight", part.weight());
        entry.put("criteria", part.criteria());
        entry.put("groundingFactIds", part.groundingFactIds());
        breakdown.add(entry);
      }
      row.put("breakdown", breakdown);
      ranking.add(row);
    }
    body.put("ranking", ranking);
    List<Map<String, Object>> excluded = new ArrayList<>();
    for (PlanVoteTally.Excluded item : outcome.excluded()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("planId", item.planId());
      row.put("reason", item.reason());
      row.put("providers", item.providers());
      row.put("groundingFactIds", item.groundingFactIds());
      excluded.add(row);
    }
    body.put("excluded", excluded);
    body.put("notices", outcome.notices());
    List<Map<String, Object>> rejections = new ArrayList<>();
    for (PlanVoteTally.Rejection item : outcome.rejections()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("providerId", item.providerId());
      row.put("factId", item.factId());
      row.put("code", item.code());
      row.put("detail", item.detail());
      rejections.add(row);
    }
    body.put("rejections", rejections);
    body.put("homogeneous", outcome.homogeneous());
    return body;
  }

  private static ResponseEntity<Map<String, Object>> badRequest(String message) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
  }
}
