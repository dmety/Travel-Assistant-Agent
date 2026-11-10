package com.travelassistant.server.agent.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.BuiltinProviderConfiguration;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 天气与灵感子 Agent 的映射行为：主 Agent 走编排层调它们，契约语义必须站得住——
 * 查不到就 DEGRADED 且不编造，数字只出现在 fact 里，advice 永远无断言。
 */
@DisplayName("天气与灵感子 Agent")
class BuiltinWeatherInspirationTest {

  private final BuiltinProviderConfiguration configuration = new BuiltinProviderConfiguration();
  private final ContractRules rules = new ContractRules();

  @Test
  @DisplayName("builtin.weather：命中城市回逐日预报 fact，advice 不带温度数字，且过契约校验")
  void weatherFact() {
    LocationService location = mock(LocationService.class);
    when(location.weather("天津")).thenReturn(new WeatherResult("天津", "120000",
        "2026-11-10 10:00",
        List.of(new WeatherResult.DayWeather("2026-11-10", "晴", "多云", "18", "8",
            "东北风", "3", "北风", "2")),
        "AMAP", Instant.parse("2026-11-10T02:00:00Z")));
    AgentProvider provider = configuration.builtinWeather(rules, location);

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "WEATHER_ADVICE", mapOf("city", "天津"),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.advice()).doesNotContain("18").doesNotContain("晴");
    Fact fact = result.facts().stream()
        .filter(f -> "builtin.weather:forecast".equals(f.factId())).findFirst().orElseThrow();
    assertThat(fact.value()).isInstanceOf(java.util.Map.class);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> value = (java.util.Map<String, Object>) fact.value();
    assertThat(value).containsEntry("category", "WEATHER").containsEntry("city", "天津");
    assertThat(value.get("days")).isInstanceOf(List.class);
    // 编排层会跑同一套契约校验：sourceKind 之类的口径错误在这里就拦下，而不是运行时 REJECTED
    ContractValidator validator = new ContractValidator(rules);
    assertThat(validator.validateResult(validator.toResponseMap("builtin.weather", result)))
        .isEmpty();
  }

  @Test
  @DisplayName("builtin.weather：高德没给预报就 DEGRADED，不编造天气")
  void weatherDegraded() {
    LocationService location = mock(LocationService.class);
    when(location.weather(anyString())).thenReturn(new WeatherResult("火星", null,
        null, List.of(), "AMAP", Instant.now()));
    AgentProvider provider = configuration.builtinWeather(rules, location);

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "WEATHER_ADVICE", mapOf("city", "火星"),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("DEGRADED");
    assertThat(result.advice()).isNull();
    assertThat(result.notices()).anyMatch(n -> n.contains("未编造天气"));
  }

  @Test
  @DisplayName("builtin.weather：缺城市名直接 REFUSED")
  void weatherRefused() {
    AgentProvider provider = configuration.builtinWeather(rules, mock(LocationService.class));

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "WEATHER_ADVICE", java.util.Map.of(),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("REFUSED");
    assertThat(result.unknownFields()).contains("city");
  }

  @Test
  @DisplayName("builtin.inspiration：好评高的排前（学校不算景点），票价原文进 fact")
  void inspirationPicks() {
    AttractionStore store = mock(AttractionStore.class);
    when(store.findByCity("杭州")).thenReturn(List.of(
        attraction("a1", "西湖", "5A", "4.9", "免费"),
        attraction("a2", "某大学", null, "5.0", "免费"),
        attraction("a3", "千岛湖", "5A", "4.5", "130 元"),
        attraction("a4", "灵隐寺", null, null, "45 元")));
    AgentProvider provider = configuration.builtinInspiration(rules, store, mock(MapTools.class));

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "CONTENT_INSPIRATION", mapOf("city", "杭州"),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("OK");
    Fact fact = result.facts().stream()
        .filter(f -> "builtin.inspiration:picks".equals(f.factId())).findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> value = (java.util.Map<String, Object>) fact.value();
    assertThat(value).containsEntry("category", "INSPIRATION").containsEntry("city", "杭州");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>) value.get("items");
    // 评分优先：4.9 > 4.5 > 无评分；rating=5.0 的大学是科教条目，直接剔除非景点
    assertThat(items).extracting(i -> i.get("name"))
        .containsExactly("西湖", "千岛湖", "灵隐寺")
        .doesNotContain("某大学");
    assertThat(items.get(0).get("reason")).isEqualTo("5A，评分 4.9，门票 免费");
    assertThat(items.get(1).get("reason")).isEqualTo("5A，评分 4.5，门票 130 元");
    assertThat(items.get(0)).containsEntry("ticket", "免费")
        .containsEntry("rating", "4.9").containsKey("ticketStatus");
    ContractValidator validator = new ContractValidator(rules);
    assertThat(validator.validateResult(validator.toResponseMap("builtin.inspiration", result)))
        .isEmpty();
  }

  @Test
  @DisplayName("builtin.inspiration：知识库没收录就改用地图实时检索，候选标注未经核验且过契约")
  void inspirationDiscoverFromMap() {
    AttractionStore store = mock(AttractionStore.class);
    when(store.findByCity("黄梅")).thenReturn(List.of());
    MapTools mapTools = mock(MapTools.class);
    when(mapTools.findScenicSpots("黄梅", 8)).thenReturn(new MapTools.ScenicResult(
        "黄梅",
        List.of(new MapTools.ScenicSpot("五祖寺", "风景名胜;寺庙", "黄梅县五祖镇", "黄梅县",
            "0713-3361111", 30.116, 115.934, "GCJ-02", MapTools.SOURCE_AMAP, 4.7, 53),
            new MapTools.ScenicSpot("四祖寺", "风景名胜;寺庙", "黄梅县大河镇", "黄梅县",
                null, 30.152, 115.912, "GCJ-02", MapTools.SOURCE_BAIDU, null, null)),
        List.of("百度密钥未配置")));
    AgentProvider provider = configuration.builtinInspiration(rules, store, mapTools);

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "CONTENT_INSPIRATION", mapOf("city", "黄梅"),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("OK");
    // advice 无数字无断言，不触发 S-03/S-05
    assertThat(result.advice()).doesNotContain("五祖").doesNotContain("免费").doesNotContain("开放");
    Fact fact = result.facts().stream()
        .filter(f -> "builtin.inspiration:discover".equals(f.factId())).findFirst().orElseThrow();
    assertThat(fact.evidence().sourceKind()).isEqualTo("AMAP");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> value = (java.util.Map<String, Object>) fact.value();
    assertThat(value).containsEntry("category", "INSPIRATION")
        .containsEntry("city", "黄梅").containsEntry("source", "LIVE_MAP");
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>) value.get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0)).containsEntry("name", "五祖寺").containsEntry("source", "高德")
        .containsEntry("rating", 4.7).containsEntry("commentCount", 53);
    // 地图真实评分与评价数进 reason；等级、票价、开放时间明确以 null 标注「没有」
    assertThat(items.get(0).get("reason").toString()).contains("评分 4.7")
        .contains("53 条评价");
    assertThat(items.get(0)).containsKeys("level", "ticket", "openTime");
    assertThat(items.get(0).get("level")).isNull();
    assertThat(items.get(0).get("ticket")).isNull();
    assertThat(items.get(0).get("openTime")).isNull();
    assertThat(items.get(1).get("rating")).isNull();
    assertThat(result.notices()).anyMatch(n -> n.contains("未经核验"));
    ContractValidator validator = new ContractValidator(rules);
    assertThat(validator.validateResult(validator.toResponseMap("builtin.inspiration", result)))
        .isEmpty();
  }

  @Test
  @DisplayName("builtin.inspiration：知识库与地图都没收录就 DEGRADED，不给假推荐")
  void inspirationDegraded() {
    AttractionStore store = mock(AttractionStore.class);
    when(store.findByCity("婺源")).thenReturn(List.of());
    MapTools mapTools = mock(MapTools.class);
    when(mapTools.findScenicSpots("婺源", 8)).thenReturn(new MapTools.ScenicResult(
        "婺源", List.of(), List.of("未检索到「婺源」的景点候选，未编造")));
    AgentProvider provider = configuration.builtinInspiration(rules, store, mapTools);

    AgentResult result = provider.invoke(new AgentContext(
        "ctx-1", null, "CONTENT_INSPIRATION", mapOf("city", "婺源"),
        List.of(), java.util.Map.of(), Instant.now().plusSeconds(10)), null);

    assertThat(result.status()).isEqualTo("DEGRADED");
    assertThat(result.notices()).anyMatch(n -> n.contains("未编造推荐"));
  }

  private static java.util.Map<String, Object> mapOf(String key, Object value) {
    return java.util.Map.of(key, value);
  }

  private static Attraction attraction(String id, String name, String level,
      String rating, String ticket) {
    return new Attraction(id, name, "杭州", "浙江省", "景点", level, rating, "08:00-17:00",
        ticket, "杭州市", null, null, null, "说明", 30.0, 120.0,
        "KB", List.of("KB"), "KNOWN", "KNOWN", "GCJ-02");
  }
}
