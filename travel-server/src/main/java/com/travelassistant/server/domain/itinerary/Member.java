package com.travelassistant.server.domain.itinerary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

/** 行程成员：对哪些板块有编辑权。查看权默认为任意成员共有。 */
public record Member(
    @JsonProperty("username") String username,
    @JsonProperty("editSections") Set<Section> editSections) {

  @JsonCreator
  public Member {
    editSections = (editSections == null) ? Set.of() : Set.copyOf(editSections);
  }
}
