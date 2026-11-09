package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.attraction.PlaceholderValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 草案规则装配器：把「需求 + 知识库候选」变成结构化补问、逐日草案、日期/预算检查。
 *
 * <p>这一层是**纯函数**：不查库、不调模型、不写文件、不落库。所有结论都能从入参复算，
 * 测试可以逐条断言。刻意与 {@link PlanningService} 分开，是为了让「确认保存」时
 * 复用同一套规则重新装配，避免前端展示的草案与最终落库的内容是两套逻辑。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li>不猜：没有日期就不编造日期，没有天数就补问（除非能由日期区间确定性换算）；</li>
 *   <li>不冒充已知：票价/开放时间是占位值或 null 时写进 {@code unknowns}，展示为「未知」；</li>
 *   <li>不伪造核验：只有 {@code verified} / {@code confirmed} 且能解析出金额的票价才计入预算粗估，
 *       其余一律算「未知」，结论只能是「无法判定」而不是「没超支」。</li>
 * </ul>
 */
public final class DraftAssembler {

  /** 每天最多安排几个候选：再多用户也不会逐个点，且排程没有可靠依据。 */
  public static final int MAX_PER_DAY = 2;
  /** 天数上限，防止 days=99999 生成一份几万天的响应。 */
  public static final int MAX_DAYS = 30;

