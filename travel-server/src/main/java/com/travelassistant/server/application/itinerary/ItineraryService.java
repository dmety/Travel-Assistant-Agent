package com.travelassistant.server.application.itinerary;

import com.travelassistant.server.domain.itinerary.DailyActivity;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Lodging;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.domain.itinerary.Transport;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 行程用例：归属/板块权限校验、乐观锁版本控制、服务端 JSON 持久化。
 *
 * <p>并发安全：所有写操作都在「按行程 id 分片」的锁内完成「读取 → 校验版本 → 写入」，
 * 使三段操作成为原子动作。此前版本校验发生在 {@link ItineraryStore#save} 的锁之外，
 * 多个同版本请求可同时通过校验，导致后写覆盖前写（缺陷 D-6）。
 */
@Service
public class ItineraryService {
  /** 写锁分片数：固定长度，避免按 id 建锁导致 Map 无限增长。 */
  private static final int LOCK_STRIPES = 64;

  private final ItineraryStore store;
  private final AccountStore accounts;
  private final Object[] writeLocks = new Object[LOCK_STRIPES];

  public ItineraryService(ItineraryStore store, AccountStore accounts) {
    this.store = store;
    this.accounts = accounts;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      writeLocks[i] = new Object();
    }
  }

  // ---- 行程级 ----

  public Itinerary create(String owner, String title, Plan plan) {
    if (title == null || title.isBlank()) {
      throw new InvalidItineraryException("行程标题不能为空");
    }
    Itinerary it = Itinerary.create(owner, title.trim(), plan);
    store.save(it);
    return it;
  }

  public List<Itinerary> listForUser(String user) {
    return store.findAll().stream()
        .filter(it -> isParticipant(it, user))
        .toList();
  }

  public Itinerary get(String id, String user) {
    Itinerary it = requireExists(id);
    requireView(it, user);
    return it;
  }

  public void delete(String id, String user) {
    inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, user);
      store.delete(id);
      return null;
    });
  }

  // ---- 板块级（细粒度权限 + 版本） ----

  public Plan getPlan(String id, String user) {
    return get(id, user).plan();
  }

  public Itinerary updatePlan(String id, String user, Plan plan, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.PLAN);
      checkVersion(it, version);
      Itinerary updated = it.withPlan(plan);
      store.save(updated);
      return updated;
    });
  }

  public List<DailySchedule> getDaily(String id, String user) {
    return get(id, user).dailySchedules();
  }

  public Itinerary updateDaily(String id, String user, List<DailySchedule> daily, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.DAILY);
      checkVersion(it, version);
      List<DailySchedule> filled = fillIds(daily, DailySchedule::id, (d, nid) -> {
        List<DailyActivity> acts = fillIds(d.activities(), DailyActivity::id,
            (a, aid) -> new DailyActivity(aid, a.time(), a.title(), a.location(), a.note()));
        return new DailySchedule(nid, d.date(), d.note(), acts);
      });
      Itinerary updated = it.withDaily(filled);
      store.save(updated);
      return updated;
    });
  }

  public List<Transport> getTransports(String id, String user) {
    return get(id, user).transports();
  }

  public Itinerary updateTransports(String id, String user, List<Transport> transports,
      long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.TRANSPORT);
      checkVersion(it, version);
      List<Transport> filled = fillIds(transports, Transport::id, (t, nid) -> new Transport(
          nid, t.mode(), t.from(), t.to(), t.date(), t.departTime(), t.arriveTime(),
          t.flightNo(), t.seat(), t.note()));
      Itinerary updated = it.withTransports(filled);
      store.save(updated);
      return updated;
    });
  }

  public List<Lodging> getLodgings(String id, String user) {
    return get(id, user).lodgings();
  }

  public Itinerary updateLodgings(String id, String user, List<Lodging> lodgings, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.LODGING);
      checkVersion(it, version);
      List<Lodging> filled = fillIds(lodgings, Lodging::id, (l, nid) -> new Lodging(
          nid, l.name(), l.address(), l.checkIn(), l.checkOut(), l.roomType(), l.note()));
      validateLodgingPeriods(filled);
      Itinerary updated = it.withLodgings(filled);
      store.save(updated);
      return updated;
    });
  }

  public List<Expense> getExpenses(String id, String user) {
    return get(id, user).expenses();
  }

  public Itinerary updateExpenses(String id, String user, List<Expense> expenses, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.EXPENSE);
      checkVersion(it, version);
      List<Expense> filled = fillIds(expenses, Expense::id, (e, nid) -> new Expense(
          nid, e.category(), e.amount(), e.currency(), e.date(), e.note()));
      validateNonNegativeAmounts(filled);
      Itinerary updated = it.withExpenses(filled);
      store.save(updated);
      return updated;
    });
  }

  // ---- 成员管理（仅 owner） ----

  public List<Member> getMembers(String id, String user) {
    return get(id, user).members();
  }

  public Itinerary addMember(String id, String ownerUser, String memberUser,
      Set<Section> editSections, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, ownerUser);
      checkVersion(it, version);
      String name = memberUser == null ? "" : memberUser.trim();
      if (name.isEmpty()) {
        throw new InvalidItineraryException("成员用户名不能为空");
      }
      if (!accounts.exists(name)) {
        throw new InvalidItineraryException("用户不存在：" + name + "（只能邀请已注册用户）");
      }
      List<Member> members = new ArrayList<>(it.members());
      members.removeIf(m -> m.username().equals(name));
      members.add(new Member(name, editSections));
      Itinerary updated = it.withMembers(members);
      store.save(updated);
      return updated;
    });
  }

  public Itinerary updateMember(String id, String ownerUser, String memberUser,
      Set<Section> editSections, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, ownerUser);
      checkVersion(it, version);
      List<Member> members = new ArrayList<>(it.members());
      int index = indexOfMember(members, memberUser);
      if (index < 0) {
        throw new MemberNotFoundException(id, memberUser);
      }
      members.set(index, new Member(memberUser, editSections));
      Itinerary updated = it.withMembers(members);
      store.save(updated);
      return updated;
    });
  }

  public Itinerary removeMember(String id, String ownerUser, String memberUser, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, ownerUser);
      checkVersion(it, version);
      List<Member> members = new ArrayList<>(it.members());
      int index = indexOfMember(members, memberUser);
      if (index < 0) {
        throw new MemberNotFoundException(id, memberUser);
      }
      members.remove(index);
      Itinerary updated = it.withMembers(members);
      store.save(updated);
      return updated;
    });
  }

  // ---- 校验 ----

  /** 费用金额必须为非负数（缺陷 D-4：此前可为负，会污染预算汇总）。 */
  private void validateNonNegativeAmounts(List<Expense> expenses) {
    for (Expense e : expenses) {
      if (e.amount() != null && e.amount().signum() < 0) {
        throw new InvalidItineraryException(
            "费用金额不能为负数：" + e.amount().toPlainString() + "（" + describe(e.category()) + "）");
      }
    }
  }

  /** 住宿离店日期不能早于入住日期（缺陷 D-5：此前会落出负时长）。 */
  private void validateLodgingPeriods(List<Lodging> lodgings) {
    for (Lodging l : lodgings) {
      if (l.checkIn() != null && l.checkOut() != null && l.checkOut().isBefore(l.checkIn())) {
        throw new InvalidItineraryException("离店日期不能早于入住日期：" + l.checkIn() + " → "
            + l.checkOut() + "（" + describe(l.name()) + "）");
      }
    }
  }

  private String describe(String text) {
    return text == null || text.isBlank() ? "未命名" : text;
  }

  private int indexOfMember(List<Member> members, String username) {
    for (int i = 0; i < members.size(); i++) {
      if (members.get(i).username().equals(username)) {
        return i;
      }
    }
    return -1;
  }

  // ---- 权限/版本辅助 ----

  private boolean isParticipant(Itinerary it, String user) {
    if (it.owner().equals(user)) {
      return true;
    }
    return it.members().stream().anyMatch(m -> m.username().equals(user));
  }

  private void requireView(Itinerary it, String user) {
    if (!isParticipant(it, user)) {
      throw new ItineraryForbiddenException();
    }
  }

  private void requireEdit(Itinerary it, String user, Section section) {
    if (it.owner().equals(user)) {
      return;
    }
    Member m = it.members().stream()
        .filter(x -> x.username().equals(user))
        .findFirst()
        .orElse(null);
    if (m == null || !m.editSections().contains(section)) {
      throw new ItineraryForbiddenException();
    }
  }

  private void requireOwner(Itinerary it, String user) {
    if (!it.owner().equals(user)) {
      throw new ItineraryForbiddenException();
    }
  }

  private void checkVersion(Itinerary it, long expected) {
    if (it.version() != expected) {
      throw new VersionConflictException(it.version());
    }
  }

  private Itinerary requireExists(String id) {
    return store.findById(id).orElseThrow(() -> new ItineraryNotFoundException(id));
  }

  /**
   * 在行程维度的写锁内执行读写。
   *
   * <p>锁按 {@code id} 哈希分片，同一行程的并发写必然串行，不同行程之间锁竞争概率低；
   * 校验失败时直接抛异常，未写入即释放锁，内存与文件状态保持一致。
   */
  private <T> T inWriteLock(String id, Supplier<T> action) {
    synchronized (writeLocks[Math.floorMod(id.hashCode(), LOCK_STRIPES)]) {
      return action.get();
    }
  }

  /** 为列表中缺失 id 的元素补一个服务端生成的 id（保证稳定引用）。 */
  private <T> List<T> fillIds(List<T> list, Function<T, String> idGetter,
      BiFunction<T, String, T> replacer) {
    if (list == null) {
      return List.of();
    }
    List<T> result = new ArrayList<>(list.size());
    for (T item : list) {
      String id = idGetter.apply(item);
      result.add(id == null ? replacer.apply(item, UUID.randomUUID().toString()) : item);
    }
    return result;
  }
}
