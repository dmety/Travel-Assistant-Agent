package com.travelassistant.server.domain.food;

import java.time.Instant;

/**
 * 一次餐饮 POI 召回的结果快照，坐标统一为 GCJ-02。
 *
 * <p>这是**运行期临时对象**：地图服务条款禁止把 POI 原始字段落库、缓存成数据集或再分发。
 * 它只在本次请求的响应里出现，不写 {@code data/}、不进知识库、不进日志正文。
 * 所有可空字段表示「接口没给」，调用方必须写成「未知」，不得补默认值。
 */
public record FoodPlace(
    String poiId,
    String name,
    Double lat,
    Double lon,
    String tags,
    Double rating,
    Double pricePerPersonCny,
    String openHours,
    Integer commentCount,
    String source,
    Instant fetchedAt) {

  public static final String SOURCE_AMAP = "AMAP";
  public static final String SOURCE_BAIDU = "BAIDU";

  /** 缺名称或缺坐标的 POI 不能成为候选：距离无法计算，也不能靠猜补。 */
  public boolean usable() {
    return name != null && !name.isBlank() && lat != null && lon != null;
  }
}
