package com.travelassistant.server.application.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 一次规划请求的结构化需求（V2 第 2 节，2026-11-08 补日期字段）。
 *
 * <p>字段全部可选，缺什么就在结果里提示「未提供」并生成结构化补问——
 * 不猜日期、不猜人数、不猜预算。模型可以润色文案，但不能改变这些事实。
 *
 * <p>{@code departDate} / {@code returnDate} 是「基础业务闭环」新增的两个字段：
 * 没有具体日期就无法生成带日期的逐日草案，也无法做日期越界检查。
 * 老客户端不传这两个键时反序列化为 {@code null}，走补问路径而不是报错。
 *
 * @param destination 目的地（城市名），必填
 * @param days        计划游玩天数，可为空
 * @param preferences 偏好关键词，如「人文」「亲子」「不想走太多路」
 * @param budget      预算上限，可为空；单位由 {@code currency} 决定
 * @param currency    币种，默认 CNY
 * @param travelers   同行人数，可为空
 * @param departDate  出发日期，可为空
 * @param returnDate  返回日期，可为空
 */
public record PlanningRequest(
    @JsonProperty("destination") String destination,
    @JsonProperty("days") Integer days,
    @JsonProperty("preferences") List<String> preferences,
    @JsonProperty("budget") BigDecimal budget,
    @JsonProperty("currency") String currency,
    @JsonProperty("travelers") Integer travelers,
    @JsonProperty("departDate") LocalDate departDate,
    @JsonProperty("returnDate") LocalDate returnDate) {

  @JsonCreator
  public PlanningRequest {
    preferences = preferences == null ? List.of() : List.copyOf(preferences);
  }

  /** 兼容不含日期的六参数调用（旧代码、测试与文档示例）。 */
  public PlanningRequest(String destination, Integer days, List<String> preferences,
      BigDecimal budget, String currency, Integer travelers) {
    this(destination, days, preferences, budget, currency, travelers, null, null);
  }

  public String currencyOrDefault() {
    return (currency == null || currency.isBlank()) ? "CNY" : currency.strip().toUpperCase();
  }

  /** 日期区间按含首尾计算的天数；日期不全或倒置时返回 {@code null}。 */
  public Integer dateRangeDays() {
    if (departDate == null || returnDate == null || returnDate.isBefore(departDate)) {
      return null;
    }
    long span = ChronoUnit.DAYS.between(departDate, returnDate) + 1;
    return span > Integer.MAX_VALUE ? null : (int) span;
  }

  /**
   * 生成逐日草案用的天数：显式 {@code days} 优先；没填天数但日期齐全时由区间换算。
   * 这只是确定性换算，不是猜测——换算结果会在 notices 里注明来源。
   */
  public Integer effectiveDays() {
    if (days != null && days > 0) {
      return days;
    }
    return dateRangeDays();
  }
}
