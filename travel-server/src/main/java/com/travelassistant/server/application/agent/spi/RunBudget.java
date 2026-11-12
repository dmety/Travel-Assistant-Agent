package com.travelassistant.server.application.agent.spi;

import java.time.Instant;

/** 单次 run 的硬上限。deadlineAt 对应 T-04，超时由服务端切断。 */
public record RunBudget(Instant deadlineAt, int maxModelCalls, int maxToolCalls) {
}
