package com.travelassistant.server.application.agent.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 传给 Provider 的全部内容。hints 是契约预留扩展位，避免为小需求改契约。 */
public record AgentContext(
    String runId,
    String userIdHash,
    String intent,
    Map<String, Object> context,
    List<Fact> facts,
    Map<String, String> hints,
    Instant deadlineAt) {
}
