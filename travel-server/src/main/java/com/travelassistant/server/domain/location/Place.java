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
    String distanceMeters) {
}
