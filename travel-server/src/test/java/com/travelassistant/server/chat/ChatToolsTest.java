package com.travelassistant.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Usage;
import com.travelassistant.server.application.chat.ChatTools;
import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 工具层：模型给什么参数，这里就必须给什么结果；查不到要如实说查不到，不许编。
 */
@DisplayName("对话 Agent 的工具")
class ChatToolsTest {

  private PlaceResolver places;
  private AttractionStore store;
  private LocationService location;
  private AgentOrchestrator orchestrator;
  private ChatTools tools;

  @BeforeEach
  void setUp() {
    places = mock(PlaceResolver.class);
    store = mock(AttractionStore.class);
    location = mock(LocationService.class);
    orchestrator = mock(AgentOrchestrator.class);
    tools = new ChatTools(places, store, location, orchestrator, new ObjectMapper());
  }

  private static Attraction attraction(String id, String name, Double lat) {
    return new Attraction(id, name, "武汉", "湖北省", "景点", "5A", "4.8", "08:00-18:00",
        "免费", "武汉市", null, null, null, "说明", lat, lat == null ? null : 114.3,
        "KB", List.of("KB"), "KNOWN", "KNOWN", lat == null ? null : "GCJ-02");
  }

  @Test
  @DisplayName("核实地名：命中时给出城市，未命中时明确说知识库没有")
  void resolvePlace() {
    when(places.resolve(eq("武当山"), anyList())).thenReturn(
        PlaceResolution.hit("武当山", "十堰", "武当山", "湖北省", "spot", "kb", "按十堰检索"));
    when(places.resolve(eq("火星基地"), anyList())).thenReturn(
        PlaceResolution.miss("火星基地", "", List.of("北京")));

    ChatTools.Outcome hit = tools.execute("resolve_place", "{\"name\":\"武当山\"}");
    ChatTools.Outcome miss = tools.execute("resolve_place", "{\"name\":\"火星基地\"}");

    assertThat(hit.ok()).isTrue();
    assertThat(hit.summary()).contains("十堰");
    assertThat(miss.ok()).isFalse();
    assertThat(miss.summary()).contains("知识库");
  }

  @Test
  @DisplayName("列景点：有条目时带上票价原文，没有时不让模型编")
  void listAttractions() {
    when(store.findByCity("武汉")).thenReturn(List.of(
        attraction("a1", "黄鹤楼", 30.5), attraction("a2", "东湖", null)));
    when(store.findByCity("婺源")).thenReturn(List.of());

    ChatTools.Outcome found = tools.execute("list_attractions", "{\"city\":\"武汉\"}");
    ChatTools.Outcome empty = tools.execute("list_attractions", "{\"city\":\"婺源\"}");

    assertThat(found.ok()).isTrue();
    assertThat(found.summary()).contains("黄鹤楼").contains("免费");
    assertThat(found.card().type()).isEqualTo("places");
    assertThat(empty.ok()).isFalse();
    assertThat(empty.summary()).contains("不要编造");
  }

  @Test
  @DisplayName("查天气：把预报摊成逐日文本，并说明是预报不是实况")
  void weather() {
    when(location.weather("天津")).thenReturn(new WeatherResult("天津", "120000", "2026-11-10 10:00",
        List.of(new WeatherResult.DayWeather("2026-11-10", "晴", "多云", "18", "8", "东北风", "3",
            "北风", "2")), "AMAP", Instant.now()));

    ChatTools.Outcome outcome = tools.execute("get_weather", "{\"city\":\"天津\",\"days\":3}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("预报").contains("18℃").contains("晴");
    assertThat(outcome.card().type()).isEqualTo("weather");
  }

  @Test
  @DisplayName("找餐馆：城市里没有带坐标的景点就不查，绝不按城市中心兜底")
  void foodWithoutAnchor() {
    when(store.findByCity("婺源")).thenReturn(List.of(attraction("a1", "某景点", null)));

    ChatTools.Outcome outcome = tools.execute("find_food", "{\"city\":\"婺源\"}");

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.summary()).contains("坐标");
  }

