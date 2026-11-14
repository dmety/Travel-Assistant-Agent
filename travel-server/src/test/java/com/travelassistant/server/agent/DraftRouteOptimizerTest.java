package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.agent.DraftAssembler;
import com.travelassistant.server.application.agent.DraftRouteOptimizer;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 地理路径优化的回归测试：锁住「减少折返」这一效果，同时锁住三条红线——
 * 最高优先级候选仍在第一天首位、每天景点数量不变、不生成任何游览时刻/耗时。
 */
@DisplayName("草案地理路径优化")
class DraftRouteOptimizerTest {

  /** 西湖 / 灵隐寺 / 西溪彼此几公里，千岛湖在百公里外，正是「跑老远再跑回来」的复现。 */
  private static final Attraction WEST_LAKE = spot("a", "西湖", 30.259, 120.130, "GCJ-02");
  private static final Attraction LINGYIN = spot("c", "灵隐寺", 30.241, 120.101, "GCJ-02");
  private static final Attraction XIXI = spot("d", "西溪湿地", 30.268, 120.065, "GCJ-02");
  private static final Attraction QIANDAO = spot("b", "千岛湖", 29.605, 119.042, "GCJ-02");

  private static Attraction spot(String id, String name, double lat, double lon, String crs) {
    return new Attraction(id, name, "杭州", "浙江省", "风景名胜", "5A", "4.8",
        "08:00-17:00", "60 元", "杭州市", null, null, null, "说明",
        lat, lon, "KB", List.of("KB"), "KNOWN", "KNOWN", crs);
  }

  private static double pathLength(List<Attraction> spots) {
    double sum = 0;
    for (int i = 1; i < spots.size(); i++) {
      Attraction a = spots.get(i - 1);
      Attraction b = spots.get(i);
      sum += CoordinateTransforms.haversineMeters(a.lat(), a.lon(), b.lat(), b.lon());
    }
    return sum;
  }

  @Test
  @DisplayName("按推荐优先级排好的候选里插着远点，优化后近点相邻、远点挪到最后")
  void nearbySpotsAreClusteredBeforeTheFarOne() {
    // 模拟 PlanningTools.rank() 把 5A 的千岛湖排在灵隐寺之前的情形
    List<Attraction> ranked = List.of(WEST_LAKE, QIANDAO, LINGYIN, XIXI);

    List<Attraction> ordered = DraftRouteOptimizer.optimize(ranked);

    assertThat(ordered).containsExactlyInAnyOrder(WEST_LAKE, QIANDAO, LINGYIN, XIXI);
    assertThat(ordered.get(0)).isSameAs(WEST_LAKE);
    assertThat(indexOf(ordered, LINGYIN)).isLessThan(indexOf(ordered, QIANDAO));
    assertThat(indexOf(ordered, XIXI)).isLessThan(indexOf(ordered, QIANDAO));
    assertThat(pathLength(ordered)).isLessThan(pathLength(ranked));
  }

  @Test
  @DisplayName("展示与落库共用 arrange：第一天仍是最高优先级景点，每天数量不变，且不写游览时刻")
  void assembleKeepsDayShapeAndDoesNotInventTimes() {
    PlanningRequest request = new PlanningRequest("杭州", 2, List.of(),
        new BigDecimal("2000"), "CNY", 2, null, null);

    DraftAssembler.Draft draft = DraftAssembler.assemble(request,
        List.of(WEST_LAKE, QIANDAO, LINGYIN, XIXI));

    assertThat(draft.dailySchedules()).hasSize(2);
    assertThat(draft.dailySchedules()).allSatisfy(day ->
        assertThat(day.items()).hasSize(DraftAssembler.MAX_PER_DAY));
    assertThat(draft.dailySchedules().get(0).items().get(0).name()).isEqualTo("西湖");
    assertThat(draft.dailySchedules().get(0).items().get(1).name()).isNotEqualTo("千岛湖");
    assertThat(draft.dailySchedules().get(0).note()).contains("游览时刻待确认");
    // 结构层没有 startTime / duration 字段，note 里也不该出现具体钟点
    assertThat(draft.dailySchedules().get(0).note()).doesNotContain("09:00", "10:30", "预计耗时");
  }

  @Test
  @DisplayName("WGS-84 与 GCJ-02 混存时先统一坐标系再比距离")
  void mixedCoordinateSystemsAreNormalized() {
    // 西溪用 WGS-84 近似值，与另外三个 GCJ-02 点混在同一批候选里
    Attraction wgs = spot("d", "西溪湿地", 30.2663, 120.0595, "WGS-84");
    List<Attraction> ranked = List.of(WEST_LAKE, QIANDAO, LINGYIN, wgs);

    List<Attraction> ordered = DraftRouteOptimizer.optimize(ranked);

    assertThat(ordered.get(0)).isSameAs(WEST_LAKE);
    assertThat(indexOf(ordered, wgs)).isLessThan(indexOf(ordered, QIANDAO));
    assertThat(ordered).hasSize(4);
  }

  @Test
  @DisplayName("坐标系标签不可识别时不猜坐标，该景点仍保留在结果里")
  void unknownCoordinateSystemIsNotGuessed() {
    Attraction unknown = spot("e", "标签异常景点", 31.0, 121.0, "MARS-2000");
    List<Attraction> ranked = List.of(WEST_LAKE, unknown, QIANDAO, LINGYIN, XIXI);

    List<Attraction> ordered = DraftRouteOptimizer.optimize(ranked);

    assertThat(ordered).hasSize(5).contains(unknown);
    assertThat(ordered.get(0)).isSameAs(WEST_LAKE);
    assertThat(indexOf(ordered, LINGYIN)).isLessThan(indexOf(ordered, QIANDAO));
  }

  @Test
  @DisplayName("最高优先级候选没有可靠坐标时整批降级为原顺序")
  void fallsBackWhenAnchorCannotBeLocated() {
    Attraction noCrs = spot("z", "无坐标首选", 30.0, 120.0, null);
    List<Attraction> ranked = List.of(noCrs, QIANDAO, LINGYIN, XIXI);

    assertThat(DraftRouteOptimizer.optimize(ranked)).isSameAs(ranked);
  }

  @Test
  @DisplayName("可靠坐标不足三个时降级为原顺序，不拿两个点硬凑路线")
  void fallsBackWhenTooFewLocatedSpots() {
    Attraction noCrs1 = spot("x1", "缺坐标一", 30.0, 120.0, null);
    Attraction noCrs2 = spot("x2", "缺坐标二", 30.0, 120.0, null);
    List<Attraction> ranked = List.of(WEST_LAKE, noCrs1, noCrs2, QIANDAO);

    assertThat(DraftRouteOptimizer.optimize(ranked)).isSameAs(ranked);
  }

  @Test
  @DisplayName("空列表、单点、两点都原样返回")
  void shortListsAreUntouched() {
    assertThat(DraftRouteOptimizer.optimize(null)).isEmpty();
    assertThat(DraftRouteOptimizer.optimize(List.of())).isEmpty();
    List<Attraction> one = new ArrayList<>(List.of(WEST_LAKE));
    assertThat(DraftRouteOptimizer.optimize(one)).isSameAs(one);
    List<Attraction> two = List.of(WEST_LAKE, QIANDAO);
    assertThat(DraftRouteOptimizer.optimize(two)).isSameAs(two);
  }

  private static int indexOf(List<Attraction> spots, Attraction target) {
    for (int i = 0; i < spots.size(); i++) {
      if (spots.get(i) == target) {
        return i;
      }
    }
    return -1;
  }
}
