package com.travelassistant.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Usage;
import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import com.travelassistant.server.application.chat.ChatTools;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.domain.attraction.Attraction;
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
  private AgentOrchestrator orchestrator;
  private VoteOrchestrator votes;
  private ChatTools tools;

  @BeforeEach
  void setUp() {
    places = mock(PlaceResolver.class);
    store = mock(AttractionStore.class);
    orchestrator = mock(AgentOrchestrator.class);
    votes = mock(VoteOrchestrator.class);
    tools = new ChatTools(places, store, orchestrator, votes, new ObjectMapper());
  }

  private static Attraction attraction(String id, String name, Double lat) {
    return new Attraction(id, name, "武汉", "湖北省", "景点", "5A", "4.8", "08:00-18:00",
        "免费", "武汉市", null, null, null, "说明", lat, lat == null ? null : 114.3,
        "KB", List.of("KB"), "KNOWN", "KNOWN", lat == null ? null : "GCJ-02");
  }

  @Test
  @DisplayName("核实地名：命中时给出城市，未命中时如实说没查到")
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
    assertThat(miss.summary()).contains("没查到").doesNotContain("知识库");
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
  @DisplayName("查天气：改走 builtin.weather 子 Agent，信封 fact 摊成逐日文本")
  void weatherViaSubAgent() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(ok("WEATHER_ADVICE",
        "builtin.weather", weatherFact()));

    ChatTools.Outcome outcome = tools.execute("get_weather", "{\"city\":\"天津\",\"days\":3}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("预报").contains("18℃").contains("晴");
    assertThat(outcome.card().type()).isEqualTo("weather");
    // 前端 weatherBlock 读的字段必须都在
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) outcome.card().data();
    assertThat(data).containsKeys("city", "reportTime", "days");
    assertThat(data).doesNotContainKey("category");
    ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.captor();
    verify(orchestrator).dispatch(captor.capture());
    assertThat(captor.getValue().intent()).isEqualTo("WEATHER_ADVICE");
    assertThat(captor.getValue().context()).containsEntry("city", "天津");
  }

  @Test
  @DisplayName("查天气：子 Agent 没给事实就如实说查不到，不编天气")
  void weatherDegraded() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(new DispatchOutcome(
        "WEATHER_ADVICE", "DEGRADED", "builtin.weather",
        new AgentResult("DEGRADED", List.of(), null, List.of("weather"),
            List.of("高德没有返回 火星 的预报，未编造天气"), new Usage(0L, 0)),
        List.of(), "test"));
    when(orchestrator.contractBody(any())).thenReturn(Map.of("facts", List.of()));

    ChatTools.Outcome outcome = tools.execute("get_weather", "{\"city\":\"火星\"}");

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.summary()).contains("没查到").contains("未编造天气");
  }

  @Test
  @DisplayName("问票务：原话透传给票务子 Agent，缺口照实转述")
  void ticketViaSubAgent() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(ok("TICKET_ADVICE",
        "builtin.ticket", ticketFact()));

    ChatTools.Outcome outcome = tools.execute("book_ticket",
        "{\"message\":\"两个成人下周六去黄鹤楼\"}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("2 个条件").contains("不要猜");
    assertThat(outcome.card().intent()).isEqualTo("TICKET_ADVICE");
    ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.captor();
    verify(orchestrator).dispatch(captor.capture());
    assertThat(captor.getValue().intent()).isEqualTo("TICKET_ADVICE");
    assertThat(captor.getValue().context())
        .containsEntry("message", "两个成人下周六去黄鹤楼");
  }

  @Test
  @DisplayName("找灵感：子 Agent 返回的主题候选摊成景点卡片")
  void inspirationViaSubAgent() {
    when(orchestrator.dispatch(any(DispatchRequest.class))).thenReturn(ok("CONTENT_INSPIRATION",
        "builtin.inspiration", inspirationFact()));

    ChatTools.Outcome outcome = tools.execute("get_inspiration", "{\"city\":\"杭州\"}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("西湖").contains("以景区公告为准");
    assertThat(outcome.card().type()).isEqualTo("places");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) outcome.card().data();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("name", "西湖").containsEntry("reason", "5A，评分 4.9");
  }

  @Test
  @DisplayName("评委投票：城市与预算进投票上下文，胜出者写进摘要")
  void votePlan() {
    PlanVoteTally.Ranking top = new PlanVoteTally.Ranking("A", "4.60", 3, "3.0",
        List.of(), List.of());
    when(votes.vote(any(VoteOrchestrator.VoteRequest.class))).thenReturn(new VoteOrchestrator.VoteRun(
        "OK", "DECISION_ADVICE", "v1", "RULES", List.of("judge.ticket", "judge.food", "judge.parking"),
        List.of(Map.of("planId", "A", "title", "控制花费")),
        new PlanVoteTally.VoteOutcome("1.0", "DECISION_ADVICE", "A",
            List.of(top), List.of(), List.of(), List.of(), true),
        List.of()));

    ChatTools.Outcome outcome = tools.execute("vote_plan",
        "{\"city\":\"武汉\",\"days\":3,\"budget\":3000}");

    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.summary()).contains("胜出方案 A").contains("3 位评委");
    assertThat(outcome.card().type()).isEqualTo("vote");
    ArgumentCaptor<VoteOrchestrator.VoteRequest> captor = ArgumentCaptor.captor();
    verify(votes).vote(captor.capture());
    assertThat(captor.getValue().intent()).isEqualTo("DECISION_ADVICE");
    assertThat(captor.getValue().context())
        .containsEntry("city", "武汉")
        .containsEntry("days", 3)
        .containsEntry("budgetCny", "3000 元");
  }

  @Test
  @DisplayName("投票没产生胜者：不伪造结论，原样转述原因")
  void voteWithoutWinner() {
    when(votes.vote(any(VoteOrchestrator.VoteRequest.class))).thenReturn(new VoteOrchestrator.VoteRun(
        "DEGRADED", "DECISION_ADVICE", "v1", "RULES", List.of(),
        List.of(), null, List.of("没有可用评委，本次投票没有产生推荐")));

    ChatTools.Outcome outcome = tools.execute("vote_plan", "{\"city\":\"武汉\",\"days\":3}");

    assertThat(outcome.ok()).isFalse();
    assertThat(outcome.summary()).contains("没有产生可推荐方案").contains("没有可用评委");
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
  @DisplayName("找餐馆：有锚点就走 L1 编排，返回契约信封当卡片")
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
    verify(orchestrator).dispatch(captor.capture());
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
    verify(orchestrator).dispatch(captor.capture());
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
  @DisplayName("工具清单：八个工具，名字唯一，参数声明合法")
  void specsAreWellFormed() {
    assertThat(ChatTools.SPECS).hasSize(8);
    List<String> names = ChatTools.SPECS.stream()
        .map(spec -> String.valueOf(((Map<?, ?>) spec.get("function")).get("name")))
        .toList();
    assertThat(names).containsExactlyInAnyOrder(
        "resolve_place", "list_attractions", "get_weather", "find_food",
        "draft_itinerary", "book_ticket", "get_inspiration", "vote_plan");
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

  private static Fact weatherFact() {
    Map<String, Object> day = new LinkedHashMap<>();
    day.put("date", "2026-11-10");
    day.put("dayWeather", "晴");
    day.put("nightWeather", "多云");
    day.put("dayTemp", "18");
    day.put("nightTemp", "8");
    day.put("dayWind", "东北风");
    day.put("dayPower", "3");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "WEATHER");
    value.put("city", "天津");
    value.put("reportTime", "2026-11-10 10:00");
    value.put("days", List.of(day));
    return new Fact("f3", "CONTENT", value,
        new Evidence("AMAP", "builtin.weather", Evidence.UNVERIFIED, Instant.now(), "预报快照"));
  }

  private static Fact ticketFact() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "TICKET");
    value.put("matchCount", 3);
    value.put("questionCount", 2);
    value.put("stockVerified", false);
    return new Fact("f4", "CONTENT", value,
        new Evidence("TICKET_RULE", "builtin.ticket", Evidence.UNVERIFIED, Instant.now(), "匹配计数"));
  }

  private static Fact inspirationFact() {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", "西湖");
    item.put("level", "5A");
    item.put("rating", "4.9");
    item.put("reason", "5A，评分 4.9");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "INSPIRATION");
    value.put("city", "杭州");
    value.put("total", 1);
    value.put("items", List.of(item));
    return new Fact("f5", "CONTENT", value,
        new Evidence("KB", "builtin.inspiration", Evidence.UNVERIFIED, Instant.now(), "候选快照"));
  }
}
