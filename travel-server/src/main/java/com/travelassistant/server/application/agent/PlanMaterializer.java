package com.travelassistant.server.application.agent;

import com.travelassistant.server.application.attraction.AttractionService;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.itinerary.DailyActivity;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Plan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 草案 → 正式行程载荷的转换器（**只读，不写库**）。
 *
 * <p>「基础业务闭环」里最难被绕过的一环：如果用户看到的草案和最终落库的内容
 * 是两套代码生成的，就会出现「确认的是 A、保存的是 B」。这里让确认接口复用
 * {@link PlanningService#planFacts} 与 {@link DraftAssembler#arrange} 重新装配，
 * 展示与落库用的是同一份规则结果。
 *
 * <p>安全边界：客户端只提交景点 ID，服务端按 ID 重新从知识库读取名称、票价、
 * 开放时间与来源。客户端回传不了「免费」「已核验」这类结论，也塞不进库里没有的 ID。
 *
 * <p>真正写库仍由调用方完成：{@code POST /api/itineraries} →
 * {@code PUT /api/itineraries/{id}/daily?version=<创建返回的 version>}。
 */
@Service
public class PlanMaterializer {

  private static final String GENERATED_FROM = "POST /api/agent/plan";

  private final PlanningService planning;
  private final AttractionService attractions;

  public PlanMaterializer(PlanningService planning, AttractionService attractions) {
    this.planning = planning;
    this.attractions = attractions;
  }

  public MaterializedPlan materialize(ConfirmDraftRequest command) {
    if (command == null || command.title() == null || command.title().isBlank()) {
      throw new InvalidPlanningRequestException("请填写行程标题");
    }
    PlanningRequest request = command.request();
    if (request == null || request.destination() == null || request.destination().isBlank()) {
      throw new InvalidPlanningRequestException("请填写目的地");
    }
    // 没有日期就落库会生成一份「不知道哪天去」的行程，也会让逐日草案越界。
    // 这里挡住，让用户回到补问环节补全，而不是静默写入 null 日期。
    if (request.departDate() == null || request.returnDate() == null) {
      throw new InvalidPlanningRequestException("请先补充出发日期与返回日期，再确认保存");
    }
    if (request.returnDate().isBefore(request.departDate())) {
      throw new InvalidPlanningRequestException("返回日期早于出发日期，请先改正日期再确认保存");
    }
    Integer span = request.dateRangeDays();
    Integer planned = request.effectiveDays();
    if (span != null && planned != null && planned > span) {
      throw new InvalidPlanningRequestException(
          "逐日草案 " + planned + " 天超过日期区间 " + span + " 天，请先统一天数与日期区间");
    }

    // 重新装配事实（不调模型），保证载荷与草案同源、同规则。
    PlanDraft draft = planning.planFacts(request);
    Set<String> candidateIds = new LinkedHashSet<>();
    for (Attraction candidate : draft.candidates()) {
      if (candidate.id() != null) {
        candidateIds.add(candidate.id());
      }
    }

    List<String> ids = command.attractionIds();
    if (ids.isEmpty()) {
      ids = draft.dailySchedules().stream()
          .flatMap(day -> day.items().stream())
          .map(DraftDay.Item::attractionId)
          .filter(Objects::nonNull)
          .distinct()
          .toList();
    } else {
      ids = ids.stream().filter(Objects::nonNull).distinct().toList();
    }
    for (String id : ids) {
      if (!candidateIds.contains(id)) {
        throw new InvalidPlanningRequestException("景点 ID 不在本次草案候选中：" + id);
      }
    }

    // 事实一律以知识库最新记录为准，不采用客户端回传的字段值。
    List<Attraction> selected = new ArrayList<>();
    for (String id : ids) {
      Attraction fresh = attractions.byId(id)
          .orElseThrow(() -> new InvalidPlanningRequestException("景点 ID 在知识库中已不存在：" + id));
      selected.add(fresh);
    }

    List<DraftDay> days = DraftAssembler.arrange(request, selected);
    List<DailySchedule> schedules = days.stream().map(this::toSchedule).toList();

    List<String> notices = new ArrayList<>(draft.notices());
    notices.add("本载荷由用户确认后生成，尚未写入任何行程；请依次调用 POST /api/itineraries "
        + "与 PUT /api/itineraries/{id}/daily?version=<创建版本号>");
    notices.add("每日安排的 note 保留了 attractionId、来源与未知项；具体游览时间需用户自行确认");
    for (DraftCheck check : draft.checks()) {
      if ("HIGH".equals(check.severity())) {
        notices.add("[" + check.code() + "] " + check.message());
      }
    }

    String summary = "由规划草案确认生成：" + selected.size() + " 个景点 / "
        + days.size() + " 天，事实来自本地景点知识库";
    String notes = "草案检查：" + (draft.budgetEstimate() == null
        ? "无" : draft.budgetEstimate().message());
    Plan plan = new Plan(request.destination().strip(), summary, notes,
        request.departDate(), request.returnDate(), request.budget(),
        request.currencyOrDefault());

    return new MaterializedPlan(command.title().strip(), plan, schedules, draft.checks(),
        notices, draft.sources(), GENERATED_FROM);
  }

  private DailySchedule toSchedule(DraftDay day) {
    List<DailyActivity> activities = day.items().stream()
        .map(item -> new DailyActivity(null, "待定", item.name(), item.city(), item.note(),
            null, null))
        .toList();
    // id 留空，由 ItineraryService.updateDaily 的 fillIds 统一补齐。
    return new DailySchedule(null, day.date(), day.note(), activities);
  }
}
