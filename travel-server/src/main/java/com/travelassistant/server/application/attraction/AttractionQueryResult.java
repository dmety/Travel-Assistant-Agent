package com.travelassistant.server.application.attraction;

import com.travelassistant.server.domain.attraction.Attraction;

/**
 * 景点查询结果。
 *
 * @param matched 命中总数（截断前）
 * @param returned 实际返回条数
 * @param limit 生效的条数上限
 * @param message 面向用户的提示；有结果时为 {@code null}
 * @param items 结果列表（只读）
 */
public record AttractionQueryResult(
    int matched,
    int returned,
    int limit,
    String message,
    java.util.List<Attraction> items) {

  public AttractionQueryResult {
    items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
  }

  public static AttractionQueryResult empty(int limit, String message) {
    return new AttractionQueryResult(0, 0, limit, message, java.util.List.of());
  }
}
