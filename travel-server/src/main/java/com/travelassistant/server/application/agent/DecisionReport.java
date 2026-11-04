package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.budget.BudgetAnalysis;
import com.travelassistant.server.domain.budget.ItineraryChecks;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.location.WeatherResult;
import java.time.Instant;
import java.util.List;

/**
 * 综合决策建议（V3 第 3 节）。
 *
 * <p>结构上把三样东西分开摆：
 * <ul>
 *   <li>{@code votes}/{@code budget}/{@code checks}/{@code weather} —— 已有的**事实**，
 *       原样带回来，用户自己也能核对；</li>
 *   <li>{@code risks} 与 {@code suggestions} —— 由 Java 规则算出来的，确定性、可复现，
 *       测试能逐条断言；</li>
 *   <li>{@code advice} —— 模型基于上面两块组织出来的文字，**可以为空**。</li>
 * </ul>
 *
 * <p>这个分层是刻意的：模型不可用时，用户仍然拿得到全部风险和可执行建议；
 * 模型说得再好，也改不了 {@code risks} 里的任何一条。
 *
 * @param risks       潜在风险（严重度 HIGH/MEDIUM/INFO），按严重度排序
 * @param suggestions 可讨论的调整方案，来自规则而非模型
 * @param advice      模型文字建议；未配置密钥或调用失败时为 {@code null}
 * @param notices     需要用户知道的情况（降级原因、数据缺口等）
 * @param sources     本次建议用到的数据来源
 */
public record DecisionReport(
    String itineraryId,
    long version,
    String title,
    CandidateBoard votes,
    BudgetAnalysis budget,
    ItineraryChecks checks,
    WeatherResult weather,
    List<Risk> risks,
    List<String> suggestions,
    String advice,
    List<String> notices,
    List<String> sources,
    Instant generatedAt) {

  public DecisionReport {
    risks = risks == null ? List.of() : List.copyOf(risks);
    suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    notices = notices == null ? List.of() : List.copyOf(notices);
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /**
   * 一条风险。
   *
   * @param severity {@code HIGH}（会直接导致行程不可行）、{@code MEDIUM}（需要人来判断）、
   *                 {@code INFO}（提醒，不一定要改）
   * @param basis    这条判断依据的是哪些数据（如「投票」「预算」「每日安排」），
   *                 用来回答「你凭什么这么说」
   */
  public record Risk(String code, String severity, String message, String basis,
      String suggestion) {
  }
}
