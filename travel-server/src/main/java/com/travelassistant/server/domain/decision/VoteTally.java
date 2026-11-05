package com.travelassistant.server.domain.decision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投票结果的纯计算规则（不含 IO 与 Spring，便于单独验证）。
 *
 * <p>三条口径，都是刻意选的：
 * <ul>
 *   <li><b>排序用净支持数（支持 − 反对）</b>，而不是「总票数」。3 人支持、2 人反对的候选
 *       排在 4 人支持、4 人反对的前面，因为后者实际上是一次僵持。</li>
 *   <li><b>弃权不计分但计人</b>：弃权不影响排名，但要让用户知道「这个人看过了，只是没意见」。</li>
 *   <li><b>平票不选赢家</b>：榜首并列时 {@code leaderId} 为 {@code null} 并给出提示。
 *       系统没有资格用「谁先提的」替一群人做决定——需求写的是「由用户决定最终选择」。</li>
 * </ul>
 */
public final class VoteTally {

  private VoteTally() {
  }

  /** 汇总成看板。 */
  public static CandidateBoard board(String itineraryId, long itineraryVersion,
      int participantCount, List<Candidate> candidates, List<Vote> votes) {
    Map<String, List<Vote>> byCandidate = new LinkedHashMap<>();
    for (Vote v : votes) {
      byCandidate.computeIfAbsent(v.candidateId(), k -> new ArrayList<>()).add(v);
    }

    List<CandidateTally> tallies = new ArrayList<>();
    for (Candidate c : candidates) {
      List<Vote> mine = byCandidate.getOrDefault(c.id(), List.of());
      int support = 0;
      int oppose = 0;
      int abstain = 0;
      List<CandidateTally.VoterChoice> choices = new ArrayList<>();
      for (Vote v : mine) {
        switch (v.choice()) {
          case SUPPORT -> support++;
          case OPPOSE -> oppose++;
          case ABSTAIN -> abstain++;
        }
        choices.add(new CandidateTally.VoterChoice(v.voter(), v.choice()));
      }
      tallies.add(new CandidateTally(c, support, oppose, abstain, support - oppose,
          List.copyOf(choices)));
    }

    tallies.sort(Comparator
        .comparingInt(CandidateTally::score).reversed()
        .thenComparing(Comparator.comparingInt(CandidateTally::support).reversed())
        .thenComparingInt(CandidateTally::oppose)
        .thenComparing(t -> t.candidate().createdAt()));

    int totalVotes = tallies.stream().mapToInt(CandidateTally::total).sum();
    int voterCount = (int) votes.stream().map(Vote::voter).distinct().count();

    String leaderId = null;
    boolean tie = false;
    if (!tallies.isEmpty()) {
      CandidateTally first = tallies.get(0);
      if (first.total() > 0) {
        leaderId = first.candidate().id();
        if (tallies.size() > 1) {
          CandidateTally second = tallies.get(1);
          if (second.total() > 0 && second.score() == first.score()
              && second.support() == first.support()) {
            tie = true;
            leaderId = null;
          }
        }
      }
    }

    List<String> notes = new ArrayList<>();
    if (candidates.isEmpty()) {
      notes.add("还没有候选项，成员可以先添加想去的地方");
    } else if (totalVotes == 0) {
      notes.add("还没有人投票，结果不构成任何结论");
    } else if (tie) {
      notes.add("榜首并列（同为 " + tallies.get(0).score() + " 净支持），投票无法分出高下，请讨论后再定");
    }
    int silent = participantCount - voterCount;
    if (silent > 0 && totalVotes > 0) {
      notes.add("还有 " + silent + " 位参与者没有投票，当前结果只代表已投票的人");
    }
    notes.add("投票只用于讨论，不会自动改动行程；确认后需由有权限的成员写入正式安排");

    return new CandidateBoard(itineraryId, itineraryVersion, participantCount, voterCount,
        tallies.size(), totalVotes, List.copyOf(tallies), leaderId, tie, List.copyOf(notes));
  }
}
