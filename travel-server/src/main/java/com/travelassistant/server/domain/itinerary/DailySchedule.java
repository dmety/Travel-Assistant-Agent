package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/** 某一天的安排（完全字段化）。 */
public record DailySchedule(
    @JsonProperty("id") String id,
    @JsonProperty("date") LocalDate date,
    @JsonProperty("note") String note,
    @JsonProperty("activities") List<DailyActivity> activities) {

  @JsonCreator
  public DailySchedule {
    activities = (activities == null) ? List.of() : activities;
  }
}
