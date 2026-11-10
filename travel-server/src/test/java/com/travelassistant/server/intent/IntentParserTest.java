package com.travelassistant.server.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.application.intent.IntentParser;
import com.travelassistant.server.application.intent.IntentResolution;
import com.travelassistant.server.application.intent.TravelIntent;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.math.BigDecimal;
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

  /* ---------- 上下文记忆 ---------- */

  private static final ConversationState WUHAN =
      new ConversationState("武汉", null, null, null, List.of());

  @Test
  @DisplayName("接着上一轮说「有没有小馆子」：补出上文城市，并标注是沿用的")
  void followUpInheritsCity() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"FOOD_RECOMMEND\",\"destination\":null}");

    IntentResolution r = parser.parse("有没有那种很小众的馆子",
        List.of(new ConversationTurn("user", "武汉有什么好吃的")), WUHAN);

    assertThat(r.intent()).isEqualTo(TravelIntent.FOOD_RECOMMEND);
    assertThat(r.city()).isEqualTo("武汉");
    assertThat(r.resolved()).isTrue();
    assertThat(r.carried()).containsExactly("city");
  }

  @Test
  @DisplayName("排行程省略天数人数：沿用上文确认过的值，一次补齐")
  void planInheritsSlots() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":null}");

    IntentResolution r = parser.parse("帮我排一下", List.of(),
        new ConversationState("武汉", 3, 2, new BigDecimal("3000"), List.of("人文")));

    assertThat(r.city()).isEqualTo("武汉");
    assertThat(r.days()).isEqualTo(3);
    assertThat(r.travelers()).isEqualTo(2);
    assertThat(r.budget()).isEqualByComparingTo("3000");
    assertThat(r.preferences()).containsExactly("人文");
    assertThat(r.carried()).containsExactly("city", "days", "travelers", "budget", "preferences");
  }

  @Test
  @DisplayName("这一句自己说了目的地：以上文为例不再覆盖")
  void explicitDestinationWins() {
    placeHit("西安");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":\"西安\"}");

    IntentResolution r = parser.parse("那去西安呢", List.of(), WUHAN);

    assertThat(r.city()).isEqualTo("西安");
    assertThat(r.carried()).doesNotContain("city");
  }

  @Test
  @DisplayName("闲聊不继承上文：问「你叫什么名字」不该被城市污染")
  void chatNeverInherits() {
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"CHAT\",\"reply\":\"我是这个旅行助手的对话入口，还没起名字。\"}");

    IntentResolution r = parser.parse("你叫什么名字", List.of(), WUHAN);

    assertThat(r.intent()).isEqualTo(TravelIntent.CHAT);
    assertThat(r.resolved()).isFalse();
    assertThat(r.carried()).isEmpty();
    assertThat(r.reply()).contains("旅行助手");
    verify(places, never()).resolve(anyString(), any());
  }

  @Test
  @DisplayName("闲聊回复去掉模型顺手编的网址：本项目没有这种页面")
  void chatReplyDropsLinks() {
    when(model.complete(anyString(), anyString())).thenReturn("""
        {"intent":"CHAT","reply":"我没有价格表，票价都来自景区知识库，详见 https://fake.example.com/p 这个页面"}
        """);

    IntentResolution r = parser.parse("你的价格表在哪里查的 给我官方链接");

    assertThat(r.reply()).isNotNull().doesNotContain("http").doesNotContain("fake.example.com");
    assertThat(r.reply()).contains("价格表");
  }

  @Test
  @DisplayName("只有网址的回复：整条丢弃，交给前端兜底文案")
  void chatReplyWithOnlyLinkIsDropped() {
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"CHAT\",\"reply\":\"https://fake.example.com/prices\"}");

    IntentResolution r = parser.parse("给我官方链接");

    assertThat(r.reply()).isNull();
  }

  @Test
  @DisplayName("问天气：不走景点知识库，城市原样交给天气接口")
  void weatherSkipsKnowledgeBase() {
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"WEATHER\",\"destination\":\"天津\",\"days\":3}");

    IntentResolution r = parser.parse("未来三天天津的天气怎么样");

    assertThat(r.intent()).isEqualTo(TravelIntent.WEATHER);
    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("天津");
    assertThat(r.days()).isEqualTo(3);
    verify(places, never()).resolve(anyString(), any());
  }

  @Test
  @DisplayName("问天气但没说城市：问一句并给可点城市")
  void weatherWithoutCityAsks() {
    when(places.popularCities(anyInt())).thenReturn(List.of("北京", "杭州", "西安"));
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"WEATHER\",\"destination\":null}");

    IntentResolution r = parser.parse("明天天气怎么样");

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("哪座城市");
    assertThat(r.alternatives()).containsExactly("北京", "杭州", "西安");
  }

  @Test
  @DisplayName("规则降级也认天气：「今天天津的天气」不会掉进闲聊")
  void rulesDetectWeather() {
    when(model.configured()).thenReturn(false);

    IntentResolution r = parser.parse("今天天津的天气怎么样");

    assertThat(r.source()).isEqualTo("rules");
    assertThat(r.intent()).isEqualTo(TravelIntent.WEATHER);
    assertThat(r.city()).isEqualTo("天津");
  }

  @Test
  @DisplayName("缓存按上下文区分：同一句话在不同上文里要重新解析")
  void cacheIsScopedByContext() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":null}");

    parser.parse("帮我排一下", List.of(), WUHAN);
    parser.parse("帮我排一下", List.of(), ConversationState.empty());

    verify(model, times(2)).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("超长历史只带最近几条：不会把提示词撑爆")
  void historyIsTrimmed() {
    placeHit("武汉");
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"intent\":\"PLAN_DRAFT\",\"destination\":\"武汉\"}");
    List<ConversationTurn> many = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      many.add(new ConversationTurn("user", "第 " + i + " 句话"));
    }

    parser.parse("武汉三天", many, ConversationState.empty());

    org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.captor();
    verify(model).complete(anyString(), prompt.capture());
    assertThat(prompt.getValue()).doesNotContain("第 11 句话").contains("第 19 句话");
  }
}
