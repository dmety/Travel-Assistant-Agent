package com.travelassistant.server.interfaces.rest.dto;

import com.travelassistant.server.domain.decision.VoteChoice;

/**
 * 投票请求体（V3 第 1 节）。
 *
 * <p>只有选项，没有身份——投票人一律取自会话。允许客户端自称是谁的投票，
 * 等于让任何人替别人表态。
 */
public record VoteRequest(VoteChoice choice) {
}
