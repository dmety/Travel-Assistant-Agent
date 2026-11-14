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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一异常映射：行程领域异常 → 对应 HTTP 状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final ObjectMapper json;

  public ApiExceptionHandler(ObjectMapper json) {
    this.json = json;
  }

  /**
   * 直接把错误 JSON 写进原始响应，绕开消息转换器。
   *
   * <p>{@code /api/itineraries/{id}/export} 声明了 {@code produces=text/plain}；
   * 若这里返回 {@code ResponseEntity<Map>}，异常解析时 Spring 仍按原 handler 的
   * produces 约束协商，JSON 转换器写不出 text/plain —— 越权本该 403，实测却变成
   * 500 空响应并打满堆栈。直接写响应则任何 Accept 都稳定返回 403/404。
   */
  private void writeError(HttpServletResponse response, int status,
      Map<String, Object> body) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(json.writeValueAsString(body));
  }

  @ExceptionHandler(ItineraryNotFoundException.class)
  public void notFound(ItineraryNotFoundException e, HttpServletResponse response)
      throws IOException {
    writeError(response, 404, Map.of("error", "行程不存在", "id", e.id()));
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
  public void forbidden(ItineraryForbiddenException e, HttpServletResponse response)
      throws IOException {
    writeError(response, 403, Map.of("error", "无权限"));
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

  /**
   * 缺少必填的查询参数（如写操作漏了 {@code version}）。
   *
   * <p>不接管时 Spring 会返回默认错误页（{@code timestamp/status/error/path}），
   * 与其它接口的 {@code {"error": "..."}} 不是同一种形状。状态码本来就该是 400，
   * 这里只是把文案统一成能直接展示给用户的一句话。
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> missingParameter(
      MissingServletRequestParameterException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "缺少必需参数：" + e.getParameterName(),
        "parameter", e.getParameterName()));
  }

  /**
   * 参数类型不符（如 {@code version=abc}）。
   *
   * <p>与缺参分开处理：一个是「没给」，一个是「给错了」，前端提示的补救动作不一样。
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> parameterTypeMismatch(
      MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "参数「" + e.getName() + "」的值 " + e.getValue() + " 格式不正确，请检查后重试",
        "parameter", e.getName()));
  }

  /**
   * 兜底：上面没有匹配到的异常统一在这里收口，分两类处理。
   *
   * <p>Spring 框架异常（{@link ErrorResponse}，如 404 找不到静态资源、405 方法不支持）保留
   * 原本的状态码，只把响应体统一成 {@code {"error": ...}}。因为
   * {@code ExceptionHandlerExceptionResolver} 先于 {@code DefaultHandlerExceptionResolver} 执行，
   * 这个 {@code Exception.class} 兜底会抢在框架默认处理之前命中；若不保留状态码，404/405 会被
   * 一律吞成 500，前端按状态码做的判断全部错位。
   *
   * <p>其余未预期异常回 500 + 固定文案，堆栈只写服务端日志，绝不回给客户端。
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    if (e instanceof ErrorResponse framework) {
      HttpStatusCode status = framework.getStatusCode();
      log.debug("框架异常 [{}]：{}", status.value(), e.getMessage());
      return ResponseEntity.status(status).body(Map.of("error", frameworkMessage(status)));
    }
    log.error("未处理的服务器异常", e);
    return ResponseEntity.status(500).body(Map.of("error", "服务器内部错误，请稍后重试"));
  }

  private static String frameworkMessage(HttpStatusCode status) {
    return switch (status.value()) {
      case 404 -> "请求的资源不存在";
      case 405 -> "请求方法不被支持";
      case 406 -> "无法返回客户端可接受的内容类型";
      case 415 -> "不支持的请求内容类型";
      default -> status.is4xxClientError() ? "请求无法被处理" : "服务器内部错误，请稍后重试";
    };
  }
}
