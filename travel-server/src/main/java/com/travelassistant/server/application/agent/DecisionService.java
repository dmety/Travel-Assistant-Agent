package com.travelassistant.server.application.agent;

import com.travelassistant.server.application.budget.BudgetService;
import com.travelassistant.server.application.decision.CandidateService;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.CheckWarning;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.decision.CandidateTally;
import com.travelassistant.server.domain.itinerary.DailyActivity;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Transport;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 综合决策建议（V3 第 3 节）。
 *
 * <p>和 V2 的规划编排一样，**判断由 Java 做，模型只负责组织语言**，但这里的理由更硬：
 * 「预算超了多少」「哪两天时间打架」「哪天有雨」都是可以用数字和日期说清楚的事，
 * 交给模型就有编造空间。{@code risks} 的每一条都由规则算出，测试可以逐条断言；
 * 模型的作用是把这些事实讲成一段人能读的话。
 *
 * <p>数据来源全部是项目里已有的东西：投票看板、预算分析、行程体检、可选的高德天气。
 * 不额外调用任何「行程合理性评估」接口，也不引入新的外部依赖。
 *
 * <p>本服务**不写库**：所有建议都要用户确认后才落到正式方案。
 */
@Service
public class DecisionService {
  private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

  /** 风险条目上限：真实行程不会有一百个风险，多了说明该去修数据而不是看建议。 */
  private static final int MAX_RISKS = 30;
  /** 规则建议条数上限。 */
  private static final int MAX_SUGGESTIONS = 8;

  private final ItineraryService itineraries;
  private final BudgetService budgets;
  private final CandidateService candidates;
  private final PlanningTools tools;
  private final DeepSeekClient model;

  public DecisionService(ItineraryService itineraries, BudgetService budgets,
      CandidateService candidates, PlanningTools tools, DeepSeekClient model) {
    this.itineraries = itineraries;
    this.budgets = budgets;
    this.candidates = candidates;
    this.tools = tools;
    this.model = model;
  }

  /** 生成综合决策建议（不落库）。 */
  public DecisionReport review(String itineraryId, String user, DecisionRequest request) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    Integer travelers = request == null ? null : request.travelers();
    boolean includeWeather = request == null || request.includeWeather() == null
        || request.includeWeather();

    CandidateBoard board = candidates.boardOf(itinerary);
    BudgetAnalysis budget = budgets.analyze(itineraryId, user, travelers);
    ItineraryChecks checks = budgets.check(itineraryId, user);

    List<String> notices = new ArrayList<>();
    Set<String> sources = new LinkedHashSet<>();
    sources.add("本系统行程数据");
    if (!board.candidates().isEmpty()) {
      sources.add("成员投票");
    }

    WeatherResult weather = fetchWeather(itinerary, includeWeather, notices, sources);

    List<DecisionReport.Risk> risks = new ArrayList<>();
    collectBudgetRisks(budget, risks);
    collectCheckRisks(checks, risks);
    collectVoteRisks(board, risks);
    collectTransportRisks(itinerary, risks);
    collectLodgingRisks(itinerary, risks);
    collectWeatherRisks(itinerary, weather, risks);
    if (itinerary.archived()) {
      risks.add(new DecisionReport.Risk("ITINERARY_ARCHIVED", "INFO",
          "行程已归档，当前为只读状态，任何调整都需要先取消归档", "行程状态",
          "取消归档后再修改"));
    }

    Comparator<DecisionReport.Risk> bySeverity =
        Comparator.comparingInt((DecisionReport.Risk r) -> severityRank(r.severity())).reversed();
    risks.sort(bySeverity);
    if (risks.size() > MAX_RISKS) {
      risks = new ArrayList<>(risks.subList(0, MAX_RISKS));
      notices.add("风险条目过多，已只显示最严重的 " + MAX_RISKS + " 条");
    }

    List<String> suggestions = buildSuggestions(itinerary, board, budget, checks);

