package com.travelassistant.server.application.itinerary;

import com.travelassistant.server.application.collaboration.ChangeLogRecorder;
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
import java.util.Objects;
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
  private final ChangeLogRecorder changeLog;
  private final Object[] writeLocks = new Object[LOCK_STRIPES];

  public ItineraryService(ItineraryStore store, AccountStore accounts, ChangeLogRecorder changeLog) {
    this.store = store;
    this.accounts = accounts;
    this.changeLog = changeLog;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      writeLocks[i] = new Object();
    }
  }

  // ---- 行程级 ----

  public Itinerary create(String owner, String title, Plan plan) {
    if (title == null || title.isBlank()) {
      throw new InvalidItineraryException("行程标题不能为空");
    }
    validatePlanPeriod(plan);
    Itinerary it = Itinerary.create(owner, title.trim(), plan);
    store.save(it);
    changeLog.record(it.id(), owner, "ITINERARY", "创建行程「" + it.title() + "」", it.version());
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
      // 行程没了，指向它的留言与修改记录也不该留着
      changeLog.purge(id);
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
      validatePlanPeriod(plan);
      Itinerary updated = it.withPlan(plan);
      store.save(updated);
      changeLog.record(id, user, "PLAN", describePlanChange(it.plan(), plan), updated.version());
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
            (a, aid) -> new DailyActivity(aid, a.time(), a.title(), a.location(), a.note(),
                a.startTime(), a.endTime()));
        return new DailySchedule(nid, d.date(), d.note(), acts);
      });
      validateActivityTimes(filled);
      Itinerary updated = it.withDaily(filled);
      store.save(updated);
      changeLog.record(id, user, "DAILY", "更新每日安排：" + filled.size() + " 天 / "
          + countActivities(filled) + " 项", updated.version());
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
      changeLog.record(id, user, "TRANSPORT", "更新交通：" + filled.size() + " 段",
          updated.version());
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
      changeLog.record(id, user, "LODGING", "更新住宿：" + filled.size() + " 处",
          updated.version());
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
      changeLog.record(id, user, "EXPENSE", "更新费用：" + filled.size() + " 笔",
          updated.version());
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
      changeLog.record(id, ownerUser, "MEMBER",
          "添加成员 " + name + "（授权：" + sectionsText(editSections) + "）", updated.version());
      return updated;
    });
  }

  /**
   * 受邀者接受邀请后加入成员（由 {@code InvitationService} 调用）。
   *
   * <p>这里**不校验版本号**：加入成员是受邀者对邀请的响应，不是与其他人的并发编辑；
   * 若要求版本号，受邀者得先在「我收到的邀请」里拿到行程版本，而创建者随时可能改动，
   * 一步就能成功的事会变成反复冲突。写入仍在行程写锁内完成，版本号照常 +1。
   *
   * <p>幂等：已是成员时只更新授权板块，不重复追加。
   */
  public Itinerary joinAsMember(String id, String user, Set<Section> editSections) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      List<Member> members = new ArrayList<>(it.members());
      int index = indexOfMember(members, user);
      Member target = new Member(user, editSections);
      if (index >= 0) {
        members.set(index, target);
      } else {
        members.add(target);
      }
      Itinerary updated = it.withMembers(members);
      store.save(updated);
      changeLog.record(id, user, "MEMBER",
          "成员 " + user + " 加入（授权：" + sectionsText(editSections) + "）", updated.version());
      return updated;
    });
  }

  /**
   * 成员主动退出（需求 §4「成员可主动退出」）。
   *
   * <p>创建者不能退出：行程必须始终有归属人，否则会变成无人可管理、也无人可删除的孤儿数据。
   * 创建者要终止行程应当删除它。
   */
  public void leave(String id, String user) {
    inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      if (it.owner().equals(user)) {
        throw new InvalidItineraryException("行程创建者不能退出，如需终止请删除行程");
      }
      List<Member> members = new ArrayList<>(it.members());
      int index = indexOfMember(members, user);
      if (index < 0) {
        throw new MemberNotFoundException(id, user);
      }
      members.remove(index);
      Itinerary updated = it.withMembers(members);
      store.save(updated);
      changeLog.record(id, user, "MEMBER", "成员 " + user + " 退出", updated.version());
      return null;
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
      changeLog.record(id, ownerUser, "MEMBER",
          "调整成员 " + memberUser + " 的授权（" + sectionsText(editSections) + "）",
          updated.version());
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
      changeLog.record(id, ownerUser, "MEMBER", "移除成员 " + memberUser, updated.version());
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

  /**
   * 安排结束时间不能早于开始时间（V2 第 3 节）。写入时拦截硬错误。
   *
   * <p>「超出旅行范围」和「同日重叠」只做体检提示、不拦截写入：前者可能是有意的
   * 跨天交通安排，后者需要用户自己判断是否真的要调整顺序，服务端直接拒绝会挡掉
   * 正在编辑中的中间状态（例如先把上下午调换位置）。倒置则是纯笔误，没有合理解释。
   */
  private void validateActivityTimes(List<DailySchedule> daily) {
    for (DailySchedule day : daily) {
      for (DailyActivity a : day.activities()) {
        if (a.timeInverted()) {
          throw new InvalidItineraryException("安排「" + describe(a.title()) + "」（" + day.date()
              + "）的结束时间 " + a.endTime() + " 早于开始时间 " + a.startTime());
        }
      }
    }
  }

  /** 住宿离店日期不能早于入住日期（缺陷 D-5：此前会落出负时长）。 */
  private void validateLodgingPeriods(List<Lodging> lodgings) {    for (Lodging l : lodgings) {
      if (l.checkIn() != null && l.checkOut() != null && l.checkOut().isBefore(l.checkIn())) {
        throw new InvalidItineraryException("离店日期不能早于入住日期：" + l.checkIn() + " → "
            + l.checkOut() + "（" + describe(l.name()) + "）");
      }
    }
  }

  /**
   * 返回日期不能早于出发日期（V1 第 5 节「时间检查」）。
   *
   * <p>在写入时拦截，而不是只在体检接口里提示：错误数据一旦落盘，后续每次汇总都要重新解释它。
   * 允许任一日期为空，缺失由体检接口提示「未填写」。
   */
  private void validatePlanPeriod(Plan plan) {
    if (plan == null || plan.departDate() == null || plan.returnDate() == null) {
      return;
    }
    if (plan.returnDate().isBefore(plan.departDate())) {
      throw new InvalidItineraryException(
          "返回日期不能早于出发日期：" + plan.departDate() + " → " + plan.returnDate());
    }
  }

  private String describe(String text) {
    return text == null || text.isBlank() ? "未命名" : text;
  }

  /** 方案变更摘要：只写真正变了的字段，避免每条记录都是同一句废话。 */
  private String describePlanChange(Plan before, Plan after) {
    if (after == null) {
      return "清空方案";
    }
    Plan b = before == null ? Plan.empty() : before;
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(b.destination(), after.destination())) {
      changed.add("目的地改为「" + describe(after.destination()) + "」");
    }
    if (!Objects.equals(b.summary(), after.summary())) {
      changed.add("概述已更新");
    }
    if (!Objects.equals(b.notes(), after.notes())) {
      changed.add("备注已更新");
    }
    if (!Objects.equals(b.departDate(), after.departDate())
        || !Objects.equals(b.returnDate(), after.returnDate())) {
      changed.add("日期改为 " + dateRangeText(after));
    }
    return changed.isEmpty() ? "更新方案（内容无变化）" : "更新方案：" + String.join("；", changed);
  }

  private String dateRangeText(Plan plan) {
    if (plan.departDate() == null && plan.returnDate() == null) {
      return "未填写";
    }
    return (plan.departDate() == null ? "未填出发" : plan.departDate().toString())
        + " ~ "
        + (plan.returnDate() == null ? "未填返回" : plan.returnDate().toString());
  }

  /** 授权板块的中文描述，用于修改记录。 */
  private String sectionsText(Set<Section> sections) {
    if (sections == null || sections.isEmpty()) {
      return "无";
    }
    List<String> names = new ArrayList<>();
    for (Section s : Section.values()) {
      if (sections.contains(s)) {
        names.add(sectionName(s));
      }
    }
    return names.isEmpty() ? "无" : String.join("、", names);
  }

  private String sectionName(Section s) {
    return switch (s) {
      case PLAN -> "方案";
      case DAILY -> "每日安排";
      case TRANSPORT -> "交通";
      case LODGING -> "住宿";
      case EXPENSE -> "费用";
    };
  }

  private int countActivities(List<DailySchedule> daily) {
    int total = 0;
    for (DailySchedule d : daily) {
      total += d.activities().size();
    }
    return total;
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
