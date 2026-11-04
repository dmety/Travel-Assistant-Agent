package com.travelassistant.server.application.budget;

import com.travelassistant.server.application.itinerary.ItineraryService;
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

  public ItineraryChecks check(String itineraryId, String user) {
    return BudgetCalculator.check(itineraries.get(itineraryId, user));
  }
}
