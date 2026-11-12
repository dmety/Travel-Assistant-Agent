package com.travelassistant.server.application.agent.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排结果 → 对外信封。
 *
 * <p>抽出来的原因：{@code POST /api/agent/invoke} 与对话 Agent 的工具结果都要回这份形状，
 * 两边各写一遍迟早会漂移（前端只认一套字段）。{@code result} 是契约形状的统一响应，
 * 不是内部对象直出；{@code attempts} 里是编排层自己的记录标签，不是准入原因码。
 */
public final class AgentEnvelope {

  private AgentEnvelope() {
  }

  public static Map<String, Object> view(DispatchOutcome outcome, AgentOrchestrator orchestrator) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("intent", outcome.intent());
    body.put("status", outcome.status());
    body.put("providerId", outcome.providerId());
    body.put("result", orchestrator.contractBody(outcome));
    body.put("registryVersion", outcome.registryVersion());
    List<Map<String, Object>> attempts = new ArrayList<>();
    for (DispatchOutcome.Attempt attempt : outcome.attempts()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("providerId", attempt.providerId());
      item.put("status", attempt.status());
      item.put("reason", attempt.reason());
      item.put("codes", attempt.codes());
      attempts.add(item);
    }
    body.put("attempts", attempts);
    return body;
  }
}
