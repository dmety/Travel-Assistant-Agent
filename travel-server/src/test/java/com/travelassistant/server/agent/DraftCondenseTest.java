package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.agent.DraftAssembler;
import com.travelassistant.server.application.agent.DraftCheck;
import com.travelassistant.server.application.agent.DraftDay;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.domain.attraction.Attraction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编排层收敛：用户要 10 天、本地只查到 4 个景点时，只给 2 天有内容的草案，
 * 剩下 8 天合并成一条说明，而不是刷八行「暂无候选」。
 *
 * <p>这些断言锁住的不是排版，而是编排层的输出纪律：不硬凑空天、不逐天重复全局提示、
 * 确认保存用的 {@link DraftAssembler#arrange} 与展示用的
 * {@link DraftAssembler#assemble} 是同一套收敛规则。
 */
@DisplayName("编排层草案收敛")
class DraftCondenseTest {

  private static final LocalDate DEPART = LocalDate.of(2026, 11, 21);

  private static PlanningRequest request(int days, LocalDate depart, LocalDate ret) {
    return new PlanningRequest("十堰", days, List.of(), new BigDecimal("2000"), "CNY", 2,
        depart, ret);
  }

  private static List<Attraction> spots(int count) {
    List<Attraction> out = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      out.add(new Attraction("t-sy-" + i, "十堰景点" + i, "十堰", "湖北省", "风景名胜",
          "4A", "4.5", "08:00-17:00", "60 元", "十堰市", null, null, null, "说明",
          32.0, 110.0, "KB", List.of("KB"), "KNOWN", "KNOWN", "GCJ-02"));
    }
    return out;
  }

  private static List<String> codes(DraftAssembler.Draft draft) {
    return draft.checks().stream().map(DraftCheck::code).toList();
  }

  @Test
  @DisplayName("10 天请求只有 4 个候选：只排 2 天，收敛说明只出现一次")
  void tenDayRequestRendersOnlySupportedDays() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(request(10, null, null), spots(4));

    assertThat(draft.dailySchedules()).hasSize(2);
    assertThat(draft.dailySchedules()).allSatisfy(day ->
        assertThat(day.items()).hasSize(DraftAssembler.MAX_PER_DAY));
    assertThat(codes(draft)).contains(DraftAssembler.TRIMMED_CHECK)
        .doesNotContain("NO_DAILY_SCHEDULE");
    List<DraftCheck> trimmed = draft.checks().stream()
        .filter(c -> DraftAssembler.TRIMMED_CHECK.equals(c.code())).toList();
    assertThat(trimmed).hasSize(1);
    assertThat(trimmed.get(0).message()).contains("其余 8 天未列空天");
    assertThat(draft.notices()).anyMatch(n -> n.contains("没有硬凑空天"));
  }

  @Test
  @DisplayName("逐日 note 不再重复全局日期提示，也不出现「暂无可用候选」")
  void dailyNotesAreShortAndNotRepeated() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(request(10, null, null), spots(4));

    for (DraftDay day : draft.dailySchedules()) {
      assertThat(day.note()).doesNotContain("缺少有效的出发/返回日期")
          .doesNotContain("本日暂无可用候选景点")
          .doesNotContain("补全日期后重新生成草案");
    }
    // 全局提示仍然保留在 checks 里，只是不再每天重复
    assertThat(codes(draft)).contains("DATE_INCOMPLETE");
  }

  @Test
  @DisplayName("日期齐全时保留日历，且同样不生成空天")
  void datesAreKeptForTheDaysThatExist() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(
        request(10, DEPART, DEPART.plusDays(9)), spots(4));

    assertThat(draft.dailySchedules()).hasSize(2);
    assertThat(draft.dailySchedules().get(0).date()).isEqualTo(DEPART);
    assertThat(draft.dailySchedules().get(1).date()).isEqualTo(DEPART.plusDays(1));
    assertThat(codes(draft)).doesNotContain("DRAFT_DAY_OUT_OF_RANGE");
  }

  @Test
  @DisplayName("请求天数与候选匹配时不加收敛提示")
  void matchingDaysAreNotTrimmed() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(
        request(2, DEPART, DEPART.plusDays(1)), spots(4));

    assertThat(draft.dailySchedules()).hasSize(2);
    assertThat(codes(draft)).doesNotContain(DraftAssembler.TRIMMED_CHECK);
  }

  @Test
  @DisplayName("没有候选时一份空天都不生成")
  void noCandidatesProducesNoEmptyDays() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(request(5, DEPART, DEPART.plusDays(4)),
        List.of());

    assertThat(draft.dailySchedules()).isEmpty();
    assertThat(codes(draft)).contains("NO_DAILY_SCHEDULE", DraftAssembler.TRIMMED_CHECK);
  }

  @Test
  @DisplayName("确认保存走同一个收敛方法，正式行程里不会写进空天")
  void materializationUsesTheSameCondensation() {
    PlanningRequest tenDays = request(10, DEPART, DEPART.plusDays(9));
    List<DraftDay> days = DraftAssembler.arrange(tenDays, spots(4));

    assertThat(days).hasSize(2);
    assertThat(days).allSatisfy(day -> assertThat(day.items()).isNotEmpty());
  }

  @Test
  @DisplayName("超大天数仍受上限与候选数双重约束")
  void hugeDayRequestIsClamped() {
    DraftAssembler.Draft draft = DraftAssembler.assemble(request(999, null, null), spots(4));

    assertThat(draft.dailySchedules()).hasSize(2);
    assertThat(codes(draft)).contains("DAYS_TOO_LARGE", DraftAssembler.TRIMMED_CHECK);
  }
}
