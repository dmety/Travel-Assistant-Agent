package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.itinerary.ItineraryForbiddenException;
import com.travelassistant.server.application.itinerary.ItineraryNotFoundException;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Lodging;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.domain.itinerary.Transport;
import com.travelassistant.server.interfaces.rest.dto.CreateItineraryRequest;
import com.travelassistant.server.interfaces.rest.dto.MemberRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 行程管理接口：归属/成员权限与乐观锁由 {@link ItineraryService} 保证。 */
@RestController
@RequestMapping("/api/itineraries")
public class ItineraryController {
  private final ItineraryService service;

  public ItineraryController(ItineraryService service) {
    this.service = service;
  }

  private String me(UserDetails user) {
    return user.getUsername();
  }

  // ---- 行程级 ----

  @PostMapping
  public ResponseEntity<Itinerary> create(@AuthenticationPrincipal UserDetails user,
      @RequestBody CreateItineraryRequest req) {
    Itinerary it = service.create(me(user), req.title(), req.plan());
    return ResponseEntity.created(URI.create("/api/itineraries/" + it.id())).body(it);
  }

  @GetMapping
  public List<Itinerary> list(@AuthenticationPrincipal UserDetails user) {
    return service.listForUser(me(user));
  }

  @GetMapping("/{id}")
  public Itinerary get(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.get(id, me(user));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    service.delete(id, me(user));
    return ResponseEntity.ok(Map.of("deleted", true));
  }

  // ---- 方案 PLAN ----

  @GetMapping("/{id}/plan")
  public ResponseEntity<Plan> getPlan(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version)).body(service.getPlan(id, me(user)));
  }

  @PutMapping("/{id}/plan")
  public ResponseEntity<Itinerary> updatePlan(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody Plan plan) {
    return ResponseEntity.ok(service.updatePlan(id, me(user), plan, version));
  }

  // ---- 每日安排 DAILY ----

  @GetMapping("/{id}/daily")
  public ResponseEntity<List<DailySchedule>> getDaily(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version)).body(service.getDaily(id, me(user)));
  }

  @PutMapping("/{id}/daily")
  public ResponseEntity<Itinerary> updateDaily(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody List<DailySchedule> daily) {
    return ResponseEntity.ok(service.updateDaily(id, me(user), daily, version));
  }

  // ---- 交通 TRANSPORT ----

  @GetMapping("/{id}/transports")
  public ResponseEntity<List<Transport>> getTransports(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version)).body(service.getTransports(id, me(user)));
  }

  @PutMapping("/{id}/transports")
  public ResponseEntity<Itinerary> updateTransports(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody List<Transport> transports) {
    return ResponseEntity.ok(service.updateTransports(id, me(user), transports, version));
  }

  // ---- 住宿 LODGING ----

  @GetMapping("/{id}/lodgings")
  public ResponseEntity<List<Lodging>> getLodgings(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version)).body(service.getLodgings(id, me(user)));
  }

  @PutMapping("/{id}/lodgings")
  public ResponseEntity<Itinerary> updateLodgings(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody List<Lodging> lodgings) {
    return ResponseEntity.ok(service.updateLodgings(id, me(user), lodgings, version));
  }

  // ---- 费用 EXPENSE ----

  @GetMapping("/{id}/expenses")
  public ResponseEntity<List<Expense>> getExpenses(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version)).body(service.getExpenses(id, me(user)));
  }

  @PutMapping("/{id}/expenses")
  public ResponseEntity<Itinerary> updateExpenses(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody List<Expense> expenses) {
    return ResponseEntity.ok(service.updateExpenses(id, me(user), expenses, version));
  }

  // ---- 成员管理（仅 owner） ----

  @GetMapping("/{id}/members")
  public List<Member> getMembers(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.getMembers(id, me(user));
  }

  @PostMapping("/{id}/members")
  public ResponseEntity<Itinerary> addMember(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody MemberRequest req) {
    return ResponseEntity.ok(service.addMember(id, me(user), req.username(), req.editSections(), version));
  }

  @PutMapping("/{id}/members/{username}")
  public ResponseEntity<Itinerary> updateMember(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @PathVariable String username, @RequestParam long version,
      @RequestBody MemberRequest req) {
    return ResponseEntity.ok(service.updateMember(id, me(user), username, req.editSections(), version));
  }

  @DeleteMapping("/{id}/members/{username}")
  public ResponseEntity<Itinerary> removeMember(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @PathVariable String username, @RequestParam long version) {
    return ResponseEntity.ok(service.removeMember(id, me(user), username, version));
  }
}
