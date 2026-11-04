package com.travelassistant.server.domain.budget;

import java.math.BigDecimal;

/** 单一币种下的费用合计。多币种场景不做汇率换算，各币种分别成行。 */
public record CurrencyTotal(String currency, BigDecimal amount, int itemCount) {
}
