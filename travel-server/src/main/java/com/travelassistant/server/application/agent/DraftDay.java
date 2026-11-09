package com.travelassistant.server.application.agent;

import java.time.LocalDate;
import java.util.List;

/**
 * 按天草案中的一天。
 *
 * <p>刻意不生成具体时刻：知识库里没有营业时段以外的排程依据，写一个「09:00」
 * 等于凭空编造。每天只给出候选景点、可追溯的景点 ID / 来源和明确的未知项，
 * 时间由用户确认后在正式行程里补。
 *
 * @param dayIndex 第几天，从 1 开始
 * @param date     具体日期；缺出发日期或超出旅行区间时为 {@code null}
 * @param note     这一天的说明（含日期缺失原因）
 * @param items    这一天的候选景点，可能为空
 */
public record DraftDay(
    int dayIndex,
    LocalDate date,
    String note,
    List<Item> items) {

  public DraftDay {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /**
   * 逐日草案里的一个景点。
   *
   * @param attractionId 知识库景点 ID，保存后可据此回查
   * @param name         景点名称
   * @param city         所在城市
   * @param source       该条记录的数据来源；缺失时标「未标注」
   * @param ticket       票价原文；未知为 {@code null}
   * @param openTime     开放时间原文；未知为 {@code null}
   * @param unknowns     本条记录里明确未知的项目，如「票价未知」
   * @param note         展示与落库时一并保留的证据说明
   */
  public record Item(
      String attractionId,
      String name,
      String city,
      String source,
      String ticket,
      String openTime,
      List<String> unknowns,
      String note) {

    public Item {
      unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
    }
  }
}
