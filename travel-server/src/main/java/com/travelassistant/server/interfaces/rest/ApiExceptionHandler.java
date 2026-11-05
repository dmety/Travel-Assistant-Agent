package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.agent.InvalidPlanningRequestException;
import com.travelassistant.server.application.attraction.AttractionUnavailableException;
import com.travelassistant.server.application.collaboration.InvalidCommentException;
import com.travelassistant.server.application.decision.CandidateNotFoundException;
import com.travelassistant.server.application.decision.InvalidCandidateException;
import com.travelassistant.server.application.invitation.InvitationConflictException;
import com.travelassistant.server.application.invitation.InvitationNotFoundException;
import com.travelassistant.server.application.itinerary.InvalidItineraryException;
import com.travelassistant.server.application.itinerary.ItineraryArchivedException;
import com.travelassistant.server.application.itinerary.ItineraryForbiddenException;
import com.travelassistant.server.application.itinerary.ItineraryNotFoundException;
import com.travelassistant.server.application.itinerary.MemberNotFoundException;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.application.ticket.InvalidTicketQueryException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常映射：行程领域异常 → 对应 HTTP 状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ItineraryNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(ItineraryNotFoundException e) {
    return ResponseEntity.status(404).body(Map.of("error", "行程不存在", "id", e.id()));
  }

  @ExceptionHandler(MemberNotFoundException.class)
  public ResponseEntity<Map<String, Object>> memberNotFound(MemberNotFoundException e) {
    return ResponseEntity.status(404).body(Map.of(
        "error", "成员不存在",
        "id", e.itineraryId(),
        "username", e.username()));
  }

  @ExceptionHandler(InvitationNotFoundException.class)
  public ResponseEntity<Map<String, Object>> invitationNotFound(InvitationNotFoundException e) {
    return ResponseEntity.status(404).body(Map.of(
        "error", "邀请不存在",
        "invitationId", e.invitationId()));
  }

  /** 邀请已被处理：不静默覆盖已接受/已拒绝/已撤回的决定。 */
  @ExceptionHandler(InvitationConflictException.class)
  public ResponseEntity<Map<String, Object>> invitationConflict(InvitationConflictException e) {
    return ResponseEntity.status(409).body(Map.of(
        "error", "邀请已被处理，不能再次变更",
        "invitationId", e.invitationId(),
        "status", e.status().name()));
  }

  @ExceptionHandler(ItineraryForbiddenException.class)
  public ResponseEntity<Map<String, Object>> forbidden(ItineraryForbiddenException e) {
    return ResponseEntity.status(403).body(Map.of("error", "无权限"));
  }

  @ExceptionHandler(VersionConflictException.class)
  public ResponseEntity<Map<String, Object>> conflict(VersionConflictException e) {
    return ResponseEntity.status(409)
        .header("X-Version", Long.toString(e.currentVersion()))
        .body(Map.of("error", "版本冲突，已被他人修改", "currentVersion", e.currentVersion()));
  }

  /** 业务校验失败：标题为空、费用为负、住宿日期倒置、邀请未注册用户等。 */
  @ExceptionHandler(InvalidItineraryException.class)
  public ResponseEntity<Map<String, Object>> invalid(InvalidItineraryException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  /** 留言内容为空或超长。 */
  @ExceptionHandler(InvalidCommentException.class)
  public ResponseEntity<Map<String, Object>> invalidComment(InvalidCommentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  /** 购票查询参数不合法（既没给景点 id 也没给名称）。 */
  @ExceptionHandler(InvalidTicketQueryException.class)
  public ResponseEntity<Map<String, Object>> invalidTicketQuery(InvalidTicketQueryException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  /**
   * 高德查询不可用：未配置密钥、服务不可达、配额不足或返回错误。
   *
   * <p>统一 503 并带原因。不能退化成 200 + 空结果——那会被理解成「当地没有这个地方」。
   */
  @ExceptionHandler(AmapUnavailableException.class)
  public ResponseEntity<Map<String, Object>> amapUnavailable(AmapUnavailableException e) {
    return ResponseEntity.status(503).body(Map.of(
        "error", "外部信息查询不可用",
        "reason", e.getMessage() == null ? "未知原因" : e.getMessage()));
  }

  /** 规划请求不合法（如未填写目的地）。 */
  @ExceptionHandler(InvalidPlanningRequestException.class)
  public ResponseEntity<Map<String, Object>> invalidPlanning(InvalidPlanningRequestException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  /** 行程已归档：不是权限问题，创建者自己也改不了，因此用 409 而不是 403。 */
  @ExceptionHandler(ItineraryArchivedException.class)
  public ResponseEntity<Map<String, Object>> archived(ItineraryArchivedException e) {
    return ResponseEntity.status(409).body(Map.of(
        "error", e.getMessage(),
        "id", e.itineraryId(),
        "archived", true));
  }

  /** 候选参数不合法：名称为空、重复添加、投票选项缺失、撤回没有的票。 */
  @ExceptionHandler(InvalidCandidateException.class)
  public ResponseEntity<Map<String, Object>> invalidCandidate(InvalidCandidateException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(CandidateNotFoundException.class)
  public ResponseEntity<Map<String, Object>> candidateNotFound(CandidateNotFoundException e) {
    return ResponseEntity.status(404).body(Map.of(
        "error", "候选项不存在",
        "candidateId", e.candidateId()));
  }

  /** 景点知识库不可用（缺失/损坏）：503 并说明原因，不能伪装成"查无结果"。 */
  @ExceptionHandler(AttractionUnavailableException.class)
  public ResponseEntity<Map<String, Object>> attractionUnavailable(AttractionUnavailableException e) {
    return ResponseEntity.status(503).body(Map.of(
        "error", "景点查询已停用",
        "reason", e.getMessage()));
  }

  /** Bean Validation 失败（如 {@code @NotBlank} 的标题）。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(FieldError::getDefaultMessage)
        .orElse("请求参数无效");
    return ResponseEntity.badRequest().body(Map.of("error", message));
  }

  /**
   * 请求体无法解析（JSON 语法错误、把数组写成对象、字段类型不匹配等）。
   *
   * <p>不接管时由 Spring 的默认错误页返回，结构是 {@code timestamp/status/error/path}，
   * 与其它接口的 {@code {"error": "..."}} 不一致 —— 前端得写两套解析。这里统一成同一种形状，
   * 状态码仍是 400。
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "请求体格式不正确，请检查 JSON 是否合法、字段类型是否匹配"));
  }
}
