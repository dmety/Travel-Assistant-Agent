package com.travelassistant.server.domain.ticket;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 一个票种。
 *
 * <p>价格允许为空：官方页面常常只写「旺季 60 元」这类需要现场确认的表述，或者根本没有
 * 公布价格。未知就保持未知，不要补成 0——0 元会让预算汇总把「不知道」算成「免费」。
 *
 * @param price    票价，未知为 {@code null}
 * @param currency 币种，未知为 {@code null}
 * @param priceNote 价格说明，如「旺季 60 元 / 淡季 40 元」
 */
public record TicketType(
    @JsonProperty("name") String name,
    @JsonProperty("price") BigDecimal price,
    @JsonProperty("currency") String currency,
    @JsonProperty("priceNote") String priceNote) {

  @JsonCreator
  public TicketType {
  }

  /** 是否有确定的票价。 */
  public boolean hasPrice() {
    return price != null;
  }
}
