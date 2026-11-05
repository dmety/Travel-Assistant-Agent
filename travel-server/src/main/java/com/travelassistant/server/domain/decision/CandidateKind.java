package com.travelassistant.server.domain.decision;

/**
 * 候选项类型（V3 第 1 节）。
 *
 * <p>区分类型是为了让「确认加入行程」知道该往哪个板块落：景点进每日安排，
 * 住宿进住宿板块。其它类型只作为讨论素材，不提供自动落库。
 */
public enum CandidateKind {
  /** 景点。 */
  ATTRACTION("景点"),
  /** 住宿。 */
  LODGING("住宿"),
  /** 其它（餐厅、演出等）。 */
  OTHER("其它");

  private final String label;

  CandidateKind(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
