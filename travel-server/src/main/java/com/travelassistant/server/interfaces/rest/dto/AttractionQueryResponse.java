package com.travelassistant.server.interfaces.rest.dto;

import com.travelassistant.server.application.attraction.AttractionQueryResult;
import java.util.List;

/**
 * 景点查询响应。
 *
 * @param matched 命中总数（截断前）
 * @param returned 实际返回条数
 * @param limit 生效的条数上限
 * @param message 无结果或缺参时的提示，有结果时为 {@code null}
 * @param items 结果列表
 */
public record AttractionQueryResponse(
    int matched,
    int returned,
    int limit,
    String message,
    List<AttractionView> items) {

  public static AttractionQueryResponse from(AttractionQueryResult result) {
    return new AttractionQueryResponse(
        result.matched(),
        result.returned(),
        result.limit(),
        result.message(),
        result.items().stream().map(AttractionView::from).toList());
  }
}
