package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 费用（完全字段化）。
 *
 * <p>2026-11-04 补充 {@code actual}（V3 第 2 节「预算分析」）：需求要求区分
 * 「预计总额」和「实际总额」。用 {@code actual} 显式标记，而不是靠「日期是否早于今天」
 * 去猜——一张补录的旧票据和一笔提前写下的预算，日期看起来可能完全一样，
 * 猜出来的「实际支出」没人敢用。缺省为 {@code false}（预计），兼容历史数据。
 */
public record Expense(
    @JsonProperty("id") String id,
    @JsonProperty("category") String category,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("date") LocalDate date,
    @JsonProperty("note") String note,
    @JsonProperty("actual") boolean actual) {

  @JsonCreator
  public Expense {
  }

  /** 兼容不含 {@code actual} 的六参数调用（旧代码与旧测试）。 */
  public Expense(String id, String category, BigDecimal amount, String currency, LocalDate date,
      String note) {
    this(id, category, amount, currency, date, note, false);
  }
}
