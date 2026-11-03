package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/** 住宿（完全字段化）。 */
public record Lodging(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("address") String address,
    @JsonProperty("checkIn") LocalDate checkIn,
    @JsonProperty("checkOut") LocalDate checkOut,
    @JsonProperty("roomType") String roomType,
    @JsonProperty("note") String note) {

  @JsonCreator
  public Lodging {
  }
}
