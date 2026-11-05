package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.collaboration.CollaborationService;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.collaboration.Comment;
import com.travelassistant.server.interfaces.rest.dto.CommentRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协作接口（V2 第 4 节）：留言与修改记录。
 *
 * <p>两条都是旁路数据，**不改变行程版本号**，因此不带 {@code version} 参数，
 * 也不会因为有人留言就让正在编辑的人撞上 409。
 */
@RestController
@RequestMapping("/api/itineraries/{id}")
public class CollaborationController {

  private final CollaborationService service;

  public CollaborationController(CollaborationService service) {
    this.service = service;
  }

  @GetMapping("/comments")
  public List<Comment> comments(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return service.comments(id, user.getUsername());
  }

  @PostMapping("/comments")
  public ResponseEntity<Comment> comment(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestBody CommentRequest req) {
    Comment created = service.comment(id, user.getUsername(), req.content());
    return ResponseEntity.created(URI.create("/api/itineraries/" + id + "/comments"))
        .body(created);
  }

  /** 修改记录，默认最近 50 条，{@code limit} 上限 200。 */
  @GetMapping("/changelog")
  public List<ChangeEntry> changelog(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
    return service.changes(id, user.getUsername(), Math.min(Math.max(limit, 1), 200));
  }
}
