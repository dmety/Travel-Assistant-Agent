package com.travelassistant.server.application.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.budget.BudgetService;
import com.travelassistant.server.application.decision.CandidateService;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.BudgetSummary;
import com.travelassistant.server.domain.budget.CheckWarning;
import com.travelassistant.server.domain.budget.CurrencyTotal;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.itinerary.DailyActivity;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 导出文本的渲染规则（V3 第 4 节）。
 *
 * <p>导出的是一份会被转发到群聊里的纯文本，所以这里的断言不是「字符串长什么样」，
 * 而是几条产品承诺：缺数据时写「未填写」而不是 0 或空白、多币种绝不合并成一个数字、
 * 备注里的换行和逗号不能把结构冲垮、结尾必须带免责说明。
 *
 * <p>预算与候选都 mock：它们各有自己的用例（{@code BudgetCalculatorTest} 等），
 * 这里只验证导出怎么**使用**它们的结果。
 */
@DisplayName("导出服务：渲染规则")
class ExportServiceTest {

  private static final String ID = "itin-export-1";
  private static final String USER = "alice";

  private ItineraryService itineraries;
  private BudgetService budgets;
  private CandidateService candidates;
  private ExportService exportService;

  private BudgetSummary summary;
  private BudgetAnalysis analysis;
  private ItineraryChecks checks;
  private CandidateBoard board;

  @BeforeEach
  void setUp() {
    itineraries = mock(ItineraryService.class);
    budgets = mock(BudgetService.class);
    candidates = mock(CandidateService.class);
    exportService = new ExportService(itineraries, budgets, candidates);

    summary = new BudgetSummary("CNY",
        List.of(new CurrencyTotal("CNY", new BigDecimal("1200.00"), 2)),
        List.of(), 2, 0, List.of(), List.of("CNY"), false, List.of());
    analysis = new BudgetAnalysis("CNY", new BigDecimal("3000.00"),
        new BigDecimal("1200.00"), new BigDecimal("1200.00"), new BigDecimal("0.00"),
        new BigDecimal("1800.00"), true, false, null, 2,
        new BigDecimal("600.00"), new BigDecimal("0.00"), List.of(), List.of());
    checks = new ItineraryChecks(ID, 1L, true, List.of());
    board = new CandidateBoard(ID, 1L, 2, 0, 0, 0, List.of(), null, false, List.of());
  }

  private String export(Itinerary it) {
    when(itineraries.get(eq(ID), anyString())).thenReturn(it);
    when(budgets.summarize(ID, USER)).thenReturn(summary);
    when(budgets.analyze(eq(ID), eq(USER), isNull())).thenReturn(analysis);
    when(budgets.check(ID, USER)).thenReturn(checks);
    when(candidates.boardOf(it)).thenReturn(board);
    return exportService.toText(ID, USER);
  }

  private static Itinerary base() {
    return Itinerary.create(USER, "杭州三日", Plan.empty());
  }

  @Test
  @DisplayName("全空行程也能导出：明确写「未填写」，不出现 null、不抛异常")
  void emptyItineraryStillRenders() {
    Itinerary it = Itinerary.create(USER, null, null);

    String text = export(it);

    assertThat(text).contains("行程摘要：未命名行程");
    assertThat(text).contains("目的地：未填写");
    assertThat(text).contains("时间：未填出发 ~ 未填返回");
    assertThat(text).contains("暂无其他成员");
    assertThat(text).contains("暂无安排");
    assertThat(text).contains("暂无交通安排");
    assertThat(text).contains("暂无住宿安排");
    assertThat(text).contains("暂无费用记录");
    assertThat(text).contains("暂无候选项");
    assertThat(text).contains("未发现问题");
    assertThat(text).doesNotContain("null");
  }

  @Test
  @DisplayName("未设预算上限时写「未设定」，不拿 0 冒充")
  void unsetLimitSaysNotSet() {
    analysis = new BudgetAnalysis("CNY", null, new BigDecimal("1200.00"),
        new BigDecimal("1200.00"), new BigDecimal("0.00"), null, false, false, null, 2,
        new BigDecimal("600.00"), new BigDecimal("0.00"), List.of(), List.of());

    String text = export(base());

    assertThat(text).contains("上限：未设定");
    assertThat(text).contains("差额：无法计算（未设上限或币种不一致）");
  }

  @Test
  @DisplayName("备注里的换行与逗号原样保留，不吞字符、不把段落拼错位")
  void specialCharactersDoNotBreakLayout() {
    String notes = "第一行：红叶季\n第二行：西湖游船末班 16:30，灵隐飞来峰 09:00 场";
    Itinerary it = base().withPlan(new Plan("杭州", "西湖—灵隐，三天两晚", notes, null, null,
        null, null));

    String text = export(it);

    assertThat(text).contains("概述：西湖—灵隐，三天两晚");
    assertThat(text).contains("备注：" + notes);
    // 换行是被保留的，所以第二行会自成一行——这是纯文本的可接受行为，钉住它
    assertThat(text).contains("第二行：西湖游船末班 16:30，灵隐飞来峰 09:00 场");
  }

