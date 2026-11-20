package com.travelassistant.server.domain.ticket;

import java.math.BigDecimal;
import java.time.Instant;

/** 沙箱订单。所有状态和票据均明确属于演示，不代表真实支付或可入园门票。 */
public record DemoTicketOrder(
    String id,
    String owner,
    String attractionId,
    String attractionName,
    String productCode,
    String productName,
    int quantity,
    BigDecimal amount,
    String orderStatus,
    String paymentStatus,
    String ticketStatus,
    String ticketId,
    String paymentUri,
    String paymentEventId,
    Instant createdAt,
    Instant expiresAt,
    Instant updatedAt,
    String notice) {
}
