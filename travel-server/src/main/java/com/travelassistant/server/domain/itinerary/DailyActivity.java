package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalTime;

/**
 * 每日安排中的单项活动（id 由服务端在落库时补全）。
 *
 * <p>2026-11-04 补充 {@code startTime}/{@code endTime}：V2 第 3 节「详细时间检查」需要
 * 判断「结束早于开始」「安排超出旅行时间范围」和「同日安排重叠」。两字段可空以兼容
 * 历史数据——老 JSON 没有这两个键时反序列化为 {@code null}，体检接口提示「未填写」，
 * 而不是让整份行程读取失败。
 *
 * <p>时间用 {@link LocalTime}（不带时区）。需求要求「按统一时区解析」，本项目所有日期
 * 时间均为目的地当地钟面时间，不做时区换算；跨时区行程需在备注里注明当地时间。
 * 原有的自由文本字段 {@code time}（如「上午」「9 点前」）继续保留，供无法确定
 * 具体时刻的安排使用。
 */
public record DailyActivity(
    @JsonProperty("id") String id,
    @JsonProperty("time") String time,
    @JsonProperty("title") String title,
    @JsonProperty("location") String location,
    @JsonProperty("note") String note,
    @JsonProperty("startTime") LocalTime startTime,
    @JsonProperty("endTime") LocalTime endTime,
    @JsonProperty("ticketRecord") TicketRecord ticketRecord) {

  @JsonCreator
  public DailyActivity {
  }

  public DailyActivity(String id, String time, String title, String location, String note,
      LocalTime startTime, LocalTime endTime) {
    this(id, time, title, location, note, startTime, endTime, null);
  }

  /** 兼容只有自由文本时间（{@code time}）的五参数调用（旧代码与旧测试）。 */
  public DailyActivity(String id, String time, String title, String location, String note) {
    this(id, time, title, location, note, null, null);
  }

  /** 起止时间是否都已填写。 */
  public boolean hasTimeRange() {
    return startTime != null && endTime != null;
  }

  /** 结束时间是否早于开始时间（任一为空时不算倒置）。 */
  public boolean timeInverted() {
    return hasTimeRange() && endTime.isBefore(startTime);
  }
}
