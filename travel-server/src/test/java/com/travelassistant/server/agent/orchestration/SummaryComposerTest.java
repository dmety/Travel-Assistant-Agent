package com.travelassistant.server.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.agent.orchestration.SummaryComposer;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.Usage;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 归纳句：只在事实范围内说话，任何不可核对的数字都整条丢弃。 */
@DisplayName("美食结果归纳层")
class SummaryComposerTest {

  private final ContractRules rules = new ContractRules();
  private DeepSeekClient model;
  private SummaryComposer composer;

  @BeforeEach
  void setUp() {
    model = mock(DeepSeekClient.class);
    composer = new SummaryComposer(model, rules);
  }

  private static Fact foodFact(String name, int distance) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "FOOD");
    value.put("name", name);
    value.put("distanceMeters", distance);
    value.put("pricePerPersonCny", 68);
    value.put("rating", 4.7);
    value.put("tags", List.of("土菜", "农家菜"));
    value.put("mealSlot", "LUNCH");
    value.put("locality", "LOCAL");
    return new Fact("food:" + name, "CONTENT", value,
        new Evidence("AMAP", "B1", Evidence.UNVERIFIED, Instant.now(), "地图快照"));
  }

  private static AgentResult result(List<Fact> facts) {
    return new AgentResult("OK", facts, "模板建议", List.of(), List.of(), new Usage(0L, 0));
  }

  @Test
  @DisplayName("未配置模型密钥时不发起调用，返回 null 由调用方保留模板建议")
  void skipsWithoutKey() {
    when(model.configured()).thenReturn(false);
    assertThat(composer.compose("FOOD_RECOMMEND", result(List.of(foodFact("A", 273))))).isNull();
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("没有 facts 时不生成归纳句")
  void skipsWithoutFacts() {
    when(model.configured()).thenReturn(true);
    assertThat(composer.compose("FOOD_RECOMMEND", result(List.of()))).isNull();
    assertThat(composer.compose("FOOD_RECOMMEND", null)).isNull();
  }

  @Test
  @DisplayName("模型不可用时静默降级，不抛异常")
  void silentWhenModelFails() {
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString()))
        .thenThrow(new AgentUnavailableException("模型超时"));
    assertThat(composer.compose("FOOD_RECOMMEND", result(List.of(foodFact("A", 273))))).isNull();
  }

  @Test
  @DisplayName("不含数字的归纳句直接采用")
  void acceptsPlainSummary() {
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString()))
        .thenReturn("候选以本地土菜和农家菜为主，多数在步行范围内。");
    assertThat(composer.compose("FOOD_RECOMMEND", result(List.of(foodFact("A", 273)))))
        .isEqualTo("候选以本地土菜和农家菜为主，多数在步行范围内。");
  }

  @Test
  @DisplayName("数字能在事实里找到就保留，找不到就整条丢弃")
  void enforcesNumericGrounding() {
    when(model.configured()).thenReturn(true);
    List<Fact> facts = List.of(foodFact("A", 273), foodFact("B", 431));

    when(model.complete(anyString(), anyString())).thenReturn("最近的一家距离 273 米。");
    assertThat(composer.compose("FOOD_RECOMMEND", result(facts)))
        .isEqualTo("最近的一家距离 273 米。");

    when(model.complete(anyString(), anyString())).thenReturn("最近的一家距离 120 米。");
    assertThat(composer.compose("FOOD_RECOMMEND", result(facts))).isNull();
  }

  @Test
  @DisplayName("超出长度上限、空输出的归纳句都不采用")
  void rejectsTooLongOrBlank() {
    when(model.configured()).thenReturn(true);
    List<Fact> facts = List.of(foodFact("A", 273));

    when(model.complete(anyString(), anyString())).thenReturn("候".repeat(200));
    assertThat(composer.compose("FOOD_RECOMMEND", result(facts))).isNull();

    when(model.complete(anyString(), anyString())).thenReturn("   ");
    assertThat(composer.compose("FOOD_RECOMMEND", result(facts))).isNull();
  }

  @Test
  @DisplayName("候选条数本身也算可核对数字")
  void allowsCandidateCount() {
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn("共 2 家候选，以本地菜为主。");
    List<Fact> facts = List.of(foodFact("A", 273), foodFact("B", 431));
    assertThat(composer.compose("FOOD_RECOMMEND", result(facts)))
        .isEqualTo("共 2 家候选，以本地菜为主。");
  }
}
