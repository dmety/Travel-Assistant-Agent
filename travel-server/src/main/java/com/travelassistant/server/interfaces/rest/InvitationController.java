package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.invitation.InvitationService;
import com.travelassistant.server.application.invitation.InvitationSummary;
import com.travelassistant.server.application.invitation.InviteResult;
import com.travelassistant.server.domain.invitation.Invitation;
import com.travelassistant.server.interfaces.rest.dto.InvitationView;
import com.travelassistant.server.interfaces.rest.dto.InviteRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邀请接口（V1 需求 §4）：创建者按账号邀请，受邀者登录后接受或拒绝。
 *
 * <p>与 {@code POST /api/itineraries/{id}/members}（创建者直接授权）并存：
 * 直加接口保留给管理场景与既有客户端，主流程走邀请，受邀者有机会拒绝。
 */
@RestController
public class InvitationController {
  private final InvitationService service;

  public InvitationController(InvitationService service) {
    this.service = service;
  }

  private String me(UserDetails user) {
    return user.getUsername();
  }

  // ---- 创建者：发起 / 查看 / 撤回 ----

  @PostMapping("/api/itineraries/{id}/invitations")
  public ResponseEntity<InvitationView> invite(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestBody InviteRequest req) {
    InviteResult result = service.invite(id, me(user), req.username(), req.editSections());
    InvitationView view = InvitationView.from(result.invitation(), result.itineraryTitle());
    if (!result.created()) {
      // 命中待处理邀请：未新增记录，用 200 区分于首次发出的 201
      return ResponseEntity.ok(view);
    }
    return ResponseEntity
        .created(URI.create("/api/invitations/" + result.invitation().id()))
        .body(view);
  }

  @GetMapping("/api/itineraries/{id}/invitations")
  public List<InvitationView> listOfItinerary(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return service.listForItinerary(id, me(user)).stream().map(InvitationView::from).toList();
  }

  @DeleteMapping("/api/invitations/{invitationId}")
  public InvitationView cancel(@AuthenticationPrincipal UserDetails user,
      @PathVariable String invitationId) {
    return InvitationView.from(service.cancel(invitationId, me(user)));
  }

  // ---- 受邀者：我的待处理邀请 / 接受 / 拒绝 ----

  @GetMapping("/api/invitations")
  public List<InvitationView> myPending(@AuthenticationPrincipal UserDetails user) {
    return service.pendingForInvitee(me(user)).stream()
        .map(InvitationView::from)
        .toList();
  }

  @PostMapping("/api/invitations/{invitationId}/accept")
  public InvitationView accept(@AuthenticationPrincipal UserDetails user,
      @PathVariable String invitationId) {
    return InvitationView.from(service.accept(invitationId, me(user)));
  }

  @PostMapping("/api/invitations/{invitationId}/reject")
  public InvitationView reject(@AuthenticationPrincipal UserDetails user,
      @PathVariable String invitationId) {
    return InvitationView.from(service.reject(invitationId, me(user)));
  }
}