  @Test
  @DisplayName("多币种分行列出，绝不合并成一个数字")
  void multipleCurrenciesStayOnTheirOwnLines() {
    Itinerary it = base().withExpenses(List.of(
        new Expense("e1", "交通", new BigDecimal("800.00"), "CNY", null, "高铁", false),
        new Expense("e2", "门票", new BigDecimal("120.00"), "USD", null, "环球影城", false)));
    summary = new BudgetSummary("CNY",
        List.of(new CurrencyTotal("CNY", new BigDecimal("800.00"), 1),
            new CurrencyTotal("USD", new BigDecimal("120.00"), 1)),
        List.of(), 2, 0, List.of(), List.of("CNY", "USD"), true, List.of("币种不一致"));

    String text = export(it);

    assertThat(text).contains("小计：CNY 800.00");
    assertThat(text).contains("USD 120.00");
    // 一旦合并成 920.00，这两种币种的费用就被当成了同一种钱
    assertThat(text).doesNotContain("920.00");
  }

  @Test
  @DisplayName("日期区间按「含首尾」计天数，不给「两天」这种少算一天的口径")
  void dateRangeCountsBothEnds() {
    Plan plan = new Plan("杭州", null, null, LocalDate.of(2026, 11, 21),
        LocalDate.of(2026, 11, 23), null, null);
    Itinerary it = base().withPlan(plan);

    assertThat(export(it)).contains("2026-11-21 ~ 2026-11-23（3 天）");
  }

  @Test
  @DisplayName("活动同时有起止时间时输出时间区间，只有自由文本时退回文本")
  void activityTimeFallsBackToFreeText() {
    Itinerary it = base().withDaily(List.of(new DailySchedule("d1", LocalDate.of(2026, 11, 21),
        "第一天", List.of(
            new DailyActivity("a1", null, "灵隐寺", "灵隐", null,
                LocalTime.of(9, 0), LocalTime.of(11, 30)),
            new DailyActivity("a2", "下午", "河坊街", "上城", null)))));

    String text = export(it);

    assertThat(text).contains("09:00–11:30 灵隐寺（灵隐）");
    assertThat(text).contains("下午 河坊街（上城）");
  }

  @Test
  @DisplayName("只有实际发生的费用标「已发生」，预计的不冒充")
  void expenseMarksActual() {
    Itinerary it = base().withExpenses(List.of(
        new Expense("e1", "住宿", new BigDecimal("600.00"), "cny", null, null, true),
        new Expense("e2", "餐饮", new BigDecimal("300.00"), "CNY", null, null, false)));

    String text = export(it);

    assertThat(text).contains("600.00 CNY（已发生）");
    assertThat(text).contains("300.00 CNY（预计）");
  }

  @Test
  @DisplayName("已归档行程在抬头写明「只读」，导出件不会被当成还能改的草稿")
  void archivedItinerarySaysReadOnly() {
    String text = export(base().withArchived(true));

    assertThat(text).contains("状态：已归档（只读）");
  }

  @Test
  @DisplayName("体检问题逐条带稳定代码，不合并成一句话")
  void warningsKeepTheirCodes() {
    checks = new ItineraryChecks(ID, 1L, false, List.of(
        new CheckWarning("RETURN_BEFORE_DEPART", "plan.returnDate", "返回日期早于出发日期"),
        new CheckWarning("BUDGET_OVER", "", "预算已超支")));

    String text = export(base());

    assertThat(text).contains("[RETURN_BEFORE_DEPART] 返回日期早于出发日期");
    assertThat(text).contains("[BUDGET_OVER] 预算已超支");
  }

  @Test
  @DisplayName("结尾必须带免责说明：导出件会脱离上下文被转发")
  void disclaimerIsAlwaysPresent() {
    assertThat(export(base()))
        .contains("以景区官方渠道为准")
        .contains("不构成预订承诺");
  }

  @Test
  @DisplayName("文件名去掉路径分隔符与空白，保留标题可读性")
  void fileNameSanitizesTitle() {
    when(itineraries.get(ID, USER))
        .thenReturn(Itinerary.create(USER, "杭州/西湖 三日游", Plan.empty()));

    assertThat(exportService.fileName(ID, USER)).isEqualTo("杭州_西湖_三日游.txt");
  }

  @Test
  @DisplayName("没有标题时文件名退回「行程.txt」，不产生空文件名")
  void fileNameFallsBackWithoutTitle() {
    when(itineraries.get(ID, USER)).thenReturn(Itinerary.create(USER, null, Plan.empty()));

    assertThat(exportService.fileName(ID, USER)).isEqualTo("行程.txt");
  }

  @Test
  @DisplayName("成员授权按固定板块顺序展示，空授权写「只读」")
  void membersShowPermissions() {
    Itinerary it = base().withMembers(List.of(
        new Member("bob", Set.of(Section.EXPENSE, Section.DAILY)),
        new Member("cara", Set.of())));

    String text = export(it);

    assertThat(text).contains("- bob（可编辑：每日安排、费用）");
    assertThat(text).contains("- cara（只读）");
  }
}
