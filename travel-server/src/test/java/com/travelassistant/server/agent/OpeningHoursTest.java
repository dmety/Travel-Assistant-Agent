package com.travelassistant.server.agent;
import static org.assertj.core.api.Assertions.assertThat;
import com.travelassistant.server.application.agent.OpeningHours;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class OpeningHoursTest {
  static final String CRANE="4月1日-10月31日（旺季）每日08:30-18:00；11月1日-次年3月31日（淡季）每日08:30-17:00";
  @Test void craneSeasonUsesTravelDateAndInclusiveBoundaries() {
    for(String date:new String[]{"2026-04-01","2026-10-31"}) assertThat(OpeningHours.evaluate(CRANE,LocalDate.parse(date)).windows().getFirst().endMinute()).isEqualTo(1080);
    for(String date:new String[]{"2026-11-01","2026-12-31","2027-01-01","2027-03-31"}) assertThat(OpeningHours.evaluate(CRANE,LocalDate.parse(date)).windows().getFirst().endMinute()).isEqualTo(1020);
  }
  @Test void missingDateDoesNotUseToday() { assertThat(OpeningHours.evaluate(CRANE,null).status()).isEqualTo(OpeningHours.Status.UNKNOWN); }
  @Test void weeklyClosureComesFromText() {
    String raw="每周一闭馆，周二至周日09:00-17:00";
    assertThat(OpeningHours.evaluate(raw,LocalDate.of(2026,11,23)).status()).isEqualTo(OpeningHours.Status.CLOSED);
    assertThat(OpeningHours.evaluate(raw,LocalDate.of(2026,11,24)).status()).isEqualTo(OpeningHours.Status.OPEN);
  }
  @Test void weeklyClosureCanOverrideExplicitDailyHours() {
    assertThat(OpeningHours.evaluate("每日09:00-17:00；每周一闭馆",LocalDate.of(2026,11,23)).status()).isEqualTo(OpeningHours.Status.CLOSED);
  }
  @Test void splitHoursWaitUntilAfternoonInsteadOfCountingClosure() {
    var result=OpeningHours.evaluate("每日09:00-12:00、14:00-17:00",LocalDate.of(2026,11,24));
    assertThat(result.windows()).hasSize(2);
    assertThat(result.findStart(11*60,150).orElseThrow()).isEqualTo(14*60);
    assertThat(result.findStart(11*60,240).isEmpty()).isTrue();
    assertThat(OpeningHours.covers("09:00-12:00、14:00-17:00",LocalDate.of(2026,11,24),11*60+30,60)).isFalse();
  }
  @Test void datesAndWeekdaysMustBothMatch() {
    assertThat(OpeningHours.evaluate("11月1日-3月31日周二至周日09:00-17:00",LocalDate.of(2026,11,23)).status()).isEqualTo(OpeningHours.Status.UNKNOWN);
    assertThat(OpeningHours.evaluate("11月1日-3月31日周二至周日09:00-17:00",LocalDate.of(2026,11,24)).status()).isEqualTo(OpeningHours.Status.OPEN);
  }
  @Test void announcementsAndUnparsedExceptionsStayUnknown() {
    for(String text:new String[]{"09:00-17:00，节假日另行公告","09:00-17:00，以当天公告为准","冬季09:00-17:00","4月31日-10月31日09:00-17:00","09:00-17:00；周一活动时间不定"}) assertThat(OpeningHours.evaluate(text,LocalDate.of(2026,11,23)).status()).isEqualTo(OpeningHours.Status.UNKNOWN);
  }
  @Test void overnightMealUsesThePreviousDaysApplicableRule() {
    assertThat(OpeningHours.covers("每日22:00-02:00",LocalDate.of(2026,11,24),60,60)).isTrue();
    assertThat(OpeningHours.covers("周一闭馆；周二至周日22:00-02:00",LocalDate.of(2026,11,24),60,60)).isFalse();
  }
}
