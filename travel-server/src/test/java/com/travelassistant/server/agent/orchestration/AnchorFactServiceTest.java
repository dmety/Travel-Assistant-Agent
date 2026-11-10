package com.travelassistant.server.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.orchestration.AnchorFactService;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 锚点下发：坐标系标签是硬前置，缺了就跳过，绝不猜。 */
@DisplayName("餐饮锚点 fact 下发")
class AnchorFactServiceTest {

  private AttractionStore store;
  private AnchorFactService service;

  @BeforeEach
  void setUp() {
    store = mock(AttractionStore.class);
    when(store.findById(anyString())).thenReturn(Optional.empty());
    service = new AnchorFactService(store);
  }

  private static Attraction attraction(String id, Double lat, Double lon, String crs) {
    return new Attraction(id, "西湖", "杭州", "浙江省", null, null, null, null, null,
        null, null, null, null, null, lat, lon, null, List.of(), null, null, crs);
  }

  private List<Fact> build(Map<String, Object> context, List<String> notices,
      List<String> unknown) {
    return service.build("FOOD_RECOMMEND", context, notices, unknown);
  }

  @Test
  @DisplayName("非餐饮 intent 不下发锚点，也不产生提示")
  void otherIntentsGetNoAnchors() {
    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    assertThat(service.build("PLAN_DRAFT", Map.of("attractionIds", List.of("a1")),
        notices, unknown)).isEmpty();
    assertThat(notices).isEmpty();
    assertThat(unknown).isEmpty();
  }

  @Test
  @DisplayName("景点锚点带坐标系标签与完整证据")
  void carriesCoordinateSystemAndEvidence() {
    when(store.findById("a1")).thenReturn(Optional.of(
        attraction("a1", 30.259, 120.13, "WGS-84")));
    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    List<Fact> facts = build(Map.of("attractionIds", List.of("a1")), notices, unknown);

    assertThat(facts).hasSize(1);
    Fact fact = facts.get(0);
    assertThat(fact.type()).isEqualTo("ATTRACTION");
    assertThat(fact.value()).containsEntry("coordinateSystem", "WGS-84")
        .containsEntry("lat", 30.259).containsEntry("lon", 120.13);
    assertThat(fact.evidence().sourceKind()).isEqualTo("KB");
    assertThat(fact.evidence().verificationStatus()).isEqualTo(Evidence.UNVERIFIED);
    assertThat(fact.evidence().fetchedAt()).isNotNull();
    assertThat(fact.factId()).hasSizeLessThanOrEqualTo(64);
    assertThat(notices).isEmpty();
    assertThat(unknown).isEmpty();
  }

  @Test
  @DisplayName("缺坐标系标签、缺坐标或查不到 id 一律跳过，不默认按城市中心")
  void skipsAnchorsWithoutCoordinateSystem() {
    when(store.findById("no-crs")).thenReturn(Optional.of(
        attraction("no-crs", 30.2, 120.1, null)));
    when(store.findById("no-lat")).thenReturn(Optional.of(
        attraction("no-lat", null, 120.1, "GCJ-02")));
    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    List<Fact> facts = build(Map.of("attractionIds",
        List.of("no-crs", "no-lat", "missing")), notices, unknown);

    assertThat(facts).isEmpty();
    assertThat(unknown).contains("anchor");
    assertThat(notices).anyMatch(n -> n.contains("缺少可用锚点"));
  }

  @Test
  @DisplayName("部分锚点不可用时只记录提示，仍下发可用锚点")
  void partialFailureKeepsUsableAnchors() {
    when(store.findById("a1")).thenReturn(Optional.of(
        attraction("a1", 30.259, 120.13, "GCJ-02")));
    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    List<Fact> facts = build(Map.of("attractionIds", List.of("a1", "missing")), notices, unknown);

    assertThat(facts).hasSize(1);
    assertThat(unknown).isEmpty();
    assertThat(notices).anyMatch(n -> n.contains("已跳过"));
  }

  @Test
  @DisplayName("显式实时定位必须自带坐标系标签")
  void explicitAnchorRequiresLabel() {
    List<String> notices = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    List<Fact> ok = build(Map.of("anchor",
        Map.of("lat", 30.25, "lon", 120.13, "coordinateSystem", "GCJ-02")), notices, unknown);
    assertThat(ok).hasSize(1);
    assertThat(ok.get(0).evidence().sourceKind()).isEqualTo("USER_INPUT");

    List<String> badNotices = new ArrayList<>();
    List<String> badUnknown = new ArrayList<>();
    assertThat(build(Map.of("anchor", Map.of("lat", 30.25, "lon", 120.13)),
        badNotices, badUnknown)).isEmpty();
    assertThat(badUnknown).contains("anchor");
  }
}
