package com.travelassistant.server.domain.itinerary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 用户自报；不代表支付、出票或平台验证。 */
public record TicketRecord(String attractionId, String product, LocalDate visitDate,
    String session, Integer travelers, BigDecimal paidTotal, Instant recordedAt, String source) {
  public static final String SOURCE = "USER_REPORTED";
}
