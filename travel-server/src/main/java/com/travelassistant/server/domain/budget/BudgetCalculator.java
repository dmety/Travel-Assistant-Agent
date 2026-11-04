package com.travelassistant.server.domain.budget;

import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Lodging;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Transport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 预算汇总与行程体检的纯计算规则（不含 IO 与 Spring，便于单独验证）。
 *
 * <p>两个刻意的取舍：
 * <ul>
 *   <li><b>不跨币种相加</b>：100 元 + 100 美元 = 200 是错的。不同币种分别成行，并给出说明，
 *       汇率换算需要外部数据源与时间点，属于 V2 之后的能力。</li>
 *   <li><b>不静默丢弃脏数据</b>：金额为空的条目进「待填写」，为负的条目照实计入合计
 *       并在说明里点出——写入时已拦截，能出现的只可能是历史数据。</li>
 * </ul>
 */
public final class BudgetCalculator {

  /** 未指定币种时使用的默认币种。 */
  public static final String DEFAULT_CURRENCY = "CNY";

  /** 未填写类别时使用的占位类别。 */
  public static final String UNCATEGORIZED = "未分类";

  private BudgetCalculator() {
  }

  /** 汇总预算：各币种合计、各分类合计、待填写条目与口径说明。 */
  public static BudgetSummary summarize(Itinerary itinerary) {
    Map<String, BigDecimal> amountByCurrency = new TreeMap<>();
    Map<String, Integer> countByCurrency = new TreeMap<>();
    Map<String, BigDecimal> amountByCategory = new LinkedHashMap<>();
    Map<String, Integer> countByCategory = new LinkedHashMap<>();
    List<PendingExpense> pending = new ArrayList<>();
    int negative = 0;

    for (Expense e : expensesOf(itinerary)) {
      String currency = currencyOf(e);
      String category = categoryOf(e);
      BigDecimal amount = e.amount();
      if (amount == null) {
        pending.add(new PendingExpense(e.id(), category, e.note()));
        continue;
      }
      if (amount.signum() < 0) {
        negative++;
      }
      amountByCurrency.merge(currency, amount, BigDecimal::add);
      countByCurrency.merge(currency, 1, Integer::sum);
      String key = categoryKey(currency, category);
      amountByCategory.merge(key, amount, BigDecimal::add);
      countByCategory.merge(key, 1, Integer::sum);
    }

    String primary = pickPrimaryCurrency(countByCurrency);
    List<CurrencyTotal> totals = new ArrayList<>();
    totals.add(new CurrencyTotal(primary, scale(amountByCurrency.get(primary)),
        countByCurrency.getOrDefault(primary, 0)));
    for (String currency : amountByCurrency.keySet()) {
      if (!currency.equals(primary)) {
        totals.add(new CurrencyTotal(currency, scale(amountByCurrency.get(currency)),
            countByCurrency.getOrDefault(currency, 0)));
      }
    }

    List<CategoryTotal> byCategory = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : amountByCategory.entrySet()) {
      String[] parts = entry.getKey().split("\u0000", 2);
      byCategory.add(new CategoryTotal(parts[1], parts[0], scale(entry.getValue()),
          countByCategory.getOrDefault(entry.getKey(), 0)));
    }
    byCategory.sort(Comparator.comparing(CategoryTotal::currency)
        .thenComparing(CategoryTotal::category));

    List<String> currencies = new ArrayList<>(new TreeSet<>(amountByCurrency.keySet()));
    boolean mixed = currencies.size() > 1;
    int itemCount = countByCurrency.values().stream().mapToInt(Integer::intValue).sum();

    List<String> notes = new ArrayList<>();
    if (mixed) {
      notes.add("存在 " + currencies.size() + " 种币种（" + String.join("、", currencies)
          + "），本汇总未做汇率换算，请按币种分别核对，不要把不同币种的金额直接相加");
    }
    if (negative > 0) {
      notes.add("有 " + negative + " 笔费用金额为负数，写入时应被拒绝，疑为历史数据，已照实计入合计");
    }
    if (!pending.isEmpty()) {
      notes.add("有 " + pending.size() + " 笔费用未填写金额，已标记为待填写，未计入合计");
    }

