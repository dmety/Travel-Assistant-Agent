package com.travelassistant.server.application.agent.orchestration;

import com.travelassistant.server.application.agent.spi.AgentResult;
import java.util.List;

/**
 * 一次编排的结果：最终响应、实际生效的 Provider、以及整条链的尝试记录。
 *
 * <p>{@link Attempt#reason()} 是编排层自己的记录标签（NOT_FOUND / CONTRACT_VIOLATION /
 * EXCEPTION / REFUSED / OK），不是《Agent 接入规范》的 {@code E_*} 准入原因码，
 * 不进 AdmissionReport、不计违规、不触发熔断。这与三条 {@code E_VOTE_*} 的边界一致。
 */
public record DispatchOutcome(
    String intent,
    String status,
    String providerId,
    AgentResult result,
    List<Attempt> attempts,
    String registryVersion) {

  public record Attempt(String providerId, String status, String reason, List<String> codes) {
  }
}
