package com.travelassistant.server.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatToolsWeatherWindowTest {
  @Test
  void extractsExplicitConsecutiveDryWeatherWindow() {
    assertThat(ChatTools.requestedDryWindowDays("找连续3天天气不下雨的出发时间")).isEqualTo(3);
    assertThat(ChatTools.requestedDryWindowDays("安排连续三天无雨的旅行")).isEqualTo(3);
    assertThat(ChatTools.requestedDryWindowDays("最近天气怎么样")).isZero();
  }

  @Test
  void returnsOnlyConsecutiveForecastWindowAndWarnsAboutLongerTripCoverage() {
    Map<String, Object> weather = Map.of("days", List.of(
        day("2026-11-20", "晴", "多云"),
        day("2026-11-21", "多云", "晴"),
        day("2026-11-22", "陰", "晴"),
        day("2026-11-23", "小雨", "小雨")));
    String result = ChatTools.dryWindowEvidence(weather, 3, 5);
    assertThat(result).contains("2026-11-20 至 2026-11-22")
        .contains("当前预报候选")
        .contains("行程为 5 天而天气仅覆盖 4 天")
        .contains("不能核验");
  }

  @Test
  void refusesToSelectWhenForecastIsTooShortOrContainsRain() {
    assertThat(ChatTools.dryWindowEvidence(Map.of("days", List.of(day("2026-11-20", "晴", "晴"))), 3, 3))
        .contains("少于所需 3 天").contains("不推荐出发日期");
    assertThat(ChatTools.dryWindowEvidence(Map.of("days", List.of(
        day("2026-11-20", "晴", "晴"), day("2026-11-21", "小雨", "多云"),
        day("2026-11-22", "晴", "晴"))), 3, 3))
        .contains("没有找到符合条件").contains("未推荐出发日期");
  }

  private static Map<String, Object> day(String date, String daytime, String night) {
    return Map.of("date", date, "dayWeather", daytime, "nightWeather", night);
  }
}
