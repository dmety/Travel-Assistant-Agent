package com.travelassistant.server.domain.location;

import java.time.Instant;
import java.util.List;

/** 周边停车 POI；价格、营业时间和实时空位缺少权威字段时显式为未知。 */
public record ParkingSearchResult(
    String destination,
    List<ParkingPlace> parkingLots,
    String source,
    Instant queriedAt,
    boolean liveAvailabilityAvailable,
    String notice) {

  public ParkingSearchResult {
    parkingLots = parkingLots == null ? List.of() : List.copyOf(parkingLots);
  }

  public record ParkingPlace(String name, String address, String distanceMeters,
      Double lat, Double lon, String source, Instant checkedAt,
      String price, String openingHours, String availableSpaces) {
  }
}
