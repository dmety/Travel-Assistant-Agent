package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 逐日草案的地理顺序优化：把「西湖 → 千岛湖 → 灵隐寺」这种来回折返改成就近串联。
 *
 * <p>只做一件事：**重排 {@code List<Attraction>}**。不生成游览时刻、不写距离或耗时、
 * 不改变每天景点数量、不改日期与 note。项目硬口径是「知识库里没有排程依据，写一个
 * 09:00 等于凭空编造」，所以这里的目标函数只用球面直线距离，绝不引入步行分钟、车程
 * 之类的伪耗时。
 *
 * <p>坐标系口径与美食 Agent 一致：知识库混存 WGS-84 / GCJ-02，先统一换算到 GCJ-02
 * 再算 {@link CoordinateTransforms#haversineMeters}。坐标或坐标系标签缺失、标签不可
 * 识别时一律跳过该点，绝不猜「大概是 GCJ-02」；可靠坐标不足或最高优先级候选没有
 * 坐标时，整批保持原顺序（降级为原来的确定性排序）。
 *
 * <p>首个候选是 {@code PlanningTools.rank()} 按「等级 → 评分 → 名称」选出的最高优先级
 * 景点，固定为路线起点：既保住推荐语义，也让「第一天第一个点」不因优化而漂移。
 * 2-opt 只调整起点之后的片段，且用「总直线距离变小」作为唯一判据。
 */
public final class DraftRouteOptimizer {

  /** 2-opt 最多迭代几轮；候选最多 10 个，收敛很快，不做无界搜索。 */
  private static final int MAX_PASSES = 6;

  /** 距离差小于 1 米视为无改进，避免浮点噪声反复翻转片段。 */
  private static final double MIN_GAIN_METERS = 1.0;

  private DraftRouteOptimizer() {
  }

  /**
   * @param source 按推荐优先级排好的候选列表，不会被修改
   * @return 就近重排后的新列表；不满足优化条件时原样返回入参
   */
  public static List<Attraction> optimize(List<Attraction> source) {
    if (source == null || source.size() < 3) {
      return source == null ? List.of() : source;
    }
    int n = source.size();
    double[] lat = new double[n];
    double[] lon = new double[n];
    boolean[] located = new boolean[n];
    int locatedCount = 0;
    for (int i = 0; i < n; i++) {
      Attraction a = source.get(i);
      if (a == null || a.lat() == null || a.lon() == null || a.coordinateSystem() == null) {
        continue;
      }
      CoordinateTransforms.LatLon gcj =
          CoordinateTransforms.toGcj02(a.lat(), a.lon(), a.coordinateSystem());
      if (gcj == null) {
        continue;  // 标签不可识别：不猜坐标系
      }
      lat[i] = gcj.lat();
      lon[i] = gcj.lon();
      located[i] = true;
      locatedCount++;
    }
    if (locatedCount < 3 || !located[0]) {
      return source;
    }

    boolean[] used = new boolean[n];
    List<Integer> route = nearestNeighbor(located, lat, lon, n, locatedCount, used);
    twoOpt(route, lat, lon);

    List<Attraction> ordered = new ArrayList<>(n);
    for (int index : route) {
      ordered.add(source.get(index));
    }
    // 没有可靠坐标的点一个都不丢，保持原相对顺序附在末尾；
    // 它们不参与距离计算，也就不会因为缺数据被硬塞进某一天。
    for (int i = 0; i < n; i++) {
      if (!used[i]) {
        ordered.add(source.get(i));
      }
    }
    return ordered;
  }

  /** 首项固定的最近邻：每一步走到离当前点最近的未访问点。 */
  private static List<Integer> nearestNeighbor(boolean[] located, double[] lat, double[] lon,
      int n, int locatedCount, boolean[] used) {
    List<Integer> route = new ArrayList<>(locatedCount);
    int current = 0;
    route.add(current);
    used[current] = true;
    while (route.size() < locatedCount) {
      int best = -1;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int i = 1; i < n; i++) {
        if (!located[i] || used[i]) {
          continue;
        }
        double d = distance(current, i, lat, lon);
        if (d < bestDistance) {
          bestDistance = d;
          best = i;
        }
      }
      if (best < 0) {
        break;
      }
      route.add(best);
      used[best] = true;
      current = best;
    }
    return route;
  }

  /** 经典 2-opt：反转能缩短总路程的片段，首项始终固定。 */
  private static void twoOpt(List<Integer> route, double[] lat, double[] lon) {
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      boolean improved = false;
      for (int i = 1; i < route.size() - 2; i++) {
        for (int k = i + 1; k < route.size() - 1; k++) {
          int a = route.get(i - 1);
          int b = route.get(i);
          int c = route.get(k);
          int d = route.get(k + 1);
          double before = distance(a, b, lat, lon) + distance(c, d, lat, lon);
          double after = distance(a, c, lat, lon) + distance(b, d, lat, lon);
          if (after + MIN_GAIN_METERS < before) {
            Collections.reverse(route.subList(i, k + 1));
            improved = true;
          }
        }
      }
      if (!improved) {
        return;
      }
    }
  }

  private static double distance(int from, int to, double[] lat, double[] lon) {
    return CoordinateTransforms.haversineMeters(lat[from], lon[from], lat[to], lon[to]);
  }
}
