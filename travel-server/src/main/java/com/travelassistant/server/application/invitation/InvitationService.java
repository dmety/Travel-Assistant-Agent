package com.travelassistant.server.application.invitation;

import com.travelassistant.server.application.itinerary.InvalidItineraryException;
import com.travelassistant.server.application.itinerary.ItineraryForbiddenException;
import com.travelassistant.server.application.itinerary.ItineraryNotFoundException;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.invitation.Invitation;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import com.travelassistant.server.infrastructure.persistence.InvitationStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 邀请用例（V1 需求 §4）：创建者按账号邀请，受邀者登录后接受或拒绝。
 *
 * <p>状态机只有一条正向路径：{@code PENDING → ACCEPTED / REJECTED / CANCELLED}。
 * 任何非 PENDING 的邀请再次处理都返回冲突，不做静默覆盖。
 *
 * <p>接受邀请会写入行程成员，因此这一步最终落到 {@link ItineraryService#joinAsMember}，
 * 复用其按行程 id 分片的写锁；本类自身对邀请状态变更加对象级互斥，
 * 避免同一邀请被并发接受两次而写出两份状态。
 */
@Service
public class InvitationService {
  private final InvitationStore store;
  private final ItineraryStore itineraries;
  private final AccountStore accounts;
  private final ItineraryService itineraryService;

  /** 邀请状态变更的互斥锁：邀请操作频率低，单锁足够，不必按 id 分片。 */
  private final Object lock = new Object();

  public InvitationService(InvitationStore store, ItineraryStore itineraries,
      AccountStore accounts, ItineraryService itineraryService) {
    this.store = store;
    this.itineraries = itineraries;
    this.accounts = accounts;
    this.itineraryService = itineraryService;
  }

  // ---- 发起 ----

  /**
   * 创建者按账号邀请。同一行程同一受邀人重复邀请时**不新增记录**，只更新授权板块后重新发出，
   * 避免重复点击堆出多条待处理邀请；调用方可用返回的 {@code created} 区分。
   */
  public InviteResult invite(String itineraryId, String inviter, String invitee,
      Set<Section> editSections) {
    synchronized (lock) {
      Itinerary it = requireItinerary(itineraryId);
      if (!it.owner().equals(inviter)) {
        throw new ItineraryForbiddenException();
      }
      String name = invitee == null ? "" : invitee.trim();
      if (name.isEmpty()) {
        throw new InvalidItineraryException("受邀用户名不能为空");
      }
      if (name.equals(it.owner())) {
        throw new InvalidItineraryException("不能邀请自己：行程创建者已是成员");
      }
      if (!accounts.exists(name)) {
        throw new InvalidItineraryException("用户不存在：" + name + "（只能邀请已注册用户）");
      }
      if (isMember(it, name)) {
        throw new InvalidItineraryException("该用户已是行程成员：" + name);
      }
      Optional<Invitation> pending = store.findByItinerary(itineraryId).stream()
          .filter(i -> i.invitee().equals(name))
          .filter(Invitation::isPending)
          .findFirst();
      if (pending.isPresent()) {
        return new InviteResult(store.save(pending.get().withSections(editSections)),
            it.title(), false);
      }
      return new InviteResult(
          store.save(Invitation.create(itineraryId, inviter, name, editSections)), it.title(), true);
    }
  }

  // ---- 查询 ----

  /** 我收到的待处理邀请（最新在前）。行程已删除的邀请不再展示。 */
  public List<InvitationSummary> pendingForInvitee(String user) {
    return store.findAll().stream()
        .filter(Invitation::isPending)
        .filter(i -> i.invitee().equals(user))
        .map(this::toSummary)
        .flatMap(Optional::stream)
        .sorted(Comparator.comparing(s -> s.invitation().createdAt(), Comparator.reverseOrder()))
        .toList();
  }

  /** 某行程的邀请记录（仅创建者可见，含已处理的）。行程已删除时返回空列表。 */
  public List<InvitationSummary> listForItinerary(String itineraryId, String user) {
    Itinerary it = requireItinerary(itineraryId);
    if (!it.owner().equals(user)) {
      throw new ItineraryForbiddenException();
    }
    return store.findByItinerary(itineraryId).stream()
        .map(i -> new InvitationSummary(i, it.title()))
        .sorted(Comparator.comparing(s -> s.invitation().createdAt(), Comparator.reverseOrder()))
        .toList();
  }

  // ---- 受邀者处理 ----

  /** 接受邀请：成为成员并获得邀请中指定的板块编辑权限。 */
  public InvitationSummary accept(String invitationId, String user) {
    synchronized (lock) {
      Invitation inv = requirePending(invitationId, user);
      Itinerary it = requireItinerary(inv.itineraryId());
      // 先加成员再落状态：万一写行程失败，邀请仍是 PENDING，受邀者可重试
      itineraryService.joinAsMember(it.id(), user, inv.editSections());
      return new InvitationSummary(store.save(inv.accept()), it.title());
    }
  }

  /** 拒绝邀请：不产生任何成员关系，邀请置为已拒绝。 */
  public InvitationSummary reject(String invitationId, String user) {
    synchronized (lock) {
      Invitation inv = requirePending(invitationId, user);
      return new InvitationSummary(store.save(inv.reject()), titleOf(inv.itineraryId()));
    }
  }

  // ---- 创建者撤回 ----

  /** 撤回邀请：只有发起该邀请的创建者可撤回，且只能撤回待处理的。 */
  public InvitationSummary cancel(String invitationId, String user) {
    synchronized (lock) {
      Invitation inv = store.findById(invitationId)
          .orElseThrow(() -> new InvitationNotFoundException(invitationId));
      if (!inv.inviter().equals(user)) {
        throw new ItineraryForbiddenException();
      }
      if (!inv.isPending()) {
        throw new InvitationConflictException(inv.id(), inv.status());
      }
      return new InvitationSummary(store.save(inv.cancel()), titleOf(inv.itineraryId()));
    }
  }

  /** 行程被删除时，把该行程所有待处理邀请置为已取消，避免留下永远无法接受的悬空邀请。 */
  public void cancelPendingOf(String itineraryId) {
    synchronized (lock) {
      List<Invitation> cancelled = store.findByItinerary(itineraryId).stream()
          .filter(Invitation::isPending)
          .map(Invitation::cancel)
          .toList();
      store.saveAll(cancelled);
    }
  }

  // ---- 辅助 ----

  private Invitation requirePending(String invitationId, String user) {
    Invitation inv = store.findById(invitationId)
        .orElseThrow(() -> new InvitationNotFoundException(invitationId));
    if (!inv.invitee().equals(user)) {
      throw new ItineraryForbiddenException();
    }
    if (!inv.isPending()) {
      throw new InvitationConflictException(inv.id(), inv.status());
    }
    return inv;
  }

  private Itinerary requireItinerary(String itineraryId) {
    return itineraries.findById(itineraryId)
        .orElseThrow(() -> new ItineraryNotFoundException(itineraryId));
  }

  private Optional<InvitationSummary> toSummary(Invitation inv) {
    return itineraries.findById(inv.itineraryId())
        .map(it -> new InvitationSummary(inv, it.title()));
  }

  /** 行程可能已被删除；此时标题为 null，不影响状态结果。 */
  private String titleOf(String itineraryId) {
    return itineraries.findById(itineraryId).map(Itinerary::title).orElse(null);
  }

  private boolean isMember(Itinerary it, String user) {
    return it.members().stream().anyMatch(m -> m.username().equals(user));
  }
}
