package com.travelassistant.server.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.domain.itinerary.Plan;

/** 创建行程请求。 */
public record CreateItineraryRequest(
    @JsonProperty("title") String title,
    @JsonProperty("plan") Plan plan) {

  @JsonCreator
  public CreateItineraryRequest {
  }
}
