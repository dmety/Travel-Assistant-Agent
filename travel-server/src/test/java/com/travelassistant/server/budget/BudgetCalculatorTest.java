package com.travelassistant.server.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.BudgetCalculator;
import com.travelassistant.server.domain.budget.BudgetSummary;
import com.travelassistant.server.domain.budget.CategoryTotal;
import com.travelassistant.server.domain.budget.CheckWarning;
import com.travelassistant.server.domain.budget.CurrencyTotal;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预算汇总与分析的纯计算规则（不启容器、不走 HTTP）。
 *
 * <p>这些规则此前只通过 {@code GET /budget}、{@code GET /budget/analysis} 间接覆盖：
 * 一个分支算错，表现是「某个字段不对」，要定位就得先排除 JSON 序列化、权限、版本号三条链路。
 * 这里直接对 {@link BudgetCalculator} 断言，失败时栈顶就是出错的那一行。
 *
 * <p>重点钉住四条口径，它们都是「看起来能跑但业务上是错的」高发区：
 * 不跨币种相加、不拿 0 冒充没填（金额与上限）、人均分摊的除零边界、金额一律两位小数。
 */
@DisplayName("预算计算规则（纯单元）")
class BudgetCalculatorTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

  private static Expense cost(String id, String category, String amount, String currency) {
    return cost(id, category, amount, currency, false);
  }

  private static Expense cost(String id, String category, String amount, String currency,
      boolean actual) {
    return new Expense(id, category, amount == null ? null : new BigDecimal(amount), currency,
        DAY, "备注", actual);
  }

  private static Itinerary trip(Plan plan, List<Member> members, Expense... expenses) {
    Itinerary itinerary = Itinerary.create("alice", "杭州三天两晚", plan);
    if (!members.isEmpty()) {
      itinerary = itinerary.withMembers(members);
    }
    return itinerary.withExpenses(List.of(expenses));
  }

  private static Member member(String username) {
    return new Member(username, Set.of(Section.DAILY));
  }

  private static boolean hasWarning(BudgetAnalysis analysis, String code) {
    return analysis.warnings().stream().map(CheckWarning::code).anyMatch(code::equals);
  }

  // ---- summarize ----

  @Test
  @DisplayName("空行程：合计 0.00 且保留两位小数，不产生任何口径说明")
  void summarizesEmptyItinerary() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of()));

    assertThat(summary.primaryCurrency()).isEqualTo(BudgetCalculator.DEFAULT_CURRENCY);
    assertThat(summary.primaryTotal()).isEqualByComparingTo("0");
    assertThat(summary.primaryTotal().scale()).isEqualTo(2);
    assertThat(summary.totals()).hasSize(1);
    assertThat(summary.totals().get(0).itemCount()).isZero();
    assertThat(summary.itemCount()).isZero();
    assertThat(summary.pendingCount()).isZero();
    assertThat(summary.currencies()).isEmpty();
    assertThat(summary.mixedCurrency()).isFalse();
    assertThat(summary.byCategory()).isEmpty();
    assertThat(summary.notes()).isEmpty();
  }

  @Test
  @DisplayName("多币种不合并：CNY 与 USD 各自成行，并说明未做汇率换算")
  void keepsCurrenciesSeparate() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of(),
        cost("e1", "餐饮", "100", "CNY"),
        cost("e2", "餐饮", "100", "USD"),
        cost("e3", "餐饮", "50", "CNY")));

    // 条目多的币种当主币种：CNY 两条 > USD 一条
    assertThat(summary.primaryCurrency()).isEqualTo("CNY");
    assertThat(summary.primaryTotal()).isEqualByComparingTo("150");
    assertThat(summary.totals())
        .extracting(CurrencyTotal::currency, t -> t.amount().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("CNY", "150.00"),
            org.assertj.core.groups.Tuple.tuple("USD", "100.00"));
    assertThat(summary.currencies()).containsExactly("CNY", "USD");
    assertThat(summary.mixedCurrency()).isTrue();
    assertThat(summary.notes()).singleElement().asString().contains("2 种币种");
    // 同类别不同币种不能合并成一行
    assertThat(summary.byCategory())
        .extracting(CategoryTotal::currency)
        .containsExactly("CNY", "USD");
  }

  @Test
  @DisplayName("类别与币种的空白被规范化：空类别归「未分类」，小写币种统一成大写")
  void normalizesBlankCategoryAndCurrency() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of(),
        cost("e1", null, "10", null),
        cost("e2", "   ", "20", "  "),
        cost("e3", " 餐饮 ", "30", "cny")));

    // 三种写法的币种都归一成 CNY，因此合并在同一个币种口径下
    assertThat(summary.primaryCurrency()).isEqualTo("CNY");
    assertThat(summary.primaryTotal()).isEqualByComparingTo("60");
    assertThat(summary.mixedCurrency()).isFalse();

    // 空类别（null 与纯空白）合并成同一行「未分类」，显式写的类别单独成行
    assertThat(summary.byCategory()).hasSize(2);
    CategoryTotal uncategorized = summary.byCategory().get(0);
    assertThat(uncategorized.category()).isEqualTo(BudgetCalculator.UNCATEGORIZED);
    assertThat(uncategorized.currency()).isEqualTo("CNY");
    assertThat(uncategorized.amount()).isEqualByComparingTo("30");
    assertThat(uncategorized.itemCount()).isEqualTo(2);

    CategoryTotal named = summary.byCategory().get(1);
    assertThat(named.category()).isEqualTo("餐饮");
    assertThat(named.currency()).isEqualTo("CNY");
    assertThat(named.amount()).isEqualByComparingTo("30");
    assertThat(named.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("金额按两位小数四舍五入，不把 100.005 原样透出")
  void roundsAmountToTwoDecimals() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of(),
        cost("e1", "交通", "100.005", "CNY")));

    assertThat(summary.primaryTotal()).isEqualByComparingTo("100.01");
    assertThat(summary.primaryTotal().scale()).isEqualTo(2);
  }

  @Test
  @DisplayName("未填金额的条目进「待填写」，不计入合计也不被静默丢弃")
  void pendingExpenseIsReportedNotDropped() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of(),
        cost("e1", "门票", null, "CNY"),
        cost("e2", "交通", "120", "CNY")));

    assertThat(summary.itemCount()).isEqualTo(1);
    assertThat(summary.pendingCount()).isEqualTo(1);
    assertThat(summary.pending()).singleElement()
        .satisfies(p -> assertThat(p.id()).isEqualTo("e1"));
    assertThat(summary.primaryTotal()).isEqualByComparingTo("120");
    assertThat(summary.notes()).anySatisfy(note -> assertThat(note).contains("未填写金额"));
  }

  // ---- analyze ----

  @Test
  @DisplayName("人均按「创建者 + 成员」分摊")
  void analyzeDividesByTravelers() {
    BudgetAnalysis analysis = BudgetCalculator.analyze(trip(
        new Plan("杭州", "西湖", "备注", DAY, DAY.plusDays(2), new BigDecimal("1000"), "CNY"),
        List.of(member("bob"), member("carol")),
        cost("e1", "交通", "600", "CNY")), null);

    assertThat(analysis.travelers()).isEqualTo(3);
    assertThat(analysis.perPerson()).isEqualByComparingTo("200");
    assertThat(analysis.perPerson().scale()).isEqualTo(2);
    assertThat(analysis.remaining()).isEqualByComparingTo("400");
    assertThat(analysis.comparable()).isTrue();
    assertThat(analysis.notes()).anySatisfy(note -> assertThat(note).contains("创建者 + 成员"));
  }

  @Test
  @DisplayName("人数给 0 或负数时退回默认人数，不做除零也不给一个假的人均")
  void nonPositiveTravelersFallsBackToDefault() {
    Itinerary itinerary = trip(
        new Plan("杭州", "西湖", "备注", DAY, DAY.plusDays(2), new BigDecimal("1000"), "CNY"),
        List.of(member("bob")),
        cost("e1", "交通", "300", "CNY"));

    BudgetAnalysis zero = BudgetCalculator.analyze(itinerary, 0);
    BudgetAnalysis negative = BudgetCalculator.analyze(itinerary, -3);
    BudgetAnalysis explicit = BudgetCalculator.analyze(itinerary, 2);

    assertThat(zero.travelers()).isEqualTo(2);
    assertThat(zero.perPerson()).isEqualByComparingTo("150");
    assertThat(negative.travelers()).isEqualTo(2);
    assertThat(negative.perPerson()).isEqualByComparingTo("150");
    // 显式指定为正数时才采用调用方的值
    assertThat(explicit.travelers()).isEqualTo(2);
    assertThat(explicit.notes()).anySatisfy(note -> assertThat(note).contains("调用方指定"));
  }

  @Test
  @DisplayName("超上限时给出超出金额与提示，差额为负值")
  void overLimitReportsOverBy() {
    BudgetAnalysis analysis = BudgetCalculator.analyze(trip(
        new Plan("杭州", "西湖", "备注", DAY, DAY.plusDays(2), new BigDecimal("100"), "CNY"),
        List.of(),
        cost("e1", "交通", "150", "CNY")), null);

    assertThat(analysis.overLimit()).isTrue();
    assertThat(analysis.overBy()).isEqualByComparingTo("50");
    assertThat(analysis.remaining()).isEqualByComparingTo("-50");
    assertThat(hasWarning(analysis, "BUDGET_OVER_LIMIT")).isTrue();
  }

  @Test
  @DisplayName("上限币种与费用主币种不同：不换算也不硬减，标为不可比")
  void currencyMismatchIsNotComparable() {
    BudgetAnalysis analysis = BudgetCalculator.analyze(trip(
        new Plan("东京", "银座", "备注", DAY, DAY.plusDays(2), new BigDecimal("5000"), "CNY"),
        List.of(),
        cost("e1", "住宿", "800", "USD")), null);

    assertThat(analysis.currency()).isEqualTo("USD");
    assertThat(analysis.comparable()).isFalse();
    assertThat(analysis.remaining()).isNull();
    assertThat(analysis.overLimit()).isFalse();
    assertThat(hasWarning(analysis, "BUDGET_CURRENCY_MISMATCH")).isTrue();
  }

  @Test
  @DisplayName("预计与实际分开：已发生的只进实际总额，仍属预计的只进计划总额")
  void plannedAndActualSplit() {
    BudgetAnalysis analysis = BudgetCalculator.analyze(trip(Plan.empty(), List.of(),
        cost("e1", "交通", "100", "CNY", true),
        cost("e2", "住宿", "200", "CNY", false)), null);

    assertThat(analysis.estimatedTotal()).isEqualByComparingTo("300");
    assertThat(analysis.actualTotal()).isEqualByComparingTo("100");
    assertThat(analysis.plannedTotal()).isEqualByComparingTo("200");
    assertThat(analysis.perPersonActual()).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("未设上限时不给差额，提示「无法判断是否超支」而不是拿 0 当上限")
  void missingLimitIsNotTreatedAsZero() {
    BudgetAnalysis analysis = BudgetCalculator.analyze(trip(Plan.empty(), List.of(),
        cost("e1", "交通", "300", "CNY")), null);

    assertThat(analysis.limit()).isNull();
    assertThat(analysis.remaining()).isNull();
    assertThat(analysis.overLimit()).isFalse();
    assertThat(hasWarning(analysis, "BUDGET_LIMIT_MISSING")).isTrue();
  }

  @Test
  @DisplayName("负数金额照实计入合计并明确提示，不静默修正")
  void negativeAmountIsCountedAndFlagged() {
    BudgetSummary summary = BudgetCalculator.summarize(trip(Plan.empty(), List.of(),
        cost("e1", "退款", "-50", "CNY")));

    assertThat(summary.primaryTotal()).isEqualByComparingTo("-50");
    assertThat(summary.notes()).anySatisfy(note -> assertThat(note).contains("负数"));
  }

  @Test
  @DisplayName("体检：费用为空、日期缺失时给出对应提示，且不因空列表报错")
  void checkHandlesEmptyItinerary() {
    var checks = BudgetCalculator.check(trip(Plan.empty(), List.of()));

    assertThat(checks.ok()).isFalse();
    assertThat(checks.warnings()).extracting(CheckWarning::code)
        .contains("DEPART_DATE_MISSING", "RETURN_DATE_MISSING", "NO_EXPENSE");
    // 空行程不该报到「安排级时间」上去
    assertThat(checks.warnings()).extracting(CheckWarning::code)
        .doesNotContain("ACTIVITY_TIME_MISSING", "ACTIVITY_TIME_OVERLAP");
  }

  @Test
  @DisplayName("体检：区间倒置时只报一条，不把每条安排都刷成越界")
  void invertedPeriodDoesNotCascade() {
    Itinerary itinerary = new Itinerary("it-1", "alice", "反了", 1L, java.time.Instant.EPOCH,
        java.time.Instant.EPOCH, List.of(),
        new Plan("杭州", "西湖", "备注", DAY.plusDays(2), DAY, null, null),
        List.of(new DailySchedule("d1", DAY.plusDays(5), "备注", List.of())),
        List.of(), List.of(), List.of(), false, null);

    var checks = BudgetCalculator.check(itinerary);

    assertThat(checks.warnings()).extracting(CheckWarning::code)
        .contains("RETURN_BEFORE_DEPART")
        .doesNotContain("DAILY_OUT_OF_RANGE");
  }
}
