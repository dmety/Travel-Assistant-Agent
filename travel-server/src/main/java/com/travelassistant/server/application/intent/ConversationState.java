package com.travelassistant.server.application.intent;

import java.math.BigDecimal;
import java.util.List;

/**
 * 本轮会话里已经确认过的槽位。
 *
 * <p>和 {@link ConversationTurn} 的分工：历史交给模型「读懂指代」，
 * 这里的槽位是**已经核实过的事实**（城市是知识库里真实存在的、天数是校验过的），
 * 因此当这一句把城市省略掉时，可以直接拿来补，不必再让模型从字缝里猜。
 *
 * <p>只带「会跨轮沿用」的字段：不含日期与预算上限之外的临时值，
 * 避免下次说「换个地方」时把上一次的出发日期也一起粘过去。
 *
 * @param city        上一轮核实过的城市名（如「武汉」「十堰」）
 * @param days        上一轮确认的天数
 * @param travelers   上一轮确认的同行人数
 * @param budget      上一轮确认的预算上限
 * @param preferences 上一轮确认的偏好
 */
public record ConversationState(
    String city,
    Integer days,
    Integer travelers,
    BigDecimal budget,
    List<String> preferences) {

  public ConversationState {
    city = city == null || city.isBlank() ? null : city.strip();
    preferences = preferences == null ? List.of() : List.copyOf(preferences);
  }

  /** 什么都没记住：调用方不必再传。 */
  public static ConversationState empty() {
    return new ConversationState(null, null, null, null, List.of());
  }

  public boolean isEmpty() {
    return city == null && days == null && travelers == null && budget == null
        && preferences.isEmpty();
  }
}
