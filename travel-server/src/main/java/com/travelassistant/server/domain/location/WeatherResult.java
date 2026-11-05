package com.travelassistant.server.domain.location;

import java.time.Instant;
import java.util.List;

/**
 * 天气预报结果。
 *
 * <p>高德天气接口给的是**预报**，不是实时观测；展示时必须说明这一点，
 * 不能用「当地天气」这种说法让用户以为是此刻的实况。
 *
 * @param city      城市名
 * @param adcode    行政区划代码（高德接口要求按 adcode 查询）
 * @param reportTime 数据发布时间
 * @param days      逐日预报（今天起最多 4 天）
 */
public record WeatherResult(
    String city,
    String adcode,
    String reportTime,
    List<DayWeather> days,
    String source,
    Instant queriedAt) {

  public WeatherResult {
    days = days == null ? List.of() : List.copyOf(days);
  }

  /** 一天的预报。 */
  public record DayWeather(
      String date,
      String dayWeather,
      String nightWeather,
      String dayTemp,
      String nightTemp,
      String dayWind,
      String dayPower,
      String nightWind,
      String nightPower) {
  }
}
