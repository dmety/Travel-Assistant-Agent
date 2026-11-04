package com.travelassistant.server.interfaces.rest.dto;

import com.travelassistant.server.domain.decision.CandidateKind;

/**
 * 添加候选项的请求体（V3 第 1 节）。
 *
 * @param kind         类型，省略时按景点处理
 * @param name         名称，必填
 * @param city         所在城市，可为空
 * @param attractionId 关联的景点 ID，可为空（手写地点不在知识库里很正常）
 * @param note         备注，可为空
 */
public record CandidateRequest(CandidateKind kind, String name, String city, String attractionId,
    String note) {
}