  @Test
  @DisplayName("找餐馆：有锚点就走上 L1 编排，返回契约信封当卡片")
  void foodWithAnchor() {
    when(store.findByCity("武汉")).thenReturn(List.of(attraction("a1", "黄鹤楼", 30.5)));
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(ok("FOOD_RECOMMEND",
        "builtin.food", foodFact()));
    when(orchestrator.contractBody(any())).thenReturn(Map.of("facts", List.of()));

    ChatTools.Outcome outcome = tools.execute("find_food", "{\"city\":\"武汉\"}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("热干面");
    assertThat(outcome.card().intent()).isEqualTo("FOOD_RECOMMEND");
    ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.captor();
    org.mockito.Mockito.verify(orchestrator).dispatch(captor.capture());
    assertThat(captor.getValue().context()).containsEntry("city", "武汉");
    assertThat(captor.getValue().context().get("attractionIds")).isEqualTo(List.of("a1"));
  }

  @Test
  @DisplayName("排行程：没给天数就不排，先问用户")
  void planWithoutDays() {
    ChatTools.Outcome outcome = tools.execute("draft_itinerary", "{\"city\":\"武汉\"}");

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.summary()).contains("玩几天");
  }

  @Test
  @DisplayName("排行程：天数与人数原样进编排上下文")
  void planWithDays() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(ok("PLAN_DRAFT",
        "builtin.planning", planFact()));
    when(orchestrator.contractBody(any())).thenReturn(Map.of("facts", List.of()));

    ChatTools.Outcome outcome = tools.execute("draft_itinerary",
        "{\"city\":\"武汉\",\"days\":3,\"travelers\":2,\"budget\":3000,\"preferences\":[\"人文\"]}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("PLAN_DRAFT");
    ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.captor();
    org.mockito.Mockito.verify(orchestrator).dispatch(captor.capture());
    assertThat(captor.getValue().context())
        .containsEntry("destination", "武汉")
        .containsEntry("days", 3)
        .containsEntry("travelers", 2);
  }

  @Test
  @DisplayName("编排失败：如实说明没有结果，不伪造草案")
  void planRefused() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(new DispatchOutcome(
        "PLAN_DRAFT", "REFUSED", "builtin.rules", AgentResult.refused(List.of(), List.of("destination"),
        "缺少目的地"), List.of(), "test"));
    when(orchestrator.contractBody(any())).thenReturn(Map.of("facts", List.of()));

    ChatTools.Outcome outcome = tools.execute("draft_itinerary", "{\"city\":\"火星\",\"days\":2}");

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.summary()).contains("缺少目的地");
  }

  @Test
  @DisplayName("未知工具与坏 JSON：都不抛异常，给一句可读的失败说明")
  void unknownToolAndBrokenArgs() {
    ChatTools.Outcome unknown = tools.execute("rm_rf", "{}");
    ChatTools.Outcome broken = tools.execute("resolve_place", "{not json");

    assertThat(unknown.ok()).isFalse();
    assertThat(unknown.summary()).contains("没有名为");
    assertThat(broken.ok()).isFalse();
    assertThat(broken.summary()).contains("name");
  }

  @Test
  @DisplayName("工具清单：名字唯一，参数声明合法")
  void specsAreWellFormed() {
    assertThat(ChatTools.SPECS).hasSize(5);
    assertThat(ChatTools.SPECS).allSatisfy(spec -> {
      assertThat(spec).containsEntry("type", "function");
      assertThat(((Map<?, ?>) spec.get("function")).get("name")).isNotNull();
    });
  }

  private static DispatchOutcome ok(String intent, String provider, Fact fact) {
    return new DispatchOutcome(intent, "OK", provider,
        new AgentResult("OK", List.of(fact), "建议", List.of(), List.of(), new Usage(10L, 5)),
        List.of(new DispatchOutcome.Attempt(provider, "OK", "OK", List.of())), "test");
  }

  private static Fact foodFact() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "FOOD");
    value.put("name", "热干面");
    return new Fact("f1", "CONTENT", value,
        new Evidence("AMAP", "p1", Evidence.UNVERIFIED, Instant.now(), "快照"));
  }

  private static Fact planFact() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "PLAN_DRAFT");
    value.put("destination", "武汉");
    return new Fact("f2", "CONTENT", value,
        new Evidence("KB", "kb", Evidence.UNVERIFIED, Instant.now(), "快照"));
  }
}
