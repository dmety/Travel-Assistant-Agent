package com.travelassistant.server.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.intent.IntentParser;
import com.travelassistant.server.application.intent.IntentResolution;
import com.travelassistant.server.application.intent.TravelIntent;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自然语言解析：模型的输出必须被校验，模型不可用必须降级而不是失败。
 *
 * <p>时钟固定为 2026-11-10（周二），这样「下周六」这类相对日期有确定答案，
 * 不会因为跑测试的日子不同而随机失败。
 */
@DisplayName("自然语言解析")
class IntentParserTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZONE);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

  private DeepSeekClient model;
  private PlaceResolver places;
  private IntentParser parser;

  @BeforeEach
  void setUp() {
    model = mock(DeepSeekClient.class);
    places = mock(PlaceResolver.class);
    when(model.configured()).thenReturn(true);
    parser = new IntentParser(model, places, new ObjectMapper(), FIXED);
  }

  private void placeHit(String city) {
    when(places.resolve(anyString(), anyList())).thenReturn(
        PlaceResolution.hit("武当山", city, "武当山", "湖北省", "spot", "kb",
            "「武当山」是「武当山」，按所在城市 " + city + " 检索"));
  }

  @Test
  @DisplayName("模型给出完整 JSON：字段全部落地，目的地由知识库核实")
  void modelJsonIsApplied() {
    placeHit("十堰");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"武当山","days":4,"travelers":3,"budget":6000,
         "departDate":"2026-11-21","returnDate":"2026-11-24","preferences":["人文"],
         "confidence":"high","reason":"下周六带爸妈出发玩四天，预算六千"}
        """);

    IntentResolution r = parser.parse("下周六带爸妈去武当山玩四天，预算六千，想看看人文古迹");

    assertThat(r.intent()).isEqualTo(TravelIntent.PLAN_DRAFT);
    assertThat(r.source()).isEqualTo("llm");
    assertThat(r.destination()).isEqualTo("武当山");
    assertThat(r.city()).isEqualTo("十堰");
    assertThat(r.resolved()).isTrue();
    assertThat(r.days()).isEqualTo(4);
    assertThat(r.travelers()).isEqualTo(3);
    assertThat(r.budget()).isEqualByComparingTo("6000");
    assertThat(r.departDate()).isEqualTo(LocalDate.of(2026, 11, 21));
    assertThat(r.returnDate()).isEqualTo(LocalDate.of(2026, 11, 24));
    assertThat(r.preferences()).containsExactly("人文");
    assertThat(r.confidence()).isEqualTo("high");
  }

  @Test
  @DisplayName("模型给出未开放的意图：回落到排行程，不影响可用性")
  void unknownIntentFallsBackToPlan() {
    placeHit("十堰");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"DECISION_ADVICE","destination":"武当山","days":2}
        """);

    IntentResolution r = parser.parse("武当山两天");

    assertThat(r.intent()).isEqualTo(TravelIntent.PLAN_DRAFT);
    assertThat(r.days()).isEqualTo(2);
  }

  @Test
  @DisplayName("模型返回不是 JSON：改用句式规则，仍能认出天数与人数")
  void nonJsonFallsBackToRules() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn("我觉得是武汉玩两天吧");

    IntentResolution r = parser.parse("武汉 2 天 2 个人");

    assertThat(r.source()).isEqualTo("rules");
    assertThat(r.days()).isEqualTo(2);
    assertThat(r.travelers()).isEqualTo(2);
  }

  @Test
  @DisplayName("模型调用失败：降级为规则解析，不抛异常")
  void modelFailureFallsBackToRules() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString()))
        .thenThrow(new AgentUnavailableException("模型服务请求失败"));

    IntentResolution r = parser.parse("武汉 3 天");

    assertThat(r.source()).isEqualTo("rules");
    assertThat(r.days()).isEqualTo(3);
  }

  @Test
  @DisplayName("没配模型密钥：一次都不调用模型")
  void unconfiguredModelSkipsCall() {
    when(model.configured()).thenReturn(false);
    placeHit("武汉");

    IntentResolution r = parser.parse("武汉 3 天");

    assertThat(r.source()).isEqualTo("rules");
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("天数超过上限：按 30 处理并留下说明")
  void daysAreClamped() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":\"武汉\",\"days\":99}");

    IntentResolution r = parser.parse("武汉玩 99 天");

    assertThat(r.days()).isEqualTo(30);
    assertThat(r.notes()).anyMatch(n -> n.contains("超过上限"));
  }

  @Test
  @DisplayName("出发日期早于今天：丢弃并说明，不生成过去的行程")
  void pastDateIsDropped() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"武汉","days":2,"departDate":"2026-01-05"}
        """);

    IntentResolution r = parser.parse("武汉两天，1 月 5 号出发");

    assertThat(r.departDate()).isNull();
    assertThat(r.notes()).anyMatch(n -> n.contains("早于今天"));
  }

  @Test
  @DisplayName("返回日期早于出发日期：丢弃返回日期并说明")
  void invertedDatesAreDropped() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"武汉","departDate":"2026-12-10",
         "returnDate":"2026-12-01"}
        """);

    IntentResolution r = parser.parse("12 月 10 号出发");

    assertThat(r.departDate()).isEqualTo(LocalDate.of(2026, 12, 10));
    assertThat(r.returnDate()).isNull();
    assertThat(r.notes()).anyMatch(n -> n.contains("早于出发日期"));
  }

  @Test
  @DisplayName("说了出发日与天数：返回日期按行程推算，并注明是推算值")
  void returnDateIsDerived() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"武汉","days":3,"departDate":"2026-12-10"}
        """);

    IntentResolution r = parser.parse("12 月 10 号出发玩三天");

    assertThat(r.returnDate()).isEqualTo(LocalDate.of(2026, 12, 12));
    assertThat(r.notes()).anyMatch(n -> n.contains("推算"));
  }

  @Test
  @DisplayName("偏好收敛到词表：别名映射、词表外丢弃")
  void preferencesAreCanonicalised() {
    placeHit("三亚");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"三亚","preferences":["海边","拍照","躺平","乱写"]}
        """);

    IntentResolution r = parser.parse("想去海边拍照，躺着那种");

    assertThat(r.source()).isEqualTo("llm");
    assertThat(r.preferences()).containsExactly("海岛", "摄影", "慢节奏");
  }

  @Test
  @DisplayName("预算不是正数或过大：忽略而不是当成 0")
  void invalidBudgetIsDropped() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"PLAN_DRAFT","destination":"武汉","budget":2000000}
        """);

    IntentResolution r = parser.parse("武汉，预算两百万");

    assertThat(r.budget()).isNull();
    assertThat(r.notes()).anyMatch(n -> n.contains("预算"));
  }

  @Test
  @DisplayName("闲聊：不查地名、不给城市选项，交给前端本地回复")
  void chatDoesNotResolvePlace() {
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"CHAT\",\"destination\":null,\"confidence\":\"high\"}");

    IntentResolution r = parser.parse("你好呀");

    assertThat(r.intent()).isEqualTo(TravelIntent.CHAT);
    assertThat(r.resolved()).isFalse();
    assertThat(r.alternatives()).isEmpty();
    verify(places, never()).resolve(anyString(), any());
  }

  @Test
  @DisplayName("目的地完全没线索：给真实存在的热门城市按钮，不让用户重打字")
  void unknownPlaceFallsBackToPopularCities() {
    when(places.resolve(anyString(), anyList())).thenReturn(
        PlaceResolution.miss("火星", "", List.of()));
    when(places.popularCities(anyInt())).thenReturn(List.of("北京", "杭州", "西安"));
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":\"火星\",\"days\":2}");

    IntentResolution r = parser.parse("想去火星玩两天");

    assertThat(r.resolved()).isFalse();
    assertThat(r.alternatives()).containsExactly("北京", "杭州", "西安");
    assertThat(r.note()).isNotBlank();
  }

  @Test
  @DisplayName("模型说整句没有地名：不反查、不抛半截短语，直接给热门城市按钮")
  void noDestinationGivesPopularCities() {
    when(places.popularCities(anyInt())).thenReturn(List.of("北京", "杭州", "西安"));
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":null,\"preferences\":[\"海岛\"]}");

    IntentResolution r = parser.parse("想找个安静的海边待几天");

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("还差一个目的地");
    assertThat(r.alternatives()).containsExactly("北京", "杭州", "西安");
    assertThat(r.preferences()).containsExactly("海岛");
    verify(places, never()).resolve(anyString(), any());
  }

  @Test
  @DisplayName("空输入：返回引导语而不是抛异常")
  void blankInputReturnsGuidance() {
    IntentResolution r = parser.parse("   ");

    assertThat(r.intent()).isEqualTo(TravelIntent.CHAT);
    assertThat(r.note()).contains("哪儿");
  }

  @Test
  @DisplayName("规则降级也认「下周六」「玩四天」：相对日期按今天换算")
  void rulesResolveRelativeDate() {
    when(model.configured()).thenReturn(false);
    placeHit("十堰");

    IntentResolution r = parser.parse("下周六去武当山玩四天");

    assertThat(r.source()).isEqualTo("rules");
    assertThat(r.days()).isEqualTo(4);
    // 2026-11-10 是周二：「下周六」按下周的周六算 = 11-21，玩四天则 11-24 返回
    assertThat(r.departDate()).isEqualTo(TODAY.plusDays(11));
    assertThat(r.returnDate()).isEqualTo(TODAY.plusDays(14));
  }
}
