package com.travelassistant.server.domain.budget;

import java.util.List;

/** 行程体检结果：{@code ok} 为真表示没有需要用户处理的提示。 */
public record ItineraryChecks(String itineraryId, long version, boolean ok,
    List<CheckWarning> warnings) {
}
