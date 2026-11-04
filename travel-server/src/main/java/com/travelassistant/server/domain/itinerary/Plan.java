package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 方案（完全字段化）。
 *
 * <p>2026-11-04 补充 {@code departDate}/{@code returnDate}：V1 第 5 节「预算与时间检查」
 * 需要记录出发和返回时间才能判断「返回早于出发」。两字段允许为空以兼容历史数据——
 * 老 JSON 没有这两个键时反序列化为 {@code null}，由体检接口提示「未填写」，
 * 而不是让整份行程读取失败。
 *
 * <p>同日补充 {@code budgetLimit}/{@code budgetCurrency}（V3 第 2 节「预算分析」）：
 * 「与预算上限的差额」需要先有上限。上限也允许为空，此时预算分析只说「未设定上限」，
 * 不拿 0 冒充一个用户从没填过的数——0 意味着「一分不花」，与「还没填」是两件事。
 */
public record Plan(
    @JsonProperty("destination") String destination,
    @JsonProperty("summary") String summary,
    @JsonProperty("notes") String notes,
    @JsonProperty("departDate") LocalDate departDate,
    @JsonProperty("returnDate") LocalDate returnDate,
    @JsonProperty("budgetLimit") BigDecimal budgetLimit,
    @JsonProperty("budgetCurrency") String budgetCurrency) {

  @JsonCreator
  public Plan {
  }

  /** 兼容不含日期与预算上限的三参数调用（旧代码与旧测试）。 */
  public Plan(String destination, String summary, String notes) {
    this(destination, summary, notes, null, null, null, null);
  }

  /** 兼容含日期、不含预算上限的五参数调用（V1 轮次的代码与测试）。 */
  public Plan(String destination, String summary, String notes, LocalDate departDate,
      LocalDate returnDate) {
    this(destination, summary, notes, departDate, returnDate, null, null);
  }

  /** 空方案：创建行程未提供 plan 时的兜底。 */
  public static Plan empty() {
    return new Plan(null, null, null, null, null, null, null);
  }

  /** 起止日期是否都已填写。 */
  public boolean hasDateRange() {
    return departDate != null && returnDate != null;
  }

  /** 是否设定了预算上限。 */
  public boolean hasBudgetLimit() {
    return budgetLimit != null;
  }
}
