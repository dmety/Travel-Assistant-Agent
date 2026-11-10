package com.travelassistant.server.application.agent;

import java.math.BigDecimal;

/**
 * 草案预算粗估：只统计**已核验且能解析出金额**的门票，不含交通、住宿与餐饮。
 *
 * <p>口径是保守的：未核验票价、占位值（「暂无」）和无法解析的票价一律计入
 * {@code unknownTicketCount}，此时结论只能是 {@code CANNOT_DETERMINE}。
 * 绝不因为「没算出钱」就说「没有超支」。
 *
 * @param currency                 币种
 * @param budgetLimit              用户填写的预算上限；未填写为 {@code null}
 * @param travelers                同行人数；未填写为 {@code null}
 * @param knownTicketSubtotal      单人已核验门票小计
 * @param totalForKnownTickets     按人数放大后的已核验门票小计；人数未知时等于单人小计
 * @param unknownTicketCount       无法计入估算的景点数
 * @param verdict                  NO_BUDGET / EXCEEDS_LIMIT / CANNOT_DETERMINE / WITHIN_KNOWN_SCOPE
 * @param message                  结论说明，含统计范围与局限
 */
public record DraftBudgetEstimate(
    String currency,
    BigDecimal budgetLimit,
    Integer travelers,
    BigDecimal knownTicketSubtotal,
    BigDecimal totalForKnownTickets,
    int unknownTicketCount,
    String verdict,
    String message) {
}
