package com.travelassistant.server.domain.decision;

/**
 * 投票选项（V3 第 1 节）。
 *
 * <p>只有「支持 / 反对 / 弃权」三档，不做 1~5 星打分。理由：多人决策里需要区分的是
 * 「想去」和「不想去」，打分反而会让人在无所谓的选项上随手给 3 分，
 * 把「没意见」伪装成中间态度。弃权是明确表达「我不参与这项决定」。
 */
public enum VoteChoice {
  /** 支持。 */
  SUPPORT("支持"),
  /** 反对。 */
  OPPOSE("反对"),
  /** 弃权。 */
  ABSTAIN("弃权");

  private final String label;

  VoteChoice(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
