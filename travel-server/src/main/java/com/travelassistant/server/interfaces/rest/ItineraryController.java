package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.budget.BudgetService;
import com.travelassistant.server.application.export.ExportService;
import com.travelassistant.server.application.invitation.InvitationService;
import com.travelassistant.server.application.itinerary.ItineraryForbiddenException;
import com.travelassistant.server.application.itinerary.ItineraryNotFoundException;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.BudgetSummary;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Lodging;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.domain.itinerary.Transport;
import com.travelassistant.server.interfaces.rest.dto.ActivityRequest;
import com.travelassistant.server.interfaces.rest.dto.CreateItineraryRequest;
import com.travelassistant.server.interfaces.rest.dto.MemberRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
  private final BudgetService budgetService;
  private final InvitationService invitationService;
  private final ExportService exportService;

  public ItineraryController(ItineraryService service, BudgetService budgetService,
      InvitationService invitationService, ExportService exportService) {
    this.service = service;
    this.budgetService = budgetService;
    this.invitationService = invitationService;
    this.exportService = exportService;
  }

  private String me(UserDetails user) {
    return user.getUsername();
  }

  // ---- 行程级 ----

  @PostMapping
  public ResponseEntity<Itinerary> create(@AuthenticationPrincipal UserDetails user,
      @Valid @RequestBody CreateItineraryRequest req) {
    Itinerary it = service.create(me(user), req.title(), req.plan());
    return ResponseEntity.created(URI.create("/api/itineraries/" + it.id())).body(it);
  }

  /**
   * 列出我的行程。
   *
   * @param archived 省略时返回全部（归档与未归档）；{@code true} 只看归档；
   *                 {@code false} 只看进行中。默认不过滤是为了兼容老客户端——
   *                 升级后不该出现「我的行程凭空少了一半」。
   */
  @GetMapping
  public List<Itinerary> list(@AuthenticationPrincipal UserDetails user,
      @RequestParam(required = false) Boolean archived) {
    return service.listForUser(me(user), archived);
  }

  @GetMapping("/{id}")
  public Itinerary get(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.get(id, me(user));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    service.delete(id, me(user));
    // 行程没了，指向它的待处理邀请也必须失效，否则受邀者会看到一条永远接受不了的邀请
    invitationService.cancelPendingOf(id);
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

  // ---- 预算与时间检查（只读，不改变版本号） ----

  @GetMapping("/{id}/budget")
  public ResponseEntity<BudgetSummary> budget(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version))
        .body(budgetService.summarize(id, me(user)));
  }

  @GetMapping("/{id}/checks")
  public ResponseEntity<ItineraryChecks> checks(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return ResponseEntity.ok(budgetService.check(id, me(user)));
  }

  /**
   * 预算分析（V3 第 2 节）：上限、预计、实际、差额与人均。
   *
   * @param travelers 参与分摊的人数；省略时按「创建者 + 成员」计算
   */
  @GetMapping("/{id}/budget/analysis")
  public ResponseEntity<BudgetAnalysis> analysis(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam(required = false) Integer travelers) {
    long version = service.get(id, me(user)).version();
    return ResponseEntity.ok().header("X-Version", Long.toString(version))
        .body(budgetService.analyze(id, me(user), travelers));
  }

  /**
   * 追加一项每日安排（V3「确认候选」与手工登记共用）。
   *
   * <p>与 {@code PUT /daily} 的区别：整份 PUT 适合表单编辑，这个方法只加一条，
   * 客户端不需要先把整份日程读出来再整体回写——那是最容易覆盖别人改动的方式。
   */
  @PostMapping("/{id}/activities")
  public ResponseEntity<Itinerary> appendActivity(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestParam long version, @RequestBody ActivityRequest req) {
    return ResponseEntity.ok(service.appendActivity(id, me(user), req.date(), req.title(),
        req.location(), req.note(), version));
  }

  /**
   * 导出文本摘要（V3 第 4 节）。
   *
   * <p>返回 {@code text/plain} 并带下载文件名。服务端不写用户的磁盘——
   * 导出件的落点由调用方决定。
   */
  @GetMapping(value = "/{id}/export", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<String> export(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    String text = exportService.toText(id, me(user));
    String name = exportService.fileName(id, me(user));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build()
                .toString())
        .body(text);
  }

  // ---- 归档（V3 第 4 节，仅 owner） ----

  /**
   * 归档：转为只读，数据全部保留。
   *
   * <p>不需要版本号：归档是一个开关，不是对方案内容的编辑；重复归档幂等。
   */
  @PostMapping("/{id}/archive")
  public ResponseEntity<Itinerary> archive(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return ResponseEntity.ok(service.archive(id, me(user)));
  }

  @PostMapping("/{id}/unarchive")
  public ResponseEntity<Itinerary> unarchive(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return ResponseEntity.ok(service.unarchive(id, me(user)));
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

  /**
   * 成员主动退出（需求 §4）。
   *
   * <p>不需要版本号：退出是成员对自己身份的操作，不属于板块内容的并发编辑；
   * 若要求版本号，成员得先读到最新版本才能退出，反而拖住流程。
   */
  @PostMapping("/{id}/leave")
  public ResponseEntity<Void> leave(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    service.leave(id, me(user));
    return ResponseEntity.noContent().build();
  }
}
