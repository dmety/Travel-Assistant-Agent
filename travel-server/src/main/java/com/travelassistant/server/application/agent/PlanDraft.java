package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import java.time.Instant;
import java.util.List;

/**
 * 规划草案：工具查到的**事实**，加上可选的模型建议。
 *
 * <p>结构上刻意把「查到的东西」和「模型说的话」分开：{@code candidates} 全部来自本地景点库
 * 或高德接口，带来源与查询时间；{@code advice} 只是文字建议，可以为空。这样即使模型不可用，
 * 用户拿到的仍是一份有依据的候选清单，而不是一句「服务不可用」。
 *
 * <p>2026-11-08 补齐「基础业务闭环」：新增 {@code questions}（结构化补问）、
 * {@code dailySchedules}（带景点 ID / 来源 / 未知项的逐日草案）、{@code checks} 与
 * {@code budgetEstimate}（草案阶段的日期与预算检查）。这四项全部由
 * {@link DraftAssembler} 的确定性规则算出，与模型是否可用无关。
 *
 * <p>本结果**不会被自动保存**。需求明确要求「用户确认后才将建议保存到正式方案」，
 * 保存必须走行程接口，由用户显式发起。
 *
 * @param candidates     候选景点（来自本地知识库）
 * @param weather        目的地天气预报，未查询或高德不可用时为 {@code null}
 * @param tickets        候选景点的购票辅助信息（仅对前几个候选查询）
 * @param advice         模型生成的建议文本；模型未配置或调用失败时为 {@code null}
 * @param notices        需要用户知道的情况（缺字段、工具未启用、模型不可用等）
 * @param sources        本次结果用到的数据来源
 * @param questions      缺项补问，空列表表示需求已填全
 * @param dailySchedules 按天草案，天数无法确定时为空列表
 * @param checks         日期与预算检查结论
 * @param budgetEstimate 预算粗估与统计范围说明
 * @param generatedAt    生成时间
 */
public record PlanDraft(
    PlanningRequest request,
    List<Attraction> candidates,
    WeatherResult weather,
    List<TicketAdvice> tickets,
    String advice,
    List<String> notices,
    List<String> sources,
    List<DraftQuestion> questions,
    List<DraftDay> dailySchedules,
    List<DraftCheck> checks,
    DraftBudgetEstimate budgetEstimate,
    Instant generatedAt) {

  public PlanDraft {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    tickets = tickets == null ? List.of() : List.copyOf(tickets);
    notices = notices == null ? List.of() : List.copyOf(notices);
    sources = sources == null ? List.of() : List.copyOf(sources);
    questions = questions == null ? List.of() : List.copyOf(questions);
    dailySchedules = dailySchedules == null ? List.of() : List.copyOf(dailySchedules);
    checks = checks == null ? List.of() : List.copyOf(checks);
  }
}
