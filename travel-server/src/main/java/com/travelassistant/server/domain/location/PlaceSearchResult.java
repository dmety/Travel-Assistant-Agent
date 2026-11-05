package com.travelassistant.server.domain.location;

import java.time.Instant;
import java.util.List;

/**
 * 地点检索结果。
 *
 * @param keyword    本次检索关键词
 * @param city       限定城市，可为空（不限城市）
 * @param places     命中地点
 * @param source     数据来源，固定标明「高德地图 Web 服务 · 地点检索」
 * @param queriedAt  查询时间，供用户判断信息新鲜度
 * @param notice     需要用户知道的补充说明（如「结果为空，换个关键词试试」）
 */
public record PlaceSearchResult(
    String keyword,
    String city,
    List<Place> places,
    String source,
    Instant queriedAt,
    String notice) {

  public PlaceSearchResult {
    places = places == null ? List.of() : List.copyOf(places);
  }
}
