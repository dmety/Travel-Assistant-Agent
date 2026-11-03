package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.itinerary.ItineraryForbiddenException;
import com.travelassistant.server.application.itinerary.ItineraryNotFoundException;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常映射：行程领域异常 → 对应 HTTP 状态码。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ItineraryNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(ItineraryNotFoundException e) {
    return ResponseEntity.status(404).body(Map.of("error", "行程不存在", "id", e.id()));
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
}
