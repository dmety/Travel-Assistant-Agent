package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 行程聚合根：归属 owner，含成员与五大板块数据，整体持有一个乐观锁版本号。
 * 任何写操作（含成员变更）都会使版本号 +1；客户端须携带当前版本号，
 * 不一致则拒绝覆盖（冲突）。
 */
public record Itinerary(
    @JsonProperty("id") String id,
    @JsonProperty("owner") String owner,
    @JsonProperty("title") String title,
    @JsonProperty("version") long version,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("updatedAt") Instant updatedAt,
    @JsonProperty("members") List<Member> members,
    @JsonProperty("plan") Plan plan,
    @JsonProperty("dailySchedules") List<DailySchedule> dailySchedules,
    @JsonProperty("transports") List<Transport> transports,
    @JsonProperty("lodgings") List<Lodging> lodgings,
    @JsonProperty("expenses") List<Expense> expenses) {

  @JsonCreator
  public Itinerary {
    members = (members == null) ? List.of() : members;
    dailySchedules = (dailySchedules == null) ? List.of() : dailySchedules;
    transports = (transports == null) ? List.of() : transports;
    lodgings = (lodgings == null) ? List.of() : lodgings;
    expenses = (expenses == null) ? List.of() : expenses;
  }

  public static Itinerary create(String owner, String title, Plan plan) {
    Instant now = Instant.now();
    return new Itinerary(
        UUID.randomUUID().toString(), owner, title, 1L, now, now,
        List.of(), plan == null ? Plan.empty() : plan,
        List.of(), List.of(), List.of(), List.of());
  }

  private Itinerary copy(long version, Plan plan, List<DailySchedule> daily,
      List<Transport> transports, List<Lodging> lodgings, List<Expense> expenses,
      List<Member> members) {
    return new Itinerary(
        id, owner, title, version, createdAt, Instant.now(),
        members == null ? this.members : members,
        plan == null ? this.plan : plan,
        daily == null ? this.dailySchedules : daily,
        transports == null ? this.transports : transports,
        lodgings == null ? this.lodgings : lodgings,
        expenses == null ? this.expenses : expenses);
  }

  public Itinerary withPlan(Plan plan) {
    return copy(version + 1, plan, null, null, null, null, null);
  }

  public Itinerary withDaily(List<DailySchedule> daily) {
    return copy(version + 1, null, daily, null, null, null, null);
  }

  public Itinerary withTransports(List<Transport> transports) {
    return copy(version + 1, null, null, transports, null, null, null);
  }

  public Itinerary withLodgings(List<Lodging> lodgings) {
    return copy(version + 1, null, null, null, lodgings, null, null);
  }

  public Itinerary withExpenses(List<Expense> expenses) {
    return copy(version + 1, null, null, null, null, expenses, null);
  }

  public Itinerary withMembers(List<Member> members) {
    return copy(version + 1, null, null, null, null, null, members);
  }
}
