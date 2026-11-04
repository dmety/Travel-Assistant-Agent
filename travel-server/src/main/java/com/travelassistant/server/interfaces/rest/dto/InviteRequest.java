package com.travelassistant.server.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelassistant.server.domain.itinerary.Section;
import java.util.Set;

/** 发起邀请请求：受邀账号 + 接受后授予的可编辑板块（为空表示只能查看）。 */
public record InviteRequest(
    @JsonProperty("username") String username,
    @JsonProperty("editSections") Set<Section> editSections) {
}
