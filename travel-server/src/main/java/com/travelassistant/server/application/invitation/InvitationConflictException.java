package com.travelassistant.server.application.invitation;

import com.travelassistant.server.domain.invitation.InvitationStatus;

/**
 * 邀请已被处理（已接受/已拒绝/已撤回），不能再次变更。
 *
 * <p>映射为 HTTP 409 而不是静默成功：重复点击「接受」不应把已拒绝的邀请改成接受，
 * 也不应覆盖创建者已撤回的决定。
 */
public class InvitationConflictException extends RuntimeException {
  private final String invitationId;
  private final InvitationStatus status;

  public InvitationConflictException(String invitationId, InvitationStatus status) {
    super("邀请已被处理，当前状态=" + status);
    this.invitationId = invitationId;
    this.status = status;
  }

  public String invitationId() {
    return invitationId;
  }

  public InvitationStatus status() {
    return status;
  }
}
