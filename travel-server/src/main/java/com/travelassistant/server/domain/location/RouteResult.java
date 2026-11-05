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
    Instant queriedAt) {

  public RouteResult {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /** 一个路线步骤。 */
  public record Step(String instruction, String road, int distanceMeters, int durationSeconds) {
  }
}
