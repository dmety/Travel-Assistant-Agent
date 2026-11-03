package com.travelassistant.server.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.domain.itinerary.Section;
import java.util.Set;

/** 添加/更新成员请求：指定成员用户名与其可编辑的板块集合。 */
public record MemberRequest(
    @JsonProperty("username") String username,
    @JsonProperty("editSections") Set<Section> editSections) {

  @JsonCreator
  public MemberRequest {
  }
}
