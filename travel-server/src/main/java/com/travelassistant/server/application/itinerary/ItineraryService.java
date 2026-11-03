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
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 行程用例：归属/板块权限校验、乐观锁版本控制、服务端 JSON 持久化。 */
@Service
public class ItineraryService {
  private final ItineraryStore store;

  public ItineraryService(ItineraryStore store) {
    this.store = store;
  }

  // ---- 行程级 ----

  public Itinerary create(String owner, String title, Plan plan) {
    Itinerary it = Itinerary.create(owner, title, plan);
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
    Itinerary it = requireExists(id);
    requireOwner(it, user);
    store.delete(id);
  }

  // ---- 板块级（细粒度权限 + 版本） ----

  public Plan getPlan(String id, String user) {
    return get(id, user).plan();
  }

  public Itinerary updatePlan(String id, String user, Plan plan, long version) {
    Itinerary it = requireExists(id);
    requireEdit(it, user, Section.PLAN);
    checkVersion(it, version);
    Itinerary updated = it.withPlan(plan);
    store.save(updated);
    return updated;
  }

  public List<DailySchedule> getDaily(String id, String user) {
    return get(id, user).dailySchedules();
  }

  public Itinerary updateDaily(String id, String user, List<DailySchedule> daily, long version) {
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
  }

  public List<Transport> getTransports(String id, String user) {
    return get(id, user).transports();
  }

  public Itinerary updateTransports(String id, String user, List<Transport> transports, long version) {
    Itinerary it = requireExists(id);
    requireEdit(it, user, Section.TRANSPORT);
    checkVersion(it, version);
    List<Transport> filled = fillIds(transports, Transport::id, (t, nid) -> new Transport(
        nid, t.mode(), t.from(), t.to(), t.date(), t.departTime(), t.arriveTime(),
        t.flightNo(), t.seat(), t.note()));
    Itinerary updated = it.withTransports(filled);
    store.save(updated);
    return updated;
  }

  public List<Lodging> getLodgings(String id, String user) {
    return get(id, user).lodgings();
  }

  public Itinerary updateLodgings(String id, String user, List<Lodging> lodgings, long version) {
    Itinerary it = requireExists(id);
    requireEdit(it, user, Section.LODGING);
    checkVersion(it, version);
    List<Lodging> filled = fillIds(lodgings, Lodging::id, (l, nid) -> new Lodging(
        nid, l.name(), l.address(), l.checkIn(), l.checkOut(), l.roomType(), l.note()));
    Itinerary updated = it.withLodgings(filled);
    store.save(updated);
    return updated;
  }

  public List<Expense> getExpenses(String id, String user) {
    return get(id, user).expenses();
  }

  public Itinerary updateExpenses(String id, String user, List<Expense> expenses, long version) {
    Itinerary it = requireExists(id);
    requireEdit(it, user, Section.EXPENSE);
    checkVersion(it, version);
    List<Expense> filled = fillIds(expenses, Expense::id, (e, nid) -> new Expense(
        nid, e.category(), e.amount(), e.currency(), e.date(), e.note()));
    Itinerary updated = it.withExpenses(filled);
    store.save(updated);
    return updated;
  }

  // ---- 成员管理（仅 owner） ----

  public List<Member> getMembers(String id, String user) {
    return get(id, user).members();
  }

  public Itinerary addMember(String id, String ownerUser, String memberUser,
      Set<Section> editSections, long version) {
    Itinerary it = requireExists(id);
    requireOwner(it, ownerUser);
    checkVersion(it, version);
    List<Member> members = new ArrayList<>(it.members());
    members.removeIf(m -> m.username().equals(memberUser));
    members.add(new Member(memberUser, editSections));
    Itinerary updated = it.withMembers(members);
    store.save(updated);
    return updated;
  }

  public Itinerary updateMember(String id, String ownerUser, String memberUser,
      Set<Section> editSections, long version) {
    Itinerary it = requireExists(id);
    requireOwner(it, ownerUser);
    checkVersion(it, version);
    List<Member> members = it.members().stream()
        .map(m -> m.username().equals(memberUser) ? new Member(memberUser, editSections) : m)
        .toList();
    Itinerary updated = it.withMembers(members);
    store.save(updated);
    return updated;
  }

  public Itinerary removeMember(String id, String ownerUser, String memberUser, long version) {
    Itinerary it = requireExists(id);
    requireOwner(it, ownerUser);
    checkVersion(it, version);
    List<Member> members = it.members().stream()
        .filter(m -> !m.username().equals(memberUser))
        .toList();
    Itinerary updated = it.withMembers(members);
    store.save(updated);
    return updated;
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
