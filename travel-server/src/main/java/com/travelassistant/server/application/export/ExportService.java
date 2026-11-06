package com.travelassistant.server.application.export;

import com.travelassistant.server.application.budget.BudgetService;
import com.travelassistant.server.application.decision.CandidateService;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.BudgetSummary;
import com.travelassistant.server.domain.budget.CategoryTotal;
import com.travelassistant.server.domain.budget.CheckWarning;
import com.travelassistant.server.domain.budget.CurrencyTotal;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.decision.CandidateTally;
import com.travelassistant.server.domain.itinerary.DailyActivity;
import com.travelassistant.server.domain.itinerary.DailySchedule;
import com.travelassistant.server.domain.itinerary.Expense;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Lodging;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.domain.itinerary.Transport;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 行程导出（V3 第 4 节）：把方案、成员、预算和体检结果拼成一份人能读的纯文本摘要。
 *
 * <p>刻意导出**文本**而不是 PDF/Word：需求要的是「可阅读的文本摘要」，纯文本没有字体、
 * 分页和渲染依赖，任何编辑器都能打开，也便于贴进群聊讨论。客户端拿到后自己落盘，
 * 服务端不写用户的文件系统。
 *
 * <p>几条硬规则：
 * <ul>
 *   <li>未知就写「未填写」，不拿占位数字糊过去；</li>
 *   <li>不同币种分行列出，绝不合并成一个数字；</li>
 *   <li>结尾必须写明「不构成预订承诺、价格以官方为准」——导出件会被转发，
 *       脱离上下文后很容易被当成价格凭据。</li>
 * </ul>
 */
@Service
public class ExportService {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  private final ItineraryService itineraries;
  private final BudgetService budgets;
  private final CandidateService candidates;

  public ExportService(ItineraryService itineraries, BudgetService budgets,
      CandidateService candidates) {
    this.itineraries = itineraries;
    this.budgets = budgets;
    this.candidates = candidates;
  }

