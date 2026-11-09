package com.travelassistant.server.application.agent.spi;

/** 单次调用用量。elapsedMs 为 T-07 必填非负整数。 */
public record Usage(long elapsedMs, int modelCalls) {
}