    return new BudgetSummary(primary, List.copyOf(totals), List.copyOf(byCategory), itemCount,
        pending.size(), List.copyOf(pending), List.copyOf(currencies), mixed, List.copyOf(notes));
  }

  /** 行程体检：日期缺失/倒置、费用待填写、多币种、各板块日期越界。 */
  public static ItineraryChecks check(Itinerary itinerary) {
    List<CheckWarning> warnings = new ArrayList<>();
    Plan plan = itinerary.plan();
    LocalDate depart = plan == null ? null : plan.departDate();
    LocalDate ret = plan == null ? null : plan.returnDate();

    if (depart == null) {
      warnings.add(new CheckWarning("DEPART_DATE_MISSING", "plan.departDate", "未填写出发日期"));
    }
    if (ret == null) {
      warnings.add(new CheckWarning("RETURN_DATE_MISSING", "plan.returnDate", "未填写返回日期"));
    }
    boolean inverted = depart != null && ret != null && ret.isBefore(depart);
    if (inverted) {
      warnings.add(new CheckWarning("RETURN_BEFORE_DEPART", "plan.returnDate",
          "返回日期 " + ret + " 早于出发日期 " + depart));
    }
    // 区间倒置时不再做越界判断，否则每条安排都会再报一次，掩盖真正的问题
    boolean ranged = depart != null && ret != null && !inverted;

    List<Expense> expenses = expensesOf(itinerary);
    long pending = expenses.stream().filter(e -> e.amount() == null).count();
    if (pending > 0) {
      warnings.add(new CheckWarning("EXPENSE_AMOUNT_MISSING", "expenses",
          "有 " + pending + " 笔费用未填写金额"));
    }
    long negative = expenses.stream()
        .filter(e -> e.amount() != null && e.amount().signum() < 0).count();
    if (negative > 0) {
      warnings.add(new CheckWarning("EXPENSE_NEGATIVE", "expenses",
          "有 " + negative + " 笔费用金额为负数"));
    }
    if (expenses.isEmpty()) {
      warnings.add(new CheckWarning("NO_EXPENSE", "expenses", "尚未记录任何费用，预算为 0"));
    }
    if (summarize(itinerary).mixedCurrency()) {
      warnings.add(new CheckWarning("MIXED_CURRENCY", "expenses",
          "存在多种币种，未做汇率换算，预算总额按币种分别给出"));
    }

    if (ranged) {
      for (int i = 0; i < itinerary.dailySchedules().size(); i++) {
        DailySchedule day = itinerary.dailySchedules().get(i);
        if (outside(day.date(), depart, ret)) {
          warnings.add(new CheckWarning("DAILY_OUT_OF_RANGE", "dailySchedules[" + i + "].date",
              "每日安排 " + day.date() + " 超出旅行时间范围 " + depart + " ~ " + ret));
        }
      }
      for (int i = 0; i < itinerary.transports().size(); i++) {
        Transport t = itinerary.transports().get(i);
        if (outside(t.date(), depart, ret)) {
          warnings.add(new CheckWarning("TRANSPORT_OUT_OF_RANGE", "transports[" + i + "].date",
              "交通日期 " + t.date() + " 超出旅行时间范围 " + depart + " ~ " + ret));
        }
      }
      for (int i = 0; i < itinerary.lodgings().size(); i++) {
        Lodging l = itinerary.lodgings().get(i);
        if (outside(l.checkIn(), depart, ret) || outside(l.checkOut(), depart, ret)) {
          warnings.add(new CheckWarning("LODGING_OUT_OF_RANGE", "lodgings[" + i + "]",
              "住宿「" + describe(l.name()) + "」日期超出旅行时间范围 " + depart + " ~ " + ret));
        }
      }
    }

    for (int i = 0; i < itinerary.lodgings().size(); i++) {
      Lodging l = itinerary.lodgings().get(i);
      if (l.checkIn() != null && l.checkOut() != null && l.checkOut().isBefore(l.checkIn())) {
        warnings.add(new CheckWarning("LODGING_PERIOD_INVALID", "lodgings[" + i + "]",
            "住宿「" + describe(l.name()) + "」离店日期 " + l.checkOut()
                + " 早于入住日期 " + l.checkIn()));
      }
    }

    return new ItineraryChecks(itinerary.id(), itinerary.version(), warnings.isEmpty(),
        List.copyOf(warnings));
  }

  // ---- 内部辅助 ----

  private static List<Expense> expensesOf(Itinerary itinerary) {
    List<Expense> expenses = itinerary.expenses();
    return expenses == null ? List.of() : expenses;
  }

  private static boolean outside(LocalDate date, LocalDate from, LocalDate to) {
    return date != null && (date.isBefore(from) || date.isAfter(to));
  }

  private static String categoryKey(String currency, String category) {
    return currency + "\u0000" + category;
  }

  /** 币种规范：去空白、转大写；缺失按默认币种。 */
  private static String currencyOf(Expense e) {
    String c = e.currency();
    return (c == null || c.isBlank()) ? DEFAULT_CURRENCY : c.strip().toUpperCase(Locale.ROOT);
  }

  /** 类别规范：去空白；缺失归入「未分类」，避免出现空标题行。 */
  private static String categoryOf(Expense e) {
    String c = e.category();
    return (c == null || c.isBlank()) ? UNCATEGORIZED : c.strip();
  }

  /** 条目最多的币种优先；平票时默认币种优先，再按字典序，保证结果稳定可断言。 */
  private static String pickPrimaryCurrency(Map<String, Integer> countByCurrency) {
    List<String> ordered = new ArrayList<>(countByCurrency.keySet());
    if (ordered.isEmpty()) {
      return DEFAULT_CURRENCY;
    }
    ordered.sort(Comparator
        .comparingInt((String c) -> countByCurrency.getOrDefault(c, 0)).reversed()
        .thenComparing((String c) -> c.equals(DEFAULT_CURRENCY) ? 0 : 1)
        .thenComparing(Comparator.naturalOrder()));
    return ordered.get(0);
  }

  private static BigDecimal scale(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
  }

  private static String describe(String text) {
    return (text == null || text.isBlank()) ? "未命名" : text;
  }
}
