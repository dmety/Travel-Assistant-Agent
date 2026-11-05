/**
 * 预算与行程体检的纯计算规则：{@link com.travelassistant.server.domain.budget.BudgetCalculator}
 * 负责按币种、类别汇总费用并给出体检提示，不依赖 Spring 与文件系统。
 *
 * <p>2026-11-04 起投入使用（V1 第 5 节「预算与时间检查」）。
 */
package com.travelassistant.server.domain.budget;
