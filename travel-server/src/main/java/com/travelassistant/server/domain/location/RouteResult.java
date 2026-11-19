package com.travelassistant.server.domain.location;

import java.time.Instant;
import java.util.List;

/**
 * 两点之间的路线结果。
 *
 * <p>距离与耗时都是**接口给出的预估**，不含实时路况以外的等车、排队、检票时间。
 * 生成建议时要说明这一点，不能把 42 分钟当成「一定能 42 分钟到」。
 *
 * @param distanceMeters  总距离（米）
 * @param durationSeconds 预估总耗时（秒）
 * @param distanceText    人性化距离，如「3.2 公里」
 * @param durationText    人性化耗时，如「42 分钟」
 * @param steps           主要路线步骤（最多保留 8 步，避免刷屏）
 */
public record RouteResult(
    RouteMode mode,
    String originLngLat,
    String destinationLngLat,
    int distanceMeters,
    int durationSeconds,
    String distanceText,
    String durationText,
    List<Step> steps,
    String source,
    Instant queriedAt, Integer walkingDistanceMeters, Fare fare, List<RailSegment> railways, java.time.LocalDate requestedDate, String originCity, String destinationCity) {
  public RouteResult(RouteMode mode,String originLngLat,String destinationLngLat,int distanceMeters,int durationSeconds,
      String distanceText,String durationText,List<Step> steps,String source,Instant queriedAt,Integer walkingDistanceMeters,Fare fare,List<RailSegment> railways) {
    this(mode,originLngLat,destinationLngLat,distanceMeters,durationSeconds,distanceText,durationText,steps,source,queriedAt,walkingDistanceMeters,fare,railways,null,null,null);
  }
  public record Fare(java.math.BigDecimal transitPerPersonCny, java.math.BigDecimal drivingTollsPerVehicleCny,
      java.math.BigDecimal taxiAlternativePerVehicleCny, String currency, String priceType, String source, Instant queriedAt) {}
  public record RailSegment(String trip, String departureStation, String departureTime, String arrivalStation,
      String arrivalTime, List<RailSeat> seats, String verification) {}
  public record RailSeat(String code, java.math.BigDecimal referencePriceCny) {}
  public RouteResult(RouteMode mode,String originLngLat,String destinationLngLat,int distanceMeters,int durationSeconds,
      String distanceText,String durationText,List<Step> steps,String source,Instant queriedAt,Integer walkingDistanceMeters) {
    this(mode,originLngLat,destinationLngLat,distanceMeters,durationSeconds,distanceText,durationText,steps,source,queriedAt,walkingDistanceMeters,null,List.of());
  }

  public RouteResult(RouteMode mode, String originLngLat, String destinationLngLat, int distanceMeters,
      int durationSeconds, String distanceText, String durationText, List<Step> steps, String source, Instant queriedAt) {
    this(mode, originLngLat, destinationLngLat, distanceMeters, durationSeconds, distanceText, durationText, steps, source, queriedAt,
        mode == RouteMode.WALKING ? distanceMeters : null);
  }

  public RouteResult {
    railways=railways==null?List.of():railways.stream().filter(r -> present(r.trip()) && present(r.departureStation()) && present(r.arrivalStation())).distinct().toList();
    if(fare!=null) fare=new Fare(positive(fare.transitPerPersonCny()),fare.drivingTollsPerVehicleCny(),
        positive(fare.taxiAlternativePerVehicleCny()),fare.currency(),fare.priceType(),fare.source(),fare.queriedAt());
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
  private static java.math.BigDecimal positive(java.math.BigDecimal n) { return n!=null && n.signum()>0?n:null; }
  private static boolean present(String s) { return s!=null && !s.isBlank() && !s.equalsIgnoreCase("null"); }

  /** 一个路线步骤。 */
  public record Step(String instruction, String road, int distanceMeters, int durationSeconds) {
  }
}