  /** 导出文本摘要。 */
  public String toText(String itineraryId, String user) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    BudgetSummary summary = budgets.summarize(itineraryId, user);
    BudgetAnalysis analysis = budgets.analyze(itineraryId, user, null);
    ItineraryChecks checks = budgets.check(itineraryId, user);
    CandidateBoard board = candidates.boardOf(itinerary);
    return render(itinerary, summary, analysis, checks, board);
  }

  /** 建议的下载文件名（去掉文件系统不接受的字符）。 */
  public String fileName(String itineraryId, String user) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    String base = itinerary.title() == null ? "行程" : itinerary.title();
    return base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".txt";
  }

  private String render(Itinerary it, BudgetSummary summary, BudgetAnalysis analysis,
      ItineraryChecks checks, CandidateBoard board) {
    StringBuilder sb = new StringBuilder();
    Plan plan = it.plan() == null ? Plan.empty() : it.plan();

    sb.append("行程摘要：").append(text(it.title(), "未命名行程")).append('\n');
    sb.append("创建者：").append(it.owner()).append('\n');
    sb.append("状态：").append(it.archived() ? "已归档（只读）" : "进行中").append('\n');
    sb.append("目的地：").append(text(plan.destination(), "未填写")).append('\n');
    sb.append("时间：").append(dateRange(plan)).append('\n');
    sb.append("版本：v").append(it.version()).append("　导出时间：")
        .append(STAMP.format(java.time.Instant.now())).append('\n');
    sb.append('\n');

    // ---- 成员 ----
    sb.append("【成员】\n");
    sb.append("- ").append(it.owner()).append("（创建者，全部权限）\n");
    if (it.members().isEmpty()) {
      sb.append("- 暂无其他成员\n");
    } else {
      for (Member m : it.members()) {
        sb.append("- ").append(m.username()).append("（")
            .append(m.editSections().isEmpty() ? "只读" : "可编辑：" + sections(m.editSections()))
            .append("）\n");
      }
    }
    sb.append('\n');

    // ---- 方案 ----
    sb.append("【方案】\n");
    sb.append("概述：").append(text(plan.summary(), "未填写")).append('\n');
    sb.append("备注：").append(text(plan.notes(), "未填写")).append('\n');
    sb.append('\n');

    // ---- 每日安排 ----
    sb.append("【每日安排】\n");
    if (it.dailySchedules().isEmpty()) {
      sb.append("- 暂无安排\n");
    } else {
      for (DailySchedule day : it.dailySchedules()) {
        sb.append(day.date() == null ? "日期未填写" : day.date().toString());
        if (day.note() != null && !day.note().isBlank()) {
          sb.append("（").append(day.note()).append("）");
        }
        sb.append('\n');
        if (day.activities().isEmpty()) {
          sb.append("  - 当天没有具体活动\n");
        }
        for (DailyActivity a : day.activities()) {
          sb.append("  - ").append(timeRange(a)).append(text(a.title(), "未命名"))
              .append(place(a.location())).append(noteSuffix(a.note())).append('\n');
          if (a.ticketRecord() != null) {
            var r = a.ticketRecord();
            sb.append("    用户自报已购票／已预约，未经平台核验：")
                .append(r.product()).append(" / ").append(r.visitDate())
                .append(" / ").append(r.session() == null ? "场次未填" : r.session())
                .append(" / ").append(r.travelers()).append("人 / 实付：")
                .append(r.paidTotal() == null ? "未知" : r.paidTotal().toPlainString() + "元")
                .append('\n');
          }
        }
      }
    }
    sb.append('\n');

    // ---- 交通 ----
    sb.append("【交通】\n");
    if (it.transports().isEmpty()) {
      sb.append("- 暂无交通安排\n");
    } else {
      for (Transport t : it.transports()) {
        sb.append("- ").append(t.date() == null ? "日期未填" : t.date().toString()).append(' ')
            .append(text(t.mode(), "交通")).append("：")
            .append(text(t.from(), "未填出发地")).append(" → ")
            .append(text(t.to(), "未填到达地"));
        if (notBlank(t.departTime()) || notBlank(t.arriveTime())) {
          sb.append("，").append(text(t.departTime(), "?")).append("–")
              .append(text(t.arriveTime(), "?"));
        }
        if (notBlank(t.flightNo())) {
          sb.append("，").append(t.flightNo());
        }
        if (notBlank(t.seat())) {
          sb.append("，").append(t.seat());
        }
        sb.append(noteSuffix(t.note())).append('\n');
      }
    }
    sb.append('\n');

    // ---- 住宿 ----
    sb.append("【住宿】\n");
    if (it.lodgings().isEmpty()) {
      sb.append("- 暂无住宿安排\n");
    } else {
      for (Lodging l : it.lodgings()) {
        sb.append("- ").append(text(l.name(), "未命名住宿"))
            .append("：").append(dateOrUnknown(l.checkIn())).append(" → ")
            .append(dateOrUnknown(l.checkOut()));
        if (notBlank(l.roomType())) {
          sb.append("，").append(l.roomType());
        }
        if (notBlank(l.address())) {
          sb.append("，").append(l.address());
        }
        sb.append(noteSuffix(l.note())).append('\n');
      }
    }
    sb.append('\n');

    // ---- 费用 ----
    sb.append("【费用】\n");
    if (it.expenses().isEmpty()) {
      sb.append("- 暂无费用记录\n");
    } else {
      for (Expense e : it.expenses()) {
        sb.append("- ").append(text(e.category(), "未分类")).append("：")
            .append(e.amount() == null ? "金额待填写" : money(e.amount()) + " " + currency(e))
            .append(e.actual() ? "（已发生）" : "（预计）");
        if (e.date() != null) {
          sb.append("，").append(e.date());
        }
        sb.append(noteSuffix(e.note())).append('\n');
      }
      sb.append("小计：");
      for (CurrencyTotal total : summary.totals()) {
        sb.append(total.currency()).append(' ').append(money(total.amount())).append("　");
      }
      sb.append('\n');
      if (!summary.byCategory().isEmpty()) {
        sb.append("分类：\n");
        for (CategoryTotal c : summary.byCategory()) {
          sb.append("  - ").append(c.category()).append("：").append(money(c.amount()))
              .append(' ').append(c.currency()).append('\n');
        }
      }
    }
    sb.append('\n');

    // ---- 预算分析 ----
    sb.append("【预算分析】\n");
    sb.append("上限：").append(analysis.limit() == null
        ? "未设定" : money(analysis.limit()) + " " + analysis.currency()).append('\n');
    sb.append("预计总额：").append(money(analysis.estimatedTotal())).append(' ')
        .append(analysis.currency()).append('\n');
    sb.append("实际总额：").append(money(analysis.actualTotal())).append(' ')
        .append(analysis.currency()).append("（已发生）\n");
    sb.append("差额：").append(analysis.remaining() == null
        ? "无法计算（未设上限或币种不一致）"
        : money(analysis.remaining()) + " " + analysis.currency()).append('\n');
    sb.append("人均：").append(analysis.travelers()).append(" 人，预计 ")
        .append(money(analysis.perPerson())).append(' ').append(analysis.currency())
        .append("／人，实际 ").append(money(analysis.perPersonActual())).append(' ')
        .append(analysis.currency()).append("／人\n");
    if (analysis.overLimit()) {
      sb.append("超支：已超出上限 ").append(money(analysis.overBy())).append(' ')
          .append(analysis.currency()).append('\n');
    }
    sb.append('\n');

    // ---- 候选与投票 ----
    sb.append("【候选与投票】\n");
    if (board.candidates().isEmpty()) {
      sb.append("- 暂无候选项\n");
    } else {
      for (CandidateTally tally : board.candidates()) {
        sb.append("- ").append(tally.candidate().name())
            .append("（").append(tally.candidate().kind().label()).append("）");
        if (tally.total() == 0) {
          sb.append("：暂无投票");
        } else {
          sb.append("：支持 ").append(tally.support())
              .append("，反对 ").append(tally.oppose())
              .append("，弃权 ").append(tally.abstain());
        }
        if (tally.candidate().id().equals(board.leaderId())) {
          sb.append("　← 当前票数最高");
        }
        sb.append('\n');
      }
      for (String note : board.notes()) {
        sb.append("  注：").append(note).append('\n');
      }
    }
    sb.append('\n');

    // ---- 体检 ----
    sb.append("【行程检查】\n");
    if (checks.warnings().isEmpty()) {
      sb.append("- 未发现问题\n");
    } else {
      for (CheckWarning warning : checks.warnings()) {
        sb.append("- [").append(warning.code()).append("] ")
            .append(warning.message()).append('\n');
      }
    }
    sb.append('\n');

    sb.append("说明：本摘要由系统按当前记录生成，票价、余票与开放时间以景区官方渠道为准，\n");
    sb.append("不构成预订承诺；同行成员的实际支出请以各自票据为准。\n");
    return sb.toString();
  }

  // ---- 格式化辅助 ----

  private String dateRange(Plan plan) {
    if (!plan.hasDateRange()) {
      String depart = plan.departDate() == null ? "未填出发" : plan.departDate().toString();
      String ret = plan.returnDate() == null ? "未填返回" : plan.returnDate().toString();
      return depart + " ~ " + ret;
    }
    long days = java.time.temporal.ChronoUnit.DAYS.between(plan.departDate(), plan.returnDate()) + 1;
    return plan.departDate() + " ~ " + plan.returnDate() + "（" + days + " 天）";
  }

  private String timeRange(DailyActivity a) {
    if (a.hasTimeRange()) {
      return a.startTime() + "–" + a.endTime() + " ";
    }
    return notBlank(a.time()) ? a.time() + " " : "";
  }

  private String place(String location) {
    return notBlank(location) ? "（" + location + "）" : "";
  }

  private String noteSuffix(String note) {
    return notBlank(note) ? "　备注：" + note : "";
  }

  private String sections(java.util.Set<Section> sections) {
    StringBuilder sb = new StringBuilder();
    for (Section s : Section.values()) {
      if (sections.contains(s)) {
        if (sb.length() > 0) {
          sb.append('、');
        }
        sb.append(switch (s) {
          case PLAN -> "方案";
          case DAILY -> "每日安排";
          case TRANSPORT -> "交通";
          case LODGING -> "住宿";
          case EXPENSE -> "费用";
        });
      }
    }
    return sb.toString();
  }

  private String money(BigDecimal amount) {
    return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private String currency(Expense e) {
    return notBlank(e.currency()) ? e.currency().toUpperCase(java.util.Locale.ROOT) : "CNY";
  }

  private String dateOrUnknown(java.time.LocalDate date) {
    return date == null ? "日期未填" : date.toString();
  }

  private boolean notBlank(String text) {
    return text != null && !text.isBlank();
  }

  private String text(String value, String fallback) {
    return notBlank(value) ? value.strip() : fallback;
  }
}
