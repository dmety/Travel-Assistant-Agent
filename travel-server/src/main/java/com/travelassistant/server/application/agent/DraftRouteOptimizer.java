package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 *
 * <p>三段式改进，目标都是「不让用户兜圈子」：
 * <ol>
 *   <li><b>最近邻</b>构造初始路线（除固定起点外每步走到最近未访问点）；</li>
 *   <li><b>2-opt</b> 反转交叉片段；<b>Or-opt</b> 把「伸出去又折回」的 1~3 个连续点整段搬到
 *       更合适的位置——2-opt 只能反转、救不了这类支线性折返，这是武汉「东湖 → 市区 → 东湖」
 *       式绕路的直接修复。两者都只在「总直线距离严格变小」时接受，起点始终钉死。</li>
 *   <li><b>锚点片区排序</b>（仅在传入城市锚点时）：把路线按大段间距切成若干地理片区，
 *       含首选点的片区排第一，其余片区按离锚点由近及远排列，消除「市区 ↔ 远郊」在多天之间横跳。</li>
 * </ol>
 *
 * <p>候选最多十几个，全部启发式都收敛很快，不做无界搜索；Or-opt 每轮重算整条路径长度，
 * 在这个规模下开销可忽略，换来的是逻辑不易出错。
 */
public final class DraftRouteOptimizer {

  /** 2-opt / Or-opt 最多迭代几轮；候选最多 10 个，收敛很快，不做无界搜索。 */
  private static final int MAX_PASSES = 6;

  /** 距离差小于 1 米视为无改进，避免浮点噪声反复翻转片段。 */
  private static final double MIN_GAIN_METERS = 1.0;

  /** Or-opt 一次搬动的最大连续点数；再多就接近全排列重排，失去「只消折返」的克制。 */
  private static final int OR_OPT_MAX_SEGMENT = 3;

  /** 切分地理片区的相邻跳阈值：超过它视为「换了一片地方」，用于锚点排序。 */
  private static final double CLUSTER_LINK_METERS = 40_000;

  private DraftRouteOptimizer() {
  }

  /**
   * @param source 按推荐优先级排好的候选列表，不会被修改
   * @return 就近重排后的新列表；不满足优化条件时原样返回入参
   */
  public static List<Attraction> optimize(List<Attraction> source) {
    return optimize(source, null);
  }

  /**
   * 带城市/住宿锚点的版本。
   *
   * @param anchor 参考点 {@code {纬度, 经度}}（GCJ-02），传 {@code null} 时只做路径优化、
   *               不重排片区先后。锚点仅影响「多个地理片区谁先谁后」，绝不改变首选点排第一，
   *               也不引入距离/耗时之外的任何编造字段。
   */
  public static List<Attraction> optimize(List<Attraction> source, double[] anchor) {
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
    List<Integer> route = routeAcrossClusters(located, lat, lon, n, anchor);
    for (int index : route) {
      used[index] = true;
    }

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

  /**
   * 跨片区链式排路：先按 {@link #CLUSTER_LINK_METERS} 单链接把可靠坐标点连成地理片区，
   * 含首选点（下标 0）的片区固定排第一，其余片区按「片区内最近点离城市锚点由近及远」排列
   * （无锚点时按离首选点的距离）；每个片区内部用最近邻 + 2-opt + Or-opt 就近串联，
   * 片区之间从上一片区的终点就近接入下一片区。这样多天行程会「扫完一片再换一片」，
   * 而不是市区与远郊来回横跳。
   */
  private static List<Integer> routeAcrossClusters(boolean[] located, double[] lat, double[] lon,
      int n, double[] anchor) {
    // 1) 单链接聚类：两点直线距离 <= 阈值即连通，传递合并。
    int[] parent = new int[n];
    for (int i = 0; i < n; i++) {
      parent[i] = i;
    }
    for (int i = 0; i < n; i++) {
      if (!located[i]) {
        continue;
      }
      for (int k = i + 1; k < n; k++) {
        if (located[k] && distance(i, k, lat, lon) <= CLUSTER_LINK_METERS) {
          union(parent, i, k);
        }
      }
    }
    List<List<Integer>> clusters = new ArrayList<>();
    java.util.Map<Integer, List<Integer>> byRoot = new java.util.LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      if (!located[i]) {
        continue;
      }
      byRoot.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).add(i);
    }
    clusters.addAll(byRoot.values());

