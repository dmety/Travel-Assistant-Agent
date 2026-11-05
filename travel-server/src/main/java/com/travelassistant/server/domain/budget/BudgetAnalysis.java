package com.travelassistant.server.domain.budget;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算分析结果（V3 第 2 节）。
 *
 * <p>所有金额都在 {@code currency} 这一个口径下计算。存在多币种且上限币种与费用
 * 主币种不一致时，{@code comparable} 为 {@code false}、{@code remaining} 为 {@code null}——
 * 拿 5000 元的上限去减 800 美元的支出，得出的差额是错的，不如明确说「算不了」。
 *
 * @param currency      分析口径币种（费用主币种）
 * @param limit         预算上限，未设定时为 {@code null}
 * @param estimatedTotal 预计总额（全部已填金额的费用，含已发生）
 * @param plannedTotal  仍属预计的部分（{@code actual=false}）
 * @param actualTotal   已发生的部分（{@code actual=true}）
 * @param remaining     上限 − 预计总额；未设上限或口径不一致时为 {@code null}
 * @param comparable    上限与费用是否同一币种、能否直接比较
 * @param overLimit     预计是否已超上限
 * @param overBy        超出金额；未超支或不可比时为 {@code null}
 * @param travelers     人均分摊人数（创建者 + 成员，或调用方显式指定）
 * @param perPerson     人均预计费用
 * @param perPersonActual 人均实际费用
 * @param warnings      超支、未设上限等提示
 * @param notes         口径说明
 */
public record BudgetAnalysis(
    String currency,
    BigDecimal limit,
    BigDecimal estimatedTotal,
    BigDecimal plannedTotal,
    BigDecimal actualTotal,
    BigDecimal remaining,
    boolean comparable,
    boolean overLimit,
    BigDecimal overBy,
    int travelers,
    BigDecimal perPerson,
    BigDecimal perPersonActual,
    List<CheckWarning> warnings,
    List<String> notes) {
}
