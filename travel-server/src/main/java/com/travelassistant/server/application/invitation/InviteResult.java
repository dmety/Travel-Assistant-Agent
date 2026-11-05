package com.travelassistant.server.application.invitation;

import com.travelassistant.server.domain.invitation.Invitation;

/**
 * 发起邀请的结果。
 *
 * <p>{@code created=false} 表示命中了同一行程同一受邀人的待处理邀请，此次调用只是更新了
 * 授权板块并重新发出——接口据此返回 200 而非 201，让调用方知道没有新增记录。
 */
public record InviteResult(Invitation invitation, String itineraryTitle, boolean created) {
}
