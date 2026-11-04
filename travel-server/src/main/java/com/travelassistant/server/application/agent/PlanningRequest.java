package com.travelassistant.server.application.agent;

import java.util.List;

/**
 * 一次规划请求的结构化需求（V2 第 2 节）。
 *
 * <p>字段全部可选，缺什么就在结果里提示「未提供」，由用户补充——
 * 不猜日期、不猜人数、不猜预算。模型可以润色文案，但不能改变这些事实。
 *
 * @param destination 目的地（城市名），必填
 * @param days        计划游玩天数，可为空
 * @param preferences 偏好关键词，如「人文」「亲子」「不想走太多路」
 * @param budget      预算上限，可为空；单位由 {@code currency} 决定
 * @param currency    币种，默认 CNY
 * @param travelers   同行人数，可为空
 */
public record PlanningRequest(
    String destination,
    Integer days,
    List<String> preferences,
    java.math.BigDecimal budget,
    String currency,
    Integer travelers) {

  public PlanningRequest {
    preferences = preferences == null ? List.of() : List.copyOf(preferences);
  }

  public String currencyOrDefault() {
    return (currency == null || currency.isBlank()) ? "CNY" : currency.strip().toUpperCase();
  }
}
