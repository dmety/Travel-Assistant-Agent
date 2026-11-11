package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.application.agent.PlanningTools;
import com.travelassistant.server.domain.attraction.Attraction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanningTools#searchLive} 的转换与降级：地名未收录时实时检索候选要能
 * 无损落进 {@link Attraction}（等级票价开放时间一律 null，展示层按未知处理）。
 */
class PlanningToolsTest {

  private static MapTools.ScenicSpot spot(String name, String type) {
    return new MapTools.ScenicSpot(name, type, "黄梅县某路1号", "黄梅县", "0713-1234567",
        30.07, 115.94, "GCJ-02", "amap", null, null, null, null);
  }

  @Test
  @DisplayName("实时候选转 Attraction：id 带 map: 前缀，等级票价开放时间保持 null")
  void convertsScenicSpots() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots(eq("黄梅"), anyInt())).thenReturn(new MapTools.ScenicResult(
        "黄梅", List.of(spot("五祖寺", "风景名胜;寺庙"), spot("四祖寺", "风景名胜;寺庙")),
        List.of()));
    PlanningTools tools = new PlanningTools(null, null, null, maps);

    var outcome = tools.searchLive("黄梅", 10);

    assertThat(outcome.ok()).isTrue();
    List<Attraction> spots = outcome.value();
    assertThat(spots).hasSize(2);
    Attraction first = spots.get(0);
    assertThat(first.id()).isEqualTo("map:五祖寺");
    assertThat(first.name()).isEqualTo("五祖寺");
    assertThat(first.city()).isEqualTo("黄梅");
    assertThat(first.type()).isEqualTo("风景名胜;寺庙");
    assertThat(first.address()).isEqualTo("黄梅县某路1号");
    assertThat(first.lat()).isEqualTo(30.07);
    assertThat(first.lon()).isEqualTo(115.94);
    assertThat(first.coordinateSystem()).isEqualTo("GCJ-02");
    // 地图拿不到的字段一律 null：展示层必须显示「未知」，不许当成免费或全天开放
    assertThat(first.level()).isNull();
    assertThat(first.rating()).isNull();
    assertThat(first.ticket()).isNull();
    assertThat(first.openTime()).isNull();
  }

  @Test
  @DisplayName("limit 生效：候选多于上限时截断")
  void respectsLimit() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots(eq("黄梅"), anyInt())).thenReturn(new MapTools.ScenicResult(
        "黄梅",
        List.of(spot("a", "风景名胜"), spot("b", "风景名胜"), spot("c", "风景名胜")),
        List.of()));
    PlanningTools tools = new PlanningTools(null, null, null, maps);

    assertThat(tools.searchLive("黄梅", 2).value()).hasSize(2);
  }

  @Test
  @DisplayName("实时检索抛异常：转成失败 ToolOutcome，不炸整份草案")
  void degradesOnFailure() {
    MapTools maps = mock(MapTools.class);
    when(maps.findScenicSpots(eq("黄梅"), anyInt()))
        .thenThrow(new IllegalStateException("高德检索失败"));
    PlanningTools tools = new PlanningTools(null, null, null, maps);

    var outcome = tools.searchLive("黄梅", 10);

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.error()).contains("实时景点检索不可用");
  }
}
