package com.travelassistant.server.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.domain.itinerary.Plan;
import jakarta.validation.constraints.NotBlank;

/** 创建行程请求。标题为必填项，去空白后不得为空（缺陷 D-2）。 */
public record CreateItineraryRequest(
    @NotBlank(message = "行程标题不能为空")
    @JsonProperty("title") String title,
    @JsonProperty("plan") Plan plan) {

  @JsonCreator
  public CreateItineraryRequest {
  }
}
