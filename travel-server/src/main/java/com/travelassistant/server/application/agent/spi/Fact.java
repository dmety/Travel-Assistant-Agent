package com.travelassistant.server.application.agent.spi;

import java.util.Map;

/** 一条带证据的事实。{@code type} 取值见规范源 enums.factType。 */
public record Fact(String factId, String type, Map<String, Object> value, Evidence evidence) {
}
