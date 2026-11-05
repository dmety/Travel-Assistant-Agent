package com.travelassistant.server.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.application.invitation.InvitationSummary;
import com.travelassistant.server.domain.invitation.Invitation;
import com.travelassistant.server.domain.itinerary.Section;
import java.time.Instant;
import java.util.Set;

/**
 * 邀请视图。
 *
 * <p>{@code status} 用字符串而不是枚举序号：客户端与文档都需要可读状态，
 * 枚举序号在增删常量后会静默错位。
 */
public record InvitationView(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("itineraryTitle") String itineraryTitle,
    @JsonProperty("inviter") String inviter,
    @JsonProperty("invitee") String invitee,
    @JsonProperty("editSections") Set<Section> editSections,
    @JsonProperty("status") String status,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("respondedAt") Instant respondedAt) {

  public static InvitationView from(InvitationSummary summary) {
    return from(summary.invitation(), summary.itineraryTitle());
  }

  public static InvitationView from(Invitation inv, String itineraryTitle) {
    return new InvitationView(inv.id(), inv.itineraryId(), itineraryTitle, inv.inviter(),
        inv.invitee(), inv.editSections(), inv.status().name(), inv.createdAt(),
        inv.respondedAt());
  }
}
