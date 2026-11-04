package com.travelassistant.server.application.agent;

/**
 * 综合决策建议的请求参数（V3 第 3 节）。
 *
 * <p>两个字段都可省略：不传人数则按「创建者 + 成员」分摊，不传天气开关则默认查天气
 * （前提是配了高德密钥）。
 *
 * @param travelers      参与分摊的人数，可为空
 * @param includeWeather 是否查询天气，可为空（默认 true）
 */
public record DecisionRequest(Integer travelers, Boolean includeWeather) {
}
