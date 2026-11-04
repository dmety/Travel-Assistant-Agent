package com.travelassistant.server.domain.budget;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算汇总结果。
 *
 * @param primaryCurrency 主要币种（条目最多者；无费用时为默认币种 CNY）
 * @param totals          各币种合计，主要币种排首位；**不做汇率换算**
 * @param byCategory      分类合计，按「币种 + 类别」拆分
 * @param itemCount       参与合计的费用条目数（不含待填写）
 * @param pendingCount    未填写金额的条目数
 * @param pending         未填写金额的条目明细
 * @param currencies      出现过的币种（升序）
 * @param mixedCurrency   是否存在多币种
 * @param notes           给用户看的口径说明（换算、负数、待填写）
 */
public record BudgetSummary(
    String primaryCurrency,
    List<CurrencyTotal> totals,
    List<CategoryTotal> byCategory,
    int itemCount,
    int pendingCount,
    List<PendingExpense> pending,
    List<String> currencies,
    boolean mixedCurrency,
    List<String> notes) {

  /** 主要币种下的合计金额（无费用时为 0.00）。 */
  public BigDecimal primaryTotal() {
    return totals.stream()
        .filter(t -> t.currency().equals(primaryCurrency))
        .map(CurrencyTotal::amount)
        .findFirst()
        .orElse(BigDecimal.ZERO.setScale(2));
  }
}