  private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)");
  private static final String RETRY = "POST /api/agent/plan";

  private DraftAssembler() {
  }

  /**
   * 一次装配的结果。
   *
   * @param questions      需要用户补全的字段
   * @param dailySchedules 按天草案；无法生成天数时为空列表
   * @param checks         日期与预算检查结论
   * @param budgetEstimate 预算粗估（含统计范围与局限）
   * @param notices        需要并入草案 notices 的补充说明
   */
  public record Draft(
      List<DraftQuestion> questions,
      List<DraftDay> dailySchedules,
      List<DraftCheck> checks,
      DraftBudgetEstimate budgetEstimate,
      List<String> notices) {
  }

  public static Draft assemble(PlanningRequest request, List<Attraction> candidates) {
    List<Attraction> safe = candidates == null ? List.of() : candidates;
    List<DraftQuestion> questions = new ArrayList<>();
    List<DraftCheck> checks = new ArrayList<>();
    List<String> notices = new ArrayList<>();

    collectQuestions(request, questions, checks, notices);

    int plannedDays = request.effectiveDays() == null ? 0
        : Math.min(request.effectiveDays(), MAX_DAYS);
    int usable = Math.min(safe.size(), plannedDays * MAX_PER_DAY);
    List<Attraction> selected = safe.subList(0, Math.max(usable, 0));
    List<DraftDay> daily = arrange(request, selected);
    collectDateChecks(request, plannedDays, checks);
    if (daily.isEmpty()) {
      checks.add(new DraftCheck("NO_DAILY_SCHEDULE", "INFO", "date",
          "天数或候选景点不足，本次未生成逐日草案", "补齐游玩天数与目的地后重新生成草案"));
    }
    if (selected.isEmpty() && !safe.isEmpty()) {
      notices.add("候选景点未进入逐日草案：请先填写游玩天数");
    }

    DraftBudgetEstimate estimate = estimate(request, selected, checks);
    return new Draft(List.copyOf(questions), List.copyOf(daily), List.copyOf(checks),
        estimate, List.copyOf(notices));
  }

  /**
   * 按候选顺序把景点分配到每一天，最多 {@link #MAX_PER_DAY} 个/天。
   *
   * <p>这是确定性排序的延续：{@code PlanningTools} 已按「等级 → 评分 → 名称」排好序，
   * 这里只做等分，不引入新的推荐判断。日期由出发日期顺序推算，超出返回日期的那些天
   * 只留 dayIndex、不给 date，避免把行程写到旅行区间之外。
   */
  public static List<DraftDay> arrange(PlanningRequest request, List<Attraction> selected) {
    List<Attraction> safe = selected == null ? List.of() : selected;
    Integer effective = request.effectiveDays();
    if (effective == null || effective <= 0) {
      return List.of();
    }
    int count = Math.min(effective, MAX_DAYS);
    LocalDate depart = request.departDate();
    LocalDate ret = request.returnDate();
    boolean validRange = depart != null && ret != null && !ret.isBefore(depart);

    List<DraftDay> days = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int from = i * MAX_PER_DAY;
      int to = Math.min(from + MAX_PER_DAY, safe.size());
      List<DraftDay.Item> items = new ArrayList<>();
      for (int j = from; j < to; j++) {
        items.add(itemOf(safe.get(j)));
      }

      LocalDate date = null;
      String note;
      if (validRange) {
        LocalDate candidate = depart.plusDays(i);
        if (!candidate.isAfter(ret)) {
          date = candidate;
          note = "第 " + (i + 1) + " 天；时间待确认，景点信息来自本地知识库快照";
        } else {
          note = "第 " + (i + 1) + " 天超出「" + depart + " ~ " + ret
              + "」区间，未分配具体日期；请缩短天数或延长返回日期";
        }
      } else {
        note = "第 " + (i + 1)
            + " 天；缺少有效的出发/返回日期，未编造具体日期，补全日期后重新生成草案";
      }
      if (items.isEmpty()) {
        note += "；本日暂无可用候选景点";
      }
      days.add(new DraftDay(i + 1, date, note, items));
    }
    return List.copyOf(days);
  }

  /** 把知识库记录转成可追溯的草案条目。 */
  public static DraftDay.Item itemOf(Attraction attraction) {
    String ticket = PlaceholderValues.clean(attraction.ticket());
    String openTime = PlaceholderValues.clean(attraction.openTime());
    String source = attraction.sourceText();
    List<String> unknowns = new ArrayList<>();
    if (ticket == null) {
      unknowns.add("票价未知");
    }
    if (openTime == null) {
      unknowns.add("开放时间未知");
    }
    if (source == null || source.isBlank()) {
      source = "未标注";
      unknowns.add("来源未标注");
    }
    if (attraction.id() == null || attraction.id().isBlank()) {
      unknowns.add("景点ID缺失");
    }
    String note = "attractionId=" + attraction.id()
        + "；来源=" + source
        + "；票价=" + PlaceholderValues.display(ticket)
        + "；开放时间=" + PlaceholderValues.display(openTime)
        + "；未知项=" + (unknowns.isEmpty() ? "无" : String.join("、", unknowns));
    return new DraftDay.Item(attraction.id(), attraction.name(), attraction.city(), source,
        ticket, openTime, unknowns, note);
  }

  // ---- 补问与检查规则 ----

  private static void collectQuestions(PlanningRequest request, List<DraftQuestion> questions,
      List<DraftCheck> checks, List<String> notices) {
    if (request.departDate() == null) {
      questions.add(question("MISSING_DEPART_DATE", "departDate", "请填写出发日期",
          "YYYY-MM-DD，例如 2026-11-20"));
    }
    if (request.returnDate() == null) {
      questions.add(question("MISSING_RETURN_DATE", "returnDate", "请填写返回日期",
          "YYYY-MM-DD，不得早于出发日期"));
    }
    if (request.days() == null || request.days() <= 0) {
      if (request.dateRangeDays() == null) {
        questions.add(question("MISSING_DAYS", "days", "请填写计划游玩天数",
            "1~30 的整数；也可以改为填写出发与返回日期，由系统换算"));
      } else {
        notices.add("未提供游玩天数，按出发/返回日期推算为 " + request.dateRangeDays() + " 天");
      }
    } else if (request.days() > MAX_DAYS) {
      checks.add(new DraftCheck("DAYS_TOO_LARGE", "MEDIUM", "date",
          "游玩天数 " + request.days() + " 超过上限 " + MAX_DAYS + "，逐日草案只生成前 "
              + MAX_DAYS + " 天", "把天数调整到 " + MAX_DAYS + " 天以内"));
    }
    if (request.travelers() == null) {
      questions.add(question("MISSING_TRAVELERS", "travelers", "请填写同行人数",
          "正整数，用于计算人均费用与门票合计"));
    } else if (request.travelers() <= 0) {
      checks.add(new DraftCheck("INVALID_TRAVELERS", "HIGH", "budget",
          "同行人数为 " + request.travelers() + "，无法计算费用分摊", "填写大于 0 的人数"));
    }
    if (request.budget() == null) {
      questions.add(question("MISSING_BUDGET", "budget", "请填写预算上限",
          "数字，单位由 currency 决定（默认 CNY）"));
    } else if (request.budget().signum() <= 0) {
      checks.add(new DraftCheck("INVALID_BUDGET", "HIGH", "budget",
          "预算上限为 " + request.budget().toPlainString() + "，不是有效的上限",
          "填写大于 0 的预算，或留空表示暂不核对"));
    }
  }

  private static DraftQuestion question(String code, String field, String text, String hint) {
    return new DraftQuestion(code, field, text, hint, RETRY);
  }

  private static void collectDateChecks(PlanningRequest request, int plannedDays,
      List<DraftCheck> checks) {
    LocalDate depart = request.departDate();
    LocalDate ret = request.returnDate();
    if (depart == null || ret == null) {
      checks.add(new DraftCheck("DATE_INCOMPLETE", "INFO", "date",
          "出发日期或返回日期缺失，逐日草案不含具体日历日期",
          "补全 departDate 与 returnDate 后重新生成草案"));
      return;
    }
    if (ret.isBefore(depart)) {
      checks.add(new DraftCheck("RETURN_BEFORE_DEPART", "HIGH", "date",
          "返回日期 " + ret + " 早于出发日期 " + depart, "改正日期后再确认保存，否则行程接口会拒绝"));
      return;
    }
    if (depart.isBefore(LocalDate.now())) {
      checks.add(new DraftCheck("DEPART_IN_PAST", "MEDIUM", "date",
          "出发日期 " + depart + " 早于今天，请确认是否写错了年份", "核对出发日期"));
    }
    Integer span = request.dateRangeDays();
    if (span != null && request.days() != null && request.days() > 0
        && !request.days().equals(span)) {
      checks.add(new DraftCheck("DAYS_DATE_MISMATCH", "MEDIUM", "date",
          "游玩天数 " + request.days() + " 与日期区间（含首尾 " + span + " 天）不一致",
          "统一 days 与日期区间，逐日草案以 days 为准"));
    }
    if (span != null && plannedDays > span) {
      checks.add(new DraftCheck("DRAFT_DAY_OUT_OF_RANGE", "HIGH", "date",
          "逐日草案共 " + plannedDays + " 天，超过日期区间 " + span + " 天，超出的天没有具体日期",
          "缩短 days 或把 returnDate 往后调"));
    }
  }

  private static DraftBudgetEstimate estimate(PlanningRequest request,
      List<Attraction> selected, List<DraftCheck> checks) {
    BigDecimal subtotal = BigDecimal.ZERO;
    int unknown = 0;
    for (Attraction attraction : selected) {
      BigDecimal known = knownTicket(attraction);
      if (known == null) {
        unknown++;
      } else {
        subtotal = subtotal.add(known);
      }
    }
    boolean travelersMissing = request.travelers() == null || request.travelers() <= 0;
    int travelers = travelersMissing ? 1 : request.travelers();
    BigDecimal total = subtotal.multiply(BigDecimal.valueOf(travelers));
    BigDecimal budget = request.budget();
    String currency = request.currencyOrDefault();

    String verdict;
    String message;
    if (budget == null) {
      verdict = "NO_BUDGET";
      message = "未提供预算上限，无法核对是否超支";
      checks.add(new DraftCheck("NO_BUDGET", "INFO", "budget", message, "填写 budget 后重新生成草案"));
    } else if (budget.signum() <= 0) {
      verdict = "INVALID_BUDGET";
      message = "预算上限不是正数，无法核对是否超支";
    } else if (total.compareTo(budget) > 0) {
      verdict = "EXCEEDS_LIMIT";
      message = "仅按已核验门票计算就已超预算：小计 " + total.toPlainString() + " " + currency
          + " > 上限 " + budget.toPlainString() + " " + currency;
      checks.add(new DraftCheck("BUDGET_EXCEEDS_LIMIT", "HIGH", "budget", message,
          "提高预算、减少景点，或核实票价后再确认保存"));
    } else if (unknown > 0 || travelersMissing) {
      verdict = "CANNOT_DETERMINE";
      message = "已核验门票小计 " + total.toPlainString() + " " + currency
          + " 未超过上限，但仍有 " + unknown + " 个景点票价未知"
          + (travelersMissing ? "、同行人数未提供" : "")
          + "，无法判定总花费是否超支";
      if (unknown > 0) {
        checks.add(new DraftCheck("UNKNOWN_TICKET_PRICE", "MEDIUM", "budget",
            "有 " + unknown + " 个景点票价未知或未核验，未计入预算粗估",
            "核实票价后再判断；未知不等于免费"));
      }
    } else {
      verdict = "WITHIN_KNOWN_SCOPE";
      message = "已核验门票小计 " + total.toPlainString() + " " + currency
          + " 未超过上限（仅门票口径）";
    }
    String scope = "统计范围：仅已核验且能解析出金额的门票，按 " + travelers + " 人计；"
        + (travelersMissing ? "人数未提供，按 1 人保守估算；" : "")
        + "不含交通、住宿、餐饮，" + unknown + " 个景点票价未知未计入。";
    return new DraftBudgetEstimate(currency, budget, request.travelers(), subtotal, total,
        unknown, verdict, message + "。" + scope);
  }

  /** 只有状态可信且金额可解析的票价才参与估算；「免费」按 0 计。 */
  private static BigDecimal knownTicket(Attraction attraction) {
    String status = attraction.ticketStatus();
    boolean trusted = status != null
        && ("verified".equalsIgnoreCase(status) || "confirmed".equalsIgnoreCase(status));
    String ticket = PlaceholderValues.clean(attraction.ticket());
    if (!trusted || ticket == null) {
      return null;
    }
    if (ticket.contains("免费")) {
      return BigDecimal.ZERO;
    }
    Matcher matcher = AMOUNT.matcher(ticket);
    if (!matcher.find()) {
      return null;
    }
    try {
      return new BigDecimal(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