    String advice = null;
    if (model.configured()) {
      try {
        advice = model.complete(systemPrompt(), userPrompt(itinerary, board, budget, checks,
            weather, risks));
        sources.add("DeepSeek " + model.model());
      } catch (AgentUnavailableException e) {
        log.warn("综合建议生成失败：{}", e.getMessage());
        notices.add(e.getMessage() + "，以下风险与建议由系统规则给出，不含模型文字");
      }
    } else {
      notices.add("未配置模型密钥（DEEPSEEK_API_KEY），本次不生成文字建议；"
          + "风险与调整建议仍由系统规则给出");
    }

    return new DecisionReport(itinerary.id(), itinerary.version(), itinerary.title(), board,
        budget, checks, weather, List.copyOf(risks), List.copyOf(suggestions), advice,
        List.copyOf(notices), List.copyOf(sources), Instant.now());
  }

  // ---- 数据采集 ----

  private WeatherResult fetchWeather(Itinerary itinerary, boolean includeWeather,
      List<String> notices, Set<String> sources) {
    Plan plan = itinerary.plan();
    String city = plan == null ? null : plan.destination();
    if (!includeWeather) {
      notices.add("本次未查询天气（请求里关闭了天气查询）");
      return null;
    }
    if (city == null || city.isBlank()) {
      notices.add("未填写目的地，无法查询天气");
      return null;
    }
    if (!tools.locationEnabled()) {
      notices.add("未接入实时天气（未配置高德密钥），天气相关的风险未纳入判断");
      return null;
    }
    ToolOutcome<WeatherResult> outcome = tools.weather(city.strip());
    if (!outcome.ok()) {
      notices.add(outcome.error());
      return null;
    }
    sources.add(outcome.value().source());
    return outcome.value();
  }

  // ---- 规则 ----

  private void collectBudgetRisks(BudgetAnalysis budget, List<DecisionReport.Risk> risks) {
    for (CheckWarning warning : budget.warnings()) {
      risks.add(new DecisionReport.Risk(warning.code(), severityOf(warning.code()),
          warning.message(), "预算", suggestionOf(warning.code())));
    }
  }

  private void collectCheckRisks(ItineraryChecks checks, List<DecisionReport.Risk> risks) {
    for (CheckWarning warning : checks.warnings()) {
      risks.add(new DecisionReport.Risk(warning.code(), severityOf(warning.code()),
          warning.message(), "行程体检", suggestionOf(warning.code())));
    }
  }

  private void collectVoteRisks(CandidateBoard board, List<DecisionReport.Risk> risks) {
    if (board.candidateCount() == 0) {
      risks.add(new DecisionReport.Risk("NO_CANDIDATE", "INFO",
          "还没有候选地点，大家尚未开始讨论要去哪里", "投票",
          "先添加 2~3 个候选地点，再让成员投票"));
      return;
    }
    if (board.totalVotes() == 0) {
      risks.add(new DecisionReport.Risk("NO_VOTE_YET", "MEDIUM",
          "有 " + board.candidateCount() + " 个候选，但还没有人投票，目前无法判断大家的倾向", "投票",
          "邀请成员投票，哪怕只是表达「都行」也比沉默有用"));
      return;
    }
    if (board.tie()) {
      risks.add(new DecisionReport.Risk("VOTE_TIE", "MEDIUM",
          "候选票数并列，投票分不出高下，直接按票数定会有人不服", "投票",
          "把并列的候选按「路程／门票／耗时」再比较一次，或由创建者拍板并说明理由"));
    }
    int silent = board.participantCount() - board.voterCount();
    if (silent > 0) {
      risks.add(new DecisionReport.Risk("VOTE_PARTIAL", "INFO",
          "还有 " + silent + " 位参与者没有投票，当前结果只代表已投票的人", "投票",
          "等成员补齐投票，或明确说明「不投视为弃权」"));
    }
  }

  /** 当天到达时间晚于当天第一项安排 → 那项安排根本赶不上。 */
  private void collectTransportRisks(Itinerary itinerary, List<DecisionReport.Risk> risks) {
    for (Transport transport : itinerary.transports()) {
      LocalTime arrive = parseTime(transport.arriveTime());
      if (transport.date() == null || arrive == null) {
        continue;
      }
      for (DailySchedule day : itinerary.dailySchedules()) {
        if (!transport.date().equals(day.date())) {
          continue;
        }
        for (DailyActivity activity : day.activities()) {
          if (!activity.hasTimeRange() || activity.startTime() == null) {
            continue;
          }
          if (activity.startTime().isBefore(arrive)) {
            risks.add(new DecisionReport.Risk("TRANSPORT_ARRIVAL_CONFLICT", "HIGH",
                transport.date() + " 的交通 " + blank(transport.departTime(), "?") + "–"
                    + blank(transport.arriveTime(), "?") + "（" + blank(transport.from(), "出发地")
                    + " → " + blank(transport.to(), "目的地") + "）到达时间晚于当天的安排「"
                    + blank(activity.title(), "未命名") + "」（" + activity.startTime() + " 开始）",
                "交通与每日安排",
                "把这项安排挪到到达之后，或换更早的班次"));
          }
        }
      }
    }
  }

  private void collectLodgingRisks(Itinerary itinerary, List<DecisionReport.Risk> risks) {
    Plan plan = itinerary.plan();
    if (plan == null || !plan.hasDateRange()) {
      return;
    }
    long nights = java.time.temporal.ChronoUnit.DAYS.between(plan.departDate(),
        plan.returnDate());
    if (nights >= 1 && itinerary.lodgings().isEmpty()) {
      risks.add(new DecisionReport.Risk("NO_LODGING", "MEDIUM",
          "行程共 " + nights + " 晚，但还没有登记任何住宿", "住宿",
          "先定住宿位置，再按位置安排每天的路线，能少走很多回头路"));
    }
    if (itinerary.transports().isEmpty()) {
      risks.add(new DecisionReport.Risk("NO_TRANSPORT", "INFO",
          "尚未登记往返交通，到达和离开时间无法核对", "交通",
          "补上往返班次，系统才能检查首末两天的安排是否来得及"));
    }
  }

  /** 旅行日期内有雨雪 → 提醒把户外安排留出备选。 */
  private void collectWeatherRisks(Itinerary itinerary, WeatherResult weather,
      List<DecisionReport.Risk> risks) {
    if (weather == null) {
      return;
    }
    Plan plan = itinerary.plan();
    LocalDate depart = plan == null ? null : plan.departDate();
    LocalDate ret = plan == null ? null : plan.returnDate();

    List<String> badDays = new ArrayList<>();
    for (WeatherResult.DayWeather day : weather.days()) {
      if (day.date() == null) {
        continue;
      }
      LocalDate date;
      try {
        date = LocalDate.parse(day.date());
      } catch (RuntimeException e) {
        continue;
      }
      if (depart != null && date.isBefore(depart)) {
        continue;
      }
      if (ret != null && date.isAfter(ret)) {
        continue;
      }
      String text = (day.dayWeather() == null ? "" : day.dayWeather())
          + (day.nightWeather() == null ? "" : day.nightWeather());
      if (text.contains("雨") || text.contains("雪")) {
        badDays.add(day.date() + " " + blank(day.dayWeather(), "未知"));
      }
    }
    if (!badDays.isEmpty()) {
      risks.add(new DecisionReport.Risk("WEATHER_BAD", "MEDIUM",
          "旅行期间有雨雪预报：" + String.join("；", badDays), "天气预报",
          "把室外项目留出备选，或与室内项目调换顺序"));
    }
  }

  private List<String> buildSuggestions(Itinerary itinerary, CandidateBoard board,
      BudgetAnalysis budget, ItineraryChecks checks) {
    List<String> suggestions = new ArrayList<>();

    CandidateTally leader = board.candidates().stream()
        .filter(t -> t.candidate().id().equals(board.leaderId()))
        .findFirst()
        .orElse(null);
    if (leader != null) {
      suggestions.add("票数最高的是「" + leader.candidate().name() + "」（支持 " + leader.support()
          + "／反对 " + leader.oppose() + "），确认后可以加入某一天的安排");
    } else if (board.candidateCount() > 0 && board.totalVotes() == 0) {
      suggestions.add("先让成员对候选投票，有了倾向再排日程，比反复改日程省事");
    }

    if (budget.remaining() != null && budget.remaining().signum() > 0) {
      suggestions.add("预算还剩 " + budget.remaining().toPlainString() + " " + budget.currency()
          + "，可用于门票或餐饮，建议先保交通与住宿");
    }
    if (budget.overLimit()) {
      suggestions.add("超支 " + budget.overBy().toPlainString() + " " + budget.currency()
          + "：先看住宿与交通能否降档，这两项通常弹性最大");
    }

    long overlap = checks.warnings().stream()
        .filter(w -> "ACTIVITY_TIME_OVERLAP".equals(w.code()))
        .count();
    if (overlap > 0) {
      suggestions.add("有 " + overlap + " 处同日安排时间重叠，先把它们错开再考虑新增项目");
    }

    long outOfRange = checks.warnings().stream()
        .filter(w -> w.code().endsWith("_OUT_OF_RANGE"))
        .count();
    if (outOfRange > 0) {
      suggestions.add("有 " + outOfRange + " 项安排在旅行日期之外，确认是笔误还是确实要跨天");
    }

    if (suggestions.size() > MAX_SUGGESTIONS) {
      return List.copyOf(suggestions.subList(0, MAX_SUGGESTIONS));
    }
    return List.copyOf(suggestions);
  }

  // ---- 模型 ----

  private String systemPrompt() {
    return """
        你是旅行助手的决策支持角色。规则：
        1. 只能使用消息里列出的风险、投票、预算和天气数据，不得补充任何未提供的数字、景点或价格。
        2. 信息缺失就写缺失，不要估算票价、余票或耗时。
        3. 不输出任何网址。
        4. 先给结论，再说依据；每条观点都要能对应到消息里某一条风险或数据。
        5. 投票只用于讨论，不得声称「已决定」；最终决定权在用户。
        6. 用中文，分点回答，控制在 450 字以内。
        7. 结尾提醒：确认后再修改正式行程。
        """;
  }

  private String userPrompt(Itinerary itinerary, CandidateBoard board, BudgetAnalysis budget,
      ItineraryChecks checks, WeatherResult weather, List<DecisionReport.Risk> risks) {
    StringBuilder sb = new StringBuilder();
    Plan plan = itinerary.plan() == null ? Plan.empty() : itinerary.plan();
    sb.append("行程：").append(blank(itinerary.title(), "未命名")).append('\n');
    sb.append("目的地：").append(blank(plan.destination(), "未填写")).append('\n');
    sb.append("日期：").append(plan.departDate()).append(" ~ ").append(plan.returnDate())
        .append('\n');
    sb.append("状态：").append(itinerary.archived() ? "已归档（只读）" : "进行中").append('\n');

    sb.append("\n投票（").append(board.voterCount()).append('/').append(board.participantCount())
        .append(" 人已投）：\n");
    if (board.candidates().isEmpty()) {
      sb.append("- 无候选\n");
    } else {
      for (CandidateTally t : board.candidates()) {
        sb.append("- ").append(t.candidate().name()).append("：支持 ").append(t.support())
            .append("，反对 ").append(t.oppose()).append("，弃权 ").append(t.abstain())
            .append(t.candidate().id().equals(board.leaderId()) ? "（当前最高）" : "")
            .append('\n');
      }
    }

    sb.append("\n预算（").append(budget.currency()).append("）：\n");
    sb.append("- 上限 ").append(money(budget.limit())).append('\n');
    sb.append("- 预计 ").append(money(budget.estimatedTotal())).append("，实际 "
        ).append(money(budget.actualTotal())).append('\n');
    sb.append("- 差额 ").append(money(budget.remaining())).append("，人均 ")
        .append(money(budget.perPerson())).append("（").append(budget.travelers())
        .append(" 人）\n");

    sb.append("\n天气：");
    if (weather == null) {
      sb.append("未获取\n");
    } else {
      sb.append(weather.city()).append("（").append(weather.reportTime()).append(" 发布）\n");
      weather.days().forEach(d -> sb.append("- ").append(d.date()).append(' ')
          .append(blank(d.dayWeather(), "未知")).append(' ')
          .append(blank(d.dayTemp(), "?")).append("°C\n"));
    }

    sb.append("\n系统已识别出的问题（共 ").append(risks.size()).append(" 条）：\n");
    if (risks.isEmpty()) {
      sb.append("- 无\n");
    } else {
      for (DecisionReport.Risk r : risks) {
        sb.append("- [").append(r.severity()).append("] ").append(r.message())
            .append("（依据：").append(r.basis()).append("）\n");
      }
    }

    sb.append("\n体检提示共 ").append(checks.warnings().size()).append(" 条。\n");
    sb.append("\n请基于以上信息，给出一段可讨论的调整建议，并说明每条建议依据了哪条数据。");
    return sb.toString();
  }

  // ---- 辅助 ----

  private int severityRank(String severity) {
    if ("HIGH".equals(severity)) {
      return 3;
    }
    if ("MEDIUM".equals(severity)) {
      return 2;
    }
    return 1;
  }

  private String severityOf(String code) {
    return switch (code) {
      case "BUDGET_OVER_LIMIT", "BUDGET_ACTUAL_OVER_LIMIT", "ACTIVITY_TIME_INVALID",
          "TRANSPORT_ARRIVAL_CONFLICT" -> "HIGH";
      case "BUDGET_CURRENCY_MISMATCH", "BUDGET_ACTUAL_OVER_PLAN", "ACTIVITY_TIME_OVERLAP",
          "DAILY_OUT_OF_RANGE", "TRANSPORT_OUT_OF_RANGE", "LODGING_OUT_OF_RANGE",
          "MIXED_CURRENCY", "EXPENSE_NEGATIVE", "VOTE_TIE", "NO_VOTE_YET",
          "NO_LODGING", "WEATHER_BAD" -> "MEDIUM";
      default -> "INFO";
    };
  }

  private String suggestionOf(String code) {
    return switch (code) {
      case "BUDGET_LIMIT_MISSING" -> "在方案里填一个预算上限，系统才能替你盯着超支";
      case "BUDGET_OVER_LIMIT" -> "先压缩住宿与交通，这两项弹性最大；实在压不下来就调整上限并说明原因";
      case "BUDGET_ACTUAL_OVER_LIMIT" -> "已经花超了，后续安排先按剩余金额倒推";
      case "BUDGET_CURRENCY_MISMATCH" -> "把上限和费用改成同一币种，或分别看两种币种的小计";
      case "BUDGET_ACTUAL_OVER_PLAN" -> "原预计偏低，更新费用记录后再看差额";
      case "EXPENSE_AMOUNT_MISSING" -> "补齐未填写的金额，否则预算总是偏低";
      case "MIXED_CURRENCY" -> "按币种分别核对，系统不做汇率换算";
      case "NO_EXPENSE" -> "先粗记一笔交通和住宿，预算才有参考价值";
      case "ACTIVITY_TIME_OVERLAP" -> "把重叠的两项错开，留出交通和排队时间";
      case "ACTIVITY_TIME_INVALID" -> "结束时间早于开始时间，多半是写错了";
      case "ACTIVITY_TIME_MISSING" -> "给关键项目补上起止时间，否则查不出冲突";
      case "DAILY_OUT_OF_RANGE" -> "确认这项安排是否真的要放在旅行日期之外";
      case "DEPART_DATE_MISSING", "RETURN_DATE_MISSING" -> "补上出发与返回日期，时间检查才能生效";
      case "RETURN_BEFORE_DEPART" -> "返回日期早于出发日期，先改正日期";
      case "TRIP_ENDED" -> "行程已结束，可以归档保存";
      default -> null;
    };
  }

  private LocalTime parseTime(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(text.strip());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String money(java.math.BigDecimal value) {
    return value == null ? "未设定" : value.setScale(2, java.math.RoundingMode.HALF_UP)
        .toPlainString();
  }

  private String blank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}
