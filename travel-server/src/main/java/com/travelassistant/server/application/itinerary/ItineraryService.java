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
import com.travelassistant.server.infrastructure.persistence.DecisionStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.time.LocalDate;
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
  private final DecisionStore decisions;
  private final Object[] writeLocks = new Object[LOCK_STRIPES];

  public ItineraryService(ItineraryStore store, AccountStore accounts, ChangeLogRecorder changeLog,
      DecisionStore decisions) {
    this.store = store;
    this.accounts = accounts;
    this.changeLog = changeLog;
    this.decisions = decisions;
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
    return listForUser(user, null);
  }

  /**
   * 列出可见行程。
   *
   * @param archived {@code null} 表示不过滤（归档与未归档都返回，这是默认行为，
   *                 避免老客户端升级后「行程凭空消失」）；{@code true} 只看归档；
   *                 {@code false} 只看未归档
   */
  public List<Itinerary> listForUser(String user, Boolean archived) {
    return store.findAll().stream()
        .filter(it -> isParticipant(it, user))
        .filter(it -> archived == null || it.archived() == archived)
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
      // 行程没了，指向它的留言、修改记录、候选与选票也不该留着
      changeLog.purge(id);
      decisions.purge(id);
      return null;
    });
  }

  /**
   * 归档行程（V3 第 4 节）。仅创建者可操作。
   *
   * <p>归档不是删除：数据全部保留，只是变成只读，并且默认不出现在「进行中」列表里。
   * 是否允许重复归档保持幂等——已经是归档状态时直接返回，不涨版本号，
   * 免得用户连点两次就因为版本冲突报错。
   */
  public Itinerary archive(String id, String user) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, user);
      if (it.archived()) {
        return it;
      }
      Itinerary updated = it.withArchived(true);
      store.save(updated);
      changeLog.record(id, user, "ARCHIVE", "归档行程（转为只读）", updated.version());
      return updated;
    });
  }

  /** 取消归档，恢复可编辑。仅创建者可操作；未归档时幂等返回。 */
  public Itinerary unarchive(String id, String user) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireOwner(it, user);
      if (!it.archived()) {
        return it;
      }
      Itinerary updated = it.withArchived(false);
      store.save(updated);
      changeLog.record(id, user, "ARCHIVE", "取消归档，恢复可编辑", updated.version());
      return updated;
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
      List<DailySchedule> filled = fillIds(daily, "每日安排", DailySchedule::id, (d, nid) -> {
        List<DailyActivity> acts = fillIds(d.activities(), "每日安排中的安排项", DailyActivity::id,
            (a, aid) -> new DailyActivity(aid, a.time(), a.title(), a.location(), a.note(),
                a.startTime(), a.endTime(), a.ticketRecord()));
        return new DailySchedule(nid, d.date(), d.note(), acts);
      });
      for (DailySchedule day : filled) {
        for (DailyActivity a : day.activities()) {
          if (a.ticketRecord() != null) {
            var previous = it.dailySchedules().stream().flatMap(d -> d.activities().stream())
                .filter(old -> Objects.equals(old.id(), a.id())).findFirst().orElse(null);
            if (previous == null || !Objects.equals(previous.ticketRecord(), a.ticketRecord())
                || !Objects.equals(day.date(), a.ticketRecord().visitDate())) {
              throw new InvalidItineraryException("购票登记须通过专用接口更新，日期调整前请先处理登记");
            }
          }
        }
      }
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
      List<Transport> filled = fillIds(transports, "交通", Transport::id, (t, nid) -> new Transport(
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
      List<Lodging> filled = fillIds(lodgings, "住宿", Lodging::id, (l, nid) -> new Lodging(
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
      List<Expense> filled = fillIds(expenses, "费用", Expense::id, (e, nid) -> new Expense(
          nid, e.category(), e.amount(), e.currency(), e.date(), e.note(), e.actual()));
      validateNonNegativeAmounts(filled);
      Itinerary updated = it.withExpenses(filled);
      store.save(updated);
      changeLog.record(id, user, "EXPENSE", "更新费用：" + filled.size() + " 笔",
          updated.version());
      return updated;
    });
  }

  /**
   * 把一项安排追加到指定日期的每日安排里（V3「确认候选」与客户端手工登记共用）。
   *
   * <p>只追加、不替换：整份 {@code PUT /daily} 适合表单编辑，而「确认一个候选景点」
   * 只需要往某天里加一条。分开发是为了避免为了加一条安排，客户端得先把整份日程读出来、
   * 改完再整体回写——那是最容易覆盖别人改动的方式。
   *
   * @param date 目标日期；为 {@code null} 时抛业务异常
   */
  public Itinerary appendActivity(String id, String user, LocalDate date, String title,
      String location, String note, long version) {
    if (date == null) {
      throw new InvalidItineraryException("请指定加入哪一天（date 不能为空）");
    }
    if (title == null || title.isBlank()) {
      throw new InvalidItineraryException("安排名称不能为空");
    }
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.DAILY);
      checkVersion(it, version);

      DailyActivity activity = new DailyActivity(UUID.randomUUID().toString(), null,
          title.strip(), location, note, null, null);
      List<DailySchedule> daily = new ArrayList<>(it.dailySchedules());
      int index = -1;
      for (int i = 0; i < daily.size(); i++) {
        if (date.equals(daily.get(i).date())) {
          index = i;
          break;
        }
      }
      if (index >= 0) {
        DailySchedule day = daily.get(index);
        List<DailyActivity> acts = new ArrayList<>(day.activities());
        acts.add(activity);
        daily.set(index, new DailySchedule(day.id(), day.date(), day.note(), acts));
      } else {
        daily.add(new DailySchedule(UUID.randomUUID().toString(), date, "",
            List.of(activity)));
      }

      Itinerary updated = it.withDaily(daily);
      store.save(updated);
      changeLog.record(id, user, "DAILY",
          "新增安排「" + title.strip() + "」（" + date + "）", updated.version());
      return updated;
    });
  }

  /** 更新同一活动的自报登记，始终由服务端写来源与时间。 */
  public Itinerary recordTicket(String id, String user, String activityId,
      com.travelassistant.server.domain.itinerary.TicketRecord input, long version) {
    return inWriteLock(id, () -> {
      Itinerary it = requireExists(id);
      requireEdit(it, user, Section.DAILY);
      checkVersion(it, version);
      if (input == null || input.attractionId() == null || input.attractionId().isBlank() || input.attractionId().length() > 100
          || input.product() == null || input.product().isBlank() || input.product().length() > 100
          || input.visitDate() == null || input.travelers() == null || input.travelers() < 1
          || input.travelers() > 100 || (input.paidTotal() != null
          && (input.paidTotal().signum() < 0 || input.paidTotal().scale() > 2))
          || (input.session() != null && input.session().length() > 100)) {
        throw new InvalidItineraryException("请检查景点、产品、日期、人数及非负实付金额（最多两位小数）");
      }
      var record = new com.travelassistant.server.domain.itinerary.TicketRecord(
          input.attractionId(), input.product().strip(), input.visitDate(), input.session(),
          input.travelers(), input.paidTotal(), java.time.Instant.now(),
          com.travelassistant.server.domain.itinerary.TicketRecord.SOURCE);
      boolean found = false;
      List<DailySchedule> days = new ArrayList<>();
      for (DailySchedule day : it.dailySchedules()) {
        List<DailyActivity> activities = new ArrayList<>();
        for (DailyActivity a : day.activities()) {
          if (Objects.equals(a.id(), activityId)) {
            if (!Objects.equals(day.date(), input.visitDate())) {
              throw new InvalidItineraryException("登记日期与活动日期不一致，请先调整");
            }
            found = true;
            activities.add(new DailyActivity(a.id(), a.time(), a.title(), a.location(), a.note(),
                a.startTime(), a.endTime(), record));
          } else { activities.add(a); }
        }
        days.add(new DailySchedule(day.id(), day.date(), day.note(), activities));
      }
      if (!found) { throw new InvalidItineraryException("未找到目标活动，请先在行程中创建活动"); }
      Itinerary updated = it.withDaily(days);
      store.save(updated);
      changeLog.record(id, user, "DAILY", "更新用户自报购票登记（未经平台核验）", updated.version());
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
      requireNotArchived(it);
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
      requireNotArchived(it);
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
      // 非成员先按「成员不存在」拒绝，再谈归档：否则非成员退出一个已归档行程会拿到
      // 409 + archived，等于替对方确认了「这份行程存在且已定稿」
      boolean ownerLeaving = it.owner().equals(user);
      if (!ownerLeaving && indexOfMember(it.members(), user) < 0) {
        throw new MemberNotFoundException(id, user);
      }
      requireNotArchived(it);
      if (ownerLeaving) {
        throw new InvalidItineraryException("行程创建者不能退出，如需终止请删除行程");
      }
      List<Member> members = new ArrayList<>(it.members());
      int index = indexOfMember(members, user);
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
      requireNotArchived(it);
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
      requireNotArchived(it);
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
  private void validateLodgingPeriods(List<Lodging> lodgings) {
    for (Lodging l : lodgings) {
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

  /**
   * 板块编辑权限校验。
   *
   * <p>**先判权限，再判归档**。反过来的话，一个与这份行程毫无关系的人提交修改，
   * 会先撞上「已归档」拿到 409 + {@code archived=true}，等于告诉对方「这个 id 存在、
   * 而且已经定稿了」——即使只有 403 之外的这一点点差别，也足够用来探测别人的行程状态。
   * 权限先命中时，非成员无论行程处于什么状态都只看到同一个 403，没有可对比的信息。
   *
   * <p>代价是「有权限但没给这个板块」的人拿到 403 而不是 409：他有查看权，
   * 本来就知道这份行程的状态，不构成额外泄露。
   */
  private void requireEdit(Itinerary it, String user, Section section) {
    if (!it.owner().equals(user)) {
      Member m = it.members().stream()
          .filter(x -> x.username().equals(user))
          .findFirst()
          .orElse(null);
      if (m == null || !m.editSections().contains(section)) {
        throw new ItineraryForbiddenException();
      }
    }
    requireNotArchived(it);
  }

  private void requireOwner(Itinerary it, String user) {
    if (!it.owner().equals(user)) {
      throw new ItineraryForbiddenException();
    }
  }

  /**
   * 已归档的行程只读（V3 第 4 节）。
   *
   * <p>放在所有写路径的共同入口上，而不是分散在各控制器里：归档的语义是「这份方案定稿了」，
   * 任何一个板块还能改，这个语义就不成立。要改就先取消归档——那是一次明确的操作。
   */
  private void requireNotArchived(Itinerary it) {
    if (it.archived()) {
      throw new ItineraryArchivedException(it.id());
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

  /**
   * 为列表中缺失 id 的元素补一个服务端生成的 id（保证稳定引用）。
   *
   * <p>数组里出现 {@code null} 元素时按请求体不合法拒绝。客户端表格里留一个空行、
   * 或者前端把 {@code [undefined]} 直接序列化，都会发出 {@code [null]}；这类请求在
   * 补 id 时会被解引用成空指针，最终以 500 返回，而它本质是 400 —— 用户看到的是
   * 「服务器内部错误」，日志里也只剩一条无上下文的 NPE。{@code label} 用来把出错位置
   * 说清楚，前端可以直接定位到第几项。
   */
  private <T> List<T> fillIds(List<T> list, String label, Function<T, String> idGetter,
      BiFunction<T, String, T> replacer) {
    if (list == null) {
      return List.of();
    }
    List<T> result = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      T item = list.get(i);
      if (item == null) {
        throw new InvalidItineraryException(
            label + "第 " + (i + 1) + " 项为空，请删除该空行后重试");
      }
      String id = idGetter.apply(item);
      result.add(id == null ? replacer.apply(item, UUID.randomUUID().toString()) : item);
    }
    return result;
  }
}
