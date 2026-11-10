package com.travelassistant.server.domain.location;

/**
 * 一个地点（高德地点检索结果）。
 *
 * <p>坐标统一为高德 GCJ-02（火星坐标）。跨源比对时不能与 WGS-84 直接混用，
 * 因此这里显式带出 {@code coordinateSystem}，避免下游忘了这件事。
 */
public record Place(
    String name,
    String type,
    String address,
    String city,
    String district,
    String tel,
    Double lat,
    Double lon,
    String coordinateSystem,
    String distanceMeters,
    /** 地图 POI 用户评分（5 分制），无评分时为 null；不是票价、不是人均消费。 */
    Double rating,
    /** 该评分对应的评价数，地图没有提供时为 null；只用于排序可信度与展示，不当成好评数。 */
    Integer commentCount) {
}
