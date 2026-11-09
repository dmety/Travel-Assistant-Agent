package com.travelassistant.server.application.agent.spi;

import java.util.List;

/** 一次路由解析结果：primary 加 fallback 链，均只包含 ACTIVE Provider。 */
public record Resolution(String intent, String primary, List<String> fallback) {
}
