package com.travelassistant.server.application.invitation;

import com.travelassistant.server.domain.invitation.Invitation;

/**
 * 邀请 + 行程标题。
 *
 * <p>标题不冗余存进邀请记录：行程随时可改名，存快照会展示过期标题；
 * 查询时从行程仓库实时取，行程已被删除的邀请直接不展示（已无法接受）。
 */
public record InvitationSummary(Invitation invitation, String itineraryTitle) {
}
