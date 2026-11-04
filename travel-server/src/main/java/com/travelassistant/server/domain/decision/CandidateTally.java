package com.travelassistant.server.domain.decision;

import java.util.List;

/**
 * 单个候选项的投票结果。
 *
 * @param votes  每位投票者的选择，按投票时间升序
 * @param score  净支持数 = 支持 − 反对（弃权不参与计算）
 */
public record CandidateTally(
    Candidate candidate,
    int support,
    int oppose,
    int abstain,
    int score,
    List<VoterChoice> votes) {

  /** 一张可展示的选票。 */
  public record VoterChoice(String voter, VoteChoice choice) {
  }

  /** 参与表态的总人数（含弃权）。 */
  public int total() {
    return support + oppose + abstain;
  }
}
