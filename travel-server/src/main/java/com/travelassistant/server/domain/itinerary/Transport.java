package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/** 交通（完全字段化）。 */
public record Transport(
    @JsonProperty("id") String id,
    @JsonProperty("mode") String mode,
    @JsonProperty("from") String from,
    @JsonProperty("to") String to,
    @JsonProperty("date") LocalDate date,
    @JsonProperty("departTime") String departTime,
    @JsonProperty("arriveTime") String arriveTime,
    @JsonProperty("flightNo") String flightNo,
    @JsonProperty("seat") String seat,
    @JsonProperty("note") String note) {

  @JsonCreator
  public Transport {
  }
}
