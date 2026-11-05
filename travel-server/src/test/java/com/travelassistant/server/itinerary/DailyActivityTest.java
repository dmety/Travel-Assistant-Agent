package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.domain.itinerary.DailyActivity;
import java.time.LocalTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 安排项的时间判定。
 *
 * <p>{@code timeInverted()} 是体检接口「结束早于开始」那一条提示的唯一依据，
 * 也是写入时拦截倒置时间的依据。它只有三行代码，但差一个 {@code ==} 或一个空判断，
 * 表现就是「用户改了半天改不进去」或「明明写反了却没人提示」——这种规则值得单独钉住，
 * 而不是靠一条走 HTTP 的用例间接体现。
 *
 * <p>六组边界里最后一组（跨午夜）目前判为倒置：产品尚未支持「23:00 出发、次日 01:00 到达」
 * 这种写法。这里把现状钉住，等产品决定是否支持时，这组用例会明确变成失败，
 * 提醒改的不只是判断，还有提示文案与前端输入约束。
 */
@DisplayName("每日安排：起止时间判定")
class DailyActivityTest {

  private static DailyActivity activity(LocalTime start, LocalTime end) {
    return new DailyActivity("a1", null, "西湖游船", "西湖", null, start, end);
  }

  static Stream<Arguments> timeRanges() {
    return Stream.of(
        Arguments.of(null, null, false, "两端都没填：不构成时间区间"),
        Arguments.of(LocalTime.of(9, 0), null, false, "只填开始：不足以判断倒置"),
        Arguments.of(null, LocalTime.of(10, 0), false, "只填结束：不足以判断倒置"),
        Arguments.of(LocalTime.of(9, 0), LocalTime.of(10, 0), false, "正常区间"),
        Arguments.of(LocalTime.of(10, 0), LocalTime.of(10, 0), false, "首尾相接，不算倒置"),
        Arguments.of(LocalTime.of(10, 0), LocalTime.of(9, 0), true, "结束早于开始"),
        Arguments.of(LocalTime.of(23, 0), LocalTime.of(1, 0), true, "跨午夜：当前判为倒置（现状）"));
  }

  @ParameterizedTest(name = "{3}")
  @MethodSource("timeRanges")
  @DisplayName("timeInverted 只在两端都填且结束早于开始时为真")
  void timeInverted(LocalTime start, LocalTime end, boolean expected, String description) {
    assertThat(activity(start, end).timeInverted()).isEqualTo(expected);
  }

  @Test
  @DisplayName("hasTimeRange 只看起止时间，不看自由文本 time")
  void freeTextTimeDoesNotCountAsRange() {
    DailyActivity vague = new DailyActivity("a1", "上午", "逛河坊街", "河坊街", null);
    assertThat(vague.hasTimeRange()).isFalse();
    assertThat(vague.timeInverted()).isFalse();
  }

  @Test
  @DisplayName("五参数构造器把起止时间留空，兼容历史数据")
  void legacyConstructorLeavesTimesNull() {
    DailyActivity legacy = new DailyActivity("a1", "09:00", "灵隐寺", "灵隐", "已预约");
    assertThat(legacy.startTime()).isNull();
    assertThat(legacy.endTime()).isNull();
    assertThat(legacy.hasTimeRange()).isFalse();
  }
}
