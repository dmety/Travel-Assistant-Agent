package com.travelassistant.server.domain.invitation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.domain.itinerary.Section;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 邀请：创建者按账号发出，受邀者登录后接受或拒绝（需求 §4）。
 *
 * <p>为什么不把「待接受」直接写成 {@code Member}：成员意味着已获得权限，而待处理邀请
 * 只是一项请求。混在一起会让「谁真正能编辑」无法从数据里判断，也无法记录拒绝结果。
 *
 * <p>邀请独立落盘，不进入行程聚合，因此**发起邀请不会改变行程版本号**——
 * 邀请是行程之外的一次沟通，不该让正在编辑的人被迫处理版本冲突。
 */
public record Invitation(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("inviter") String inviter,
    @JsonProperty("invitee") String invitee,
    @JsonProperty("editSections") Set<Section> editSections,
    @JsonProperty("status") InvitationStatus status,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("respondedAt") Instant respondedAt) {

  @JsonCreator
  public Invitation {
    editSections = (editSections == null) ? Set.of() : Set.copyOf(editSections);
    status = (status == null) ? InvitationStatus.PENDING : status;
  }

  public static Invitation create(String itineraryId, String inviter, String invitee,
      Set<Section> editSections) {
    return new Invitation(UUID.randomUUID().toString(), itineraryId, inviter, invitee.trim(),
        editSections, InvitationStatus.PENDING, Instant.now(), null);
  }

  public boolean isPending() {
    return status == InvitationStatus.PENDING;
  }

  /** 重新发出邀请时更新授权的板块，状态与创建时间保持不变。 */
  public Invitation withSections(Set<Section> sections) {
    return new Invitation(id, itineraryId, inviter, invitee,
        (sections == null) ? Set.of() : sections, status, createdAt, respondedAt);
  }

  public Invitation accept() {
    return transition(InvitationStatus.ACCEPTED);
  }

  public Invitation reject() {
    return transition(InvitationStatus.REJECTED);
  }

  public Invitation cancel() {
    return transition(InvitationStatus.CANCELLED);
  }

  private Invitation transition(InvitationStatus target) {
    return new Invitation(id, itineraryId, inviter, invitee, editSections, target,
        createdAt, Instant.now());
  }
}
