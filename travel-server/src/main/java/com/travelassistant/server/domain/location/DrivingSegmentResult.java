package com.travelassistant.server.domain.location;

import java.time.Instant;

/** 自驾单路段：地图驾车路线与目的地附近停车 POI。 */
public record DrivingSegmentResult(String origin, String destination,
    RouteResult route, ParkingSearchResult parking, Instant queriedAt) {
}