    // 2) 含首选点的片区排第一；其余按离锚点（无锚点则离首选点）由近及远。
    int home = indexOfCluster(clusters, 0);
    List<Integer> firstCluster = clusters.remove(home);
    final int start = firstCluster.get(0);
    final double[] ref = resolveReference(anchor, located, lat, lon, start);
    clusters.sort(Comparator
        .comparingDouble((List<Integer> c) -> minRefDistance(c, ref, lat, lon))
        .thenComparingInt(List::hashCode));  // 稳定：距离相同保持发现顺序
    clusters.add(0, firstCluster);

    // 3) 片区内部就近串联，片区之间从上一终点就近接入下一片区。
    List<Integer> route = new ArrayList<>();
    int entry = 0;  // 首选点固定为全局起点
    int prevExit = 0;
    for (List<Integer> cluster : clusters) {
      if (cluster != firstCluster) {
        entry = nearestTo(prevExit, cluster, lat, lon);
      }
      List<Integer> local = orderWithinCluster(cluster, entry, lat, lon);
      route.addAll(local);
      prevExit = local.get(local.size() - 1);
    }
    return route;
  }

  /** 单个片区内：以 {@code entry} 为首做最近邻，再 2-opt + Or-opt（entry 始终钉死不动）。 */
  private static List<Integer> orderWithinCluster(List<Integer> members, int entry,
      double[] lat, double[] lon) {
    List<Integer> local = new ArrayList<>(members.size());
    local.add(entry);
    // 复用全局最近邻的语义：在成员子集里从 entry 出发贪心最近。
    java.util.Set<Integer> pool = new java.util.LinkedHashSet<>(members);
    pool.remove(entry);
    int current = entry;
    while (!pool.isEmpty()) {
      int best = -1;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int cand : pool) {
        double d = distance(current, cand, lat, lon);
        if (d < bestDistance) {
          bestDistance = d;
          best = cand;
        }
      }
      local.add(best);
      pool.remove(best);
      current = best;
    }
    twoOpt(local, lat, lon);
    orOpt(local, lat, lon);
    return local;
  }

  /** 片区集合里包含 {@code node} 的下标。 */
  private static int indexOfCluster(List<List<Integer>> clusters, int node) {
    for (int c = 0; c < clusters.size(); c++) {
      if (clusters.get(c).contains(node)) {
        return c;
      }
    }
    return 0;
  }

  /** 片区内离参考点（锚点）最近者的距离，用于片区排序。 */
  private static double minRefDistance(List<Integer> cluster, double[] ref,
      double[] lat, double[] lon) {
    double best = Double.POSITIVE_INFINITY;
    for (int index : cluster) {
      double d = CoordinateTransforms.haversineMeters(ref[0], ref[1], lat[index], lon[index]);
      if (d < best) {
        best = d;
      }
    }
    return best;
  }

  /** 无锚点或锚点不可用时退化为「以首选点为参考」，保证片区排序总有确定参照。 */
  private static double[] resolveReference(double[] anchor, boolean[] located,
      double[] lat, double[] lon, int start) {
    if (anchor != null && Double.isFinite(anchor[0]) && Double.isFinite(anchor[1])) {
      return anchor;
    }
    return new double[] {lat[start], lon[start]};
  }

  private static int nearestTo(int from, List<Integer> candidates, double[] lat, double[] lon) {
    int best = candidates.get(0);
    double bestDistance = Double.POSITIVE_INFINITY;
    for (int cand : candidates) {
      double d = distance(from, cand, lat, lon);
      if (d < bestDistance) {
        bestDistance = d;
        best = cand;
      }
    }
    return best;
  }

  private static int find(int[] parent, int x) {
    while (parent[x] != x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  }

  private static void union(int[] parent, int a, int b) {
    int ra = find(parent, a);
    int rb = find(parent, b);
    if (ra != rb) {
      parent[rb] = ra;
    }
  }

  /**
   * 候选集合的地理质心（GCJ-02），作为 {@link #optimize(List, double[])} 的城市锚点。
   * 纯计算、不触网；没有任何可靠坐标时返回 {@code null}（退化为无锚点优化）。
   */
  public static double[] cityAnchor(List<Attraction> source) {
    if (source == null) {
      return null;
    }
    double sumLat = 0;
    double sumLon = 0;
    int count = 0;
    for (Attraction a : source) {
      if (a == null || a.lat() == null || a.lon() == null || a.coordinateSystem() == null) {
        continue;
      }
      CoordinateTransforms.LatLon gcj =
          CoordinateTransforms.toGcj02(a.lat(), a.lon(), a.coordinateSystem());
      if (gcj == null) {
        continue;
      }
      sumLat += gcj.lat();
      sumLon += gcj.lon();
      count++;
    }
    return count == 0 ? null : new double[] {sumLat / count, sumLon / count};
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

  /**
   * Or-opt：把一段连续 1~3 个点整体搬到别的位置，只在整条路径变短时接受。
   * 首项（片区/全局起点）永不移动；插入位置也不允许落在首项之前。候选量小，
   * 每个候选位置直接重算整条路径长度，简单可靠。
   */
  private static void orOpt(List<Integer> route, double[] lat, double[] lon) {
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      boolean improved = false;
      int n = route.size();
      for (int len = 1; len <= OR_OPT_MAX_SEGMENT; len++) {
        for (int i = 1; i + len <= n; i++) {
          List<Integer> segment = new ArrayList<>(route.subList(i, i + len));
          List<Integer> rest = new ArrayList<>(n - len);
          for (int k = 0; k < n; k++) {
            if (k < i || k >= i + len) {
              rest.add(route.get(k));
            }
          }
          double baseline = pathLength(route, lat, lon);
          int bestPos = -1;
          double bestLength = baseline;
          for (int p = 1; p <= rest.size(); p++) {  // p=0 会把片段挪到起点之前，禁止
            double length = pathLength(spliced(rest, segment, p), lat, lon);
            if (length + MIN_GAIN_METERS < bestLength) {
              bestLength = length;
              bestPos = p;
            }
          }
          if (bestPos >= 0) {
            List<Integer> candidate = spliced(rest, segment, bestPos);
            route.clear();
            route.addAll(candidate);
            improved = true;
          }
        }
      }
      if (!improved) {
        return;
      }
    }
  }

  /** 把 {@code segment} 插入 {@code rest} 的第 {@code pos} 位（rest 已移除该片段）。 */
  private static List<Integer> spliced(List<Integer> rest, List<Integer> segment, int pos) {
    List<Integer> out = new ArrayList<>(rest.size() + segment.size());
    out.addAll(rest.subList(0, pos));
    out.addAll(segment);
    out.addAll(rest.subList(pos, rest.size()));
    return out;
  }

  private static double pathLength(List<Integer> route, double[] lat, double[] lon) {
    double sum = 0;
    for (int i = 1; i < route.size(); i++) {
      sum += distance(route.get(i - 1), route.get(i), lat, lon);
    }
    return sum;
  }

  private static double distance(int from, int to, double[] lat, double[] lon) {
    return CoordinateTransforms.haversineMeters(lat[from], lon[from], lat[to], lon[to]);
  }
}

