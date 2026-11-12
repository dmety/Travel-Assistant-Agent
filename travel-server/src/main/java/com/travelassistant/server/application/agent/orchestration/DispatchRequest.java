package com.travelassistant.server.application.agent.orchestration;

import java.util.Map;

/**
 * 编排层入参。只有 intent 与开放对象 context / hints，不放凭据、不放用户身份原文。
 *
 * <p>identity 不进这一层：{@code AgentContext.userIdHash} 保持 null，避免把 hash 或用户名
 * 当作真实用户身份传给 Provider（决策类能力需要显式 user 与 itineraryId，见
 * {@code BuiltinProviderConfiguration}）。
 */
public record DispatchRequest(
    String intent,
    Map<String, Object> context,
    Map<String, String> hints) {
}
