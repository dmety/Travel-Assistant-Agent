package com.travelassistant.server.domain.decision;

import java.util.List;

/**
 * 某行程的候选看板（V3 第 1 节）。
 *
 * @param participantCount 应参与人数（创建者 + 成员）
 * @param voterCount       实际投过票的人数
 * @param candidates       按「净支持 → 支持数 → 添加时间」排好序的候选
 * @param leaderId         票数最高者的 ID；无人投票或平票时为 {@code null}
 * @param tie              榜首是否并列
 * @param notes            口径说明（如「还有 2 人未投票」「榜首并列，投票无法决定」）
 */
public record CandidateBoard(
    String itineraryId,
    long itineraryVersion,
    int participantCount,
    int voterCount,
    int candidateCount,
    int totalVotes,
    List<CandidateTally> candidates,
    String leaderId,
    boolean tie,
    List<String> notes) {
}
