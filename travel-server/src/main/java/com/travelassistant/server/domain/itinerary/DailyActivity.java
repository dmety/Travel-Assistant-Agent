package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 每日安排中的单项活动（id 由服务端在落库时补全）。 */
public record DailyActivity(
    @JsonProperty("id") String id,
    @JsonProperty("time") String time,
    @JsonProperty("title") String title,
    @JsonProperty("location") String location,
    @JsonProperty("note") String note) {

  @JsonCreator
  public DailyActivity {
  }
}
