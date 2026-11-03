package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 费用（完全字段化）。 */
public record Expense(
    @JsonProperty("id") String id,
    @JsonProperty("category") String category,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("date") LocalDate date,
    @JsonProperty("note") String note) {

  @JsonCreator
  public Expense {
  }
}
