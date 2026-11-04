package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.attraction.AttractionQueryResult;
import com.travelassistant.server.application.attraction.AttractionService;
import com.travelassistant.server.interfaces.rest.dto.AttractionQueryResponse;
import com.travelassistant.server.interfaces.rest.dto.AttractionView;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 景点查询接口（只读知识库）。
 *
 * <ul>
 *   <li>{@code GET /api/attractions?city=杭州&limit=20}：按城市/省份查询</li>
 *   <li>{@code GET /api/attractions?name=西湖&limit=20}：按名称查询</li>
 *   <li>{@code GET /api/attractions/{id}}：按 ID 查详情</li>
 *   <li>{@code GET /api/attractions/status}：知识库加载状态与不可用原因</li>
 * </ul>
 *
 * <p>库不可用时查询接口返回 503 并说明原因，而不是返回空列表——
 * 否则调用方会误判为"知识库里确实没有这个景点"。
 */
@RestController
@RequestMapping("/api/attractions")
public class AttractionController {

  private final AttractionService service;

  public AttractionController(AttractionService service) {
    this.service = service;
  }

  /** 按城市或名称查询；两者都为空返回 400。city 优先于 name。 */
  @GetMapping
  public ResponseEntity<Object> query(
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) Integer limit) {

    boolean hasCity = city != null && !city.isBlank();
    boolean hasName = name != null && !name.isBlank();
    if (!hasCity && !hasName) {
      return ResponseEntity.badRequest().body(Map.of(
          "error", "请提供查询参数：city（城市/省份）或 name（景点名称）"));
    }

    AttractionQueryResult result = hasCity
        ? service.byCity(city, limit)
        : service.byName(name, limit);
    return ResponseEntity.ok(AttractionQueryResponse.from(result));
  }

  /** 按 ID 查详情，找不到返回 404。 */
  @GetMapping("/{id}")
  public ResponseEntity<Object> detail(@PathVariable String id) {
    return service.byId(id)
        .<ResponseEntity<Object>>map(a -> ResponseEntity.ok(AttractionView.from(a)))
        .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
            "error", "景点不存在", "id", id)));
  }

  /** 知识库状态；不可用时给出原因，便于人工排查。 */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status() {
    AttractionStore.Status s = service.status();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("available", s.available());
    if (s.available()) {
      body.put("count", s.count());
      body.put("cityCount", s.cityCount());
      body.put("loadedAt", s.loadedAt());
    } else {
      body.put("reason", s.reason());
    }
    body.put("path", s.path());
    return ResponseEntity.ok(body);
  }
}
