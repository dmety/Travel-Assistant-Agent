package com.travelassistant.server.application.invitation;

/** 邀请不存在（含已被清理）。映射为 HTTP 404。 */
public class InvitationNotFoundException extends RuntimeException {
  private final String invitationId;

  public InvitationNotFoundException(String invitationId) {
    super("邀请不存在：" + invitationId);
    this.invitationId = invitationId;
  }

  public String invitationId() {
    return invitationId;
  }
}
