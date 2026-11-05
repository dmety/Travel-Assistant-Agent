package com.travelassistant.server.domain.budget;

import java.math.BigDecimal;

/**
 * 分类预算合计。
 *
 * <p>带币种维度：同一类别下若混用不同币种，会拆成多行而不是相加——
 * 相加会把 100 元与 100 美元算成 200，是错误的汇总。
 */
public record CategoryTotal(String category, String currency, BigDecimal amount, int itemCount) {
}
