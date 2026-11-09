package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Plan;
import java.util.List;

/**
 * 草案转成的**行程载荷**（只读产物，不写库）。
 *
 * <p>前端拿到它之后，仍要走既有的 {@code POST /api/itineraries} 与
 * {@code PUT /api/itineraries/{id}/daily?version=...} 才真正落库。
 * 这样「Agent 不自行写库」与「用户确认后才保存」两条约束都不被绕过，
 * 版本控制也完全复用既有 {@code X-Version} / 409 语义。
 *
 * @param title          行程标题
 * @param plan           方案板块（目的地、日期、预算上限、备注）
 * @param dailySchedules 每日安排板块，每项 note 带景点 ID、来源与未知项
 * @param checks         生成时的日期/预算检查，供保存前最后确认
 * @param notices        保存前仍需注意的事项
 * @param sources        本次载荷用到的数据来源
 * @param generatedFrom  溯源标记
 */
public record MaterializedPlan(
    String title,
    Plan plan,
    List<DailySchedule> dailySchedules,
    List<DraftCheck> checks,
    List<String> notices,
    List<String> sources,
    String generatedFrom) {

  public MaterializedPlan {
    dailySchedules = dailySchedules == null ? List.of() : List.copyOf(dailySchedules);
    checks = checks == null ? List.of() : List.copyOf(checks);
    notices = notices == null ? List.of() : List.copyOf(notices);
    sources = sources == null ? List.of() : List.copyOf(sources);
  }
}
