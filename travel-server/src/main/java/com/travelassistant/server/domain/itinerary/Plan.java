package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 方案（完全字段化）。 */
public record Plan(
    @JsonProperty("destination") String destination,
    @JsonProperty("summary") String summary,
    @JsonProperty("notes") String notes) {

  @JsonCreator
  public Plan {
  }
}
