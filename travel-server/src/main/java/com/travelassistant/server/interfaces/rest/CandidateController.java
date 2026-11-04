package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.decision.CandidateService;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.interfaces.rest.dto.ActivityRequest;
import com.travelassistant.server.interfaces.rest.dto.CandidateRequest;
import com.travelassistant.server.interfaces.rest.dto.VoteRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选与投票接口（V3 第 1 节）。
 *
 * <p>除 {@code apply} 外，这些接口都**不带版本号**：候选与选票是讨论数据，
 * 不进行程聚合，改动它们不会推动行程版本号，也不该让正在编辑日程的人撞上 409。
 * 只有 {@code apply} 会真正写入每日安排，因此它要求版本号与 DAILY 编辑权。
 */
@RestController
@RequestMapping("/api/itineraries/{id}/candidates")
public class CandidateController {

  private final CandidateService service;

  public CandidateController(CandidateService service) {
    this.service = service;
  }

  /** 候选看板：含每人每项的票、排序、并列与未投票提示。 */
  @GetMapping
  public CandidateBoard board(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return service.board(id, user.getUsername());
  }

  @PostMapping
  public ResponseEntity<Candidate> add(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestBody CandidateRequest req) {
    Candidate created = service.add(id, user.getUsername(), req.kind(), req.name(), req.city(),
        req.attractionId(), req.note());
    return ResponseEntity.created(
            URI.create("/api/itineraries/" + id + "/candidates/" + created.id()))
        .body(created);
  }

  /** 删除候选：提出者本人或创建者。 */
  @DeleteMapping("/{candidateId}")
  public ResponseEntity<Map<String, Object>> remove(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @PathVariable String candidateId) {
    service.remove(id, user.getUsername(), candidateId);
    return ResponseEntity.ok(Map.of("deleted", true, "candidateId", candidateId));
  }

  /** 投票或改票，返回更新后的看板。 */
  @PostMapping("/{candidateId}/votes")
  public CandidateBoard vote(@AuthenticationPrincipal UserDetails user, @PathVariable String id,
      @PathVariable String candidateId, @RequestBody VoteRequest req) {
    return service.vote(id, user.getUsername(), candidateId, req.choice());
  }

  /** 撤回自己的票。 */
  @DeleteMapping("/{candidateId}/votes")
  public CandidateBoard withdraw(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @PathVariable String candidateId) {
    return service.withdrawVote(id, user.getUsername(), candidateId);
  }

  /**
   * 确认候选：把票选出来的地点真正写进某一天的安排。
   *
   * <p>这是整个投票流程唯一的写入口——投票结果本身不会自动改动行程。
   */
  @PostMapping("/{candidateId}/apply")
  public ResponseEntity<Itinerary> apply(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @PathVariable String candidateId,
      @RequestParam long version, @RequestBody ActivityRequest req) {
    return ResponseEntity.ok(service.apply(id, user.getUsername(), candidateId, req.date(),
        version));
  }
}
