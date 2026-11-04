package com.travelassistant.server.application.budget;

import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.BudgetCalculator;
import com.travelassistant.server.domain.budget.BudgetSummary;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import org.springframework.stereotype.Service;

/**
 * 预算与体检用例。只读，不改变行程版本号。
 *
 * <p>权限沿用行程的查看权（owner 或成员），因此先经 {@link ItineraryService#get} 取聚合根，
 * 越权访问会抛 403，与读取行程详情的行为保持一致。
 */
@Service
public class BudgetService {
  private final ItineraryService itineraries;

  public BudgetService(ItineraryService itineraries) {
    this.itineraries = itineraries;
  }

  public BudgetSummary summarize(String itineraryId, String user) {
    return BudgetCalculator.summarize(itineraries.get(itineraryId, user));
  }

  /**
   * 预算分析（V3 第 2 节）：上限、预计、实际、差额、人均与超支提示。
   *
   * @param travelers 参与分摊的人数；{@code null} 时按「创建者 + 成员」计算
   */
  public BudgetAnalysis analyze(String itineraryId, String user, Integer travelers) {
    return BudgetCalculator.analyze(itineraries.get(itineraryId, user), travelers);
  }

  public ItineraryChecks check(String itineraryId, String user) {
    return BudgetCalculator.check(itineraries.get(itineraryId, user));
  }
}
