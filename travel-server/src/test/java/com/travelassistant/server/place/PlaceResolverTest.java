package com.travelassistant.server.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 地名识别的三级判断：知识库直查 → 模型归类 → 字面相近兜底。
 *
 * <p>重点不是「能识别地名」，而是**失败时不骗人**：模型给出清单外的城市要被拒、
 * 模型不可用要降级而不是报错、找不到要给出可点的相近城市。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("地名识别")
class PlaceResolverTest {

  @Mock
  private AttractionStore store;

  @Mock
  private DeepSeekClient model;

  private PlaceResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new PlaceResolver(store, model, new ObjectMapper());
  }

  private static Attraction spot(String name, String city, String province) {
    return new Attraction("test-1", name, city, province,
        null, null, null, null, null,
        null, null, null, null, null,
        null, null,
        null, null, null, null, null);
  }

  @Test
  @DisplayName("知识库有城市：直接命中，不调用模型")
  void knowledgeBaseCityWins() {
    when(store.findByCity("武汉")).thenReturn(List.of(spot("黄鹤楼", "武汉", "湖北省")));

    PlaceResolution r = resolver.resolve("想去武汉", List.of("武汉"));

    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("武汉");
    assertThat(r.source()).isEqualTo("kb");
    assertThat(r.level()).isEqualTo("city");
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("知识库有景点：取所在城市，说明里点出景点名")
  void knowledgeBaseSpotResolvesCity() {
    when(store.findByName("黄鹤楼")).thenReturn(List.of(spot("黄鹤楼", "武汉", "湖北省")));

    PlaceResolution r = resolver.resolve("黄鹤楼", List.of("黄鹤楼"));

    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("武汉");
    assertThat(r.spot()).isEqualTo("黄鹤楼");
    assertThat(r.level()).isEqualTo("spot");
    assertThat(r.note()).contains("黄鹤楼").contains("武汉");
  }

  @Test
  @DisplayName("县级地名交给模型判断：黄梅 → 黄冈，带后缀也能收敛")
  void modelMapsCountyToCity() {
    when(store.knownCities()).thenReturn(Map.of("黄冈", 5, "武汉", 355, "黄石", 3));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"city\":\"黄冈市\",\"level\":\"county\",\"reason\":\"黄梅县属湖北省黄冈市\"}");

    PlaceResolution r = resolver.resolve("黄梅", List.of("黄梅"));

    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("黄冈");
    assertThat(r.source()).isEqualTo("llm");
    assertThat(r.level()).isEqualTo("county");
    assertThat(r.note()).contains("黄冈");
  }

  @Test
  @DisplayName("模型套了 Markdown 代码块也照样解析")
  void modelAnswerWithCodeFenceIsParsed() {
    when(store.knownCities()).thenReturn(Map.of("上饶", 6));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "```json\n{\"city\":\"上饶\",\"level\":\"county\",\"reason\":\"婺源县属上饶市\"}\n```");

    PlaceResolution r = resolver.resolve("婺源", List.of("婺源"));

    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("上饶");
  }

  @Test
  @DisplayName("模型判断出的城市不在知识库里：说明归属，不假装没认出")
  void modelCityOutsideCatalogIsExplained() {
    when(store.knownCities()).thenReturn(Map.of("黄冈", 5));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"city\":\"上饶\",\"level\":\"county\",\"reason\":\"婺源县属上饶市\"}");

    PlaceResolution r = resolver.resolve("婺源", List.of("婺源"));

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("上饶");
    assertThat(r.alternatives()).isEmpty();
  }

  @Test
  @DisplayName("只共享通名不算相近：长白山不会被推成山东")
  void weakOverlapIsIgnored() {
    when(store.knownCities()).thenReturn(Map.of("山东", 30, "白山", 4));
    when(model.configured()).thenReturn(false);

    PlaceResolution r = resolver.resolve("长白山", List.of("长白山"));

    assertThat(r.alternatives()).contains("白山");
    assertThat(r.alternatives()).doesNotContain("山东");
  }

  @Test
  @DisplayName("模型返回不是 JSON：忽略内容，只给文字说明")
  void nonJsonAnswerIsIgnored() {
    when(store.knownCities()).thenReturn(Map.of("黄冈", 5, "黄石", 3));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn("我觉得是黄冈吧");

    PlaceResolution r = resolver.resolve("黄梅", List.of("黄梅"));

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("黄梅");
  }

  @Test
  @DisplayName("没配模型密钥：不问模型，直接给结论")
  void unconfiguredModelSkipsCall() {
    when(store.knownCities()).thenReturn(Map.of("白山", 4));

    PlaceResolution r = resolver.resolve("长白山", List.of("长白山"));

    assertThat(r.resolved()).isFalse();
    assertThat(r.alternatives()).contains("白山");
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("模型调用失败：降级为相近城市，不抛异常")
  void modelFailureDegrades() {
    when(store.knownCities()).thenReturn(Map.of("白山", 4));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString()))
        .thenThrow(new AgentUnavailableException("模型服务请求失败"));

    PlaceResolution r = resolver.resolve("长白山", List.of("长白山"));

    assertThat(r.resolved()).isFalse();
    assertThat(r.alternatives()).contains("白山");
  }

  @Test
  @DisplayName("完全没有地名：如实说明，不猜")
  void emptyInputIsReported() {
    PlaceResolution r = resolver.resolve("", List.of());

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("地名");
    assertThat(r.alternatives()).isEmpty();
    verify(model, never()).complete(anyString(), anyString());
  }

  @Test
  @DisplayName("同一地名重复问：走缓存，不再查库也不再调模型")
  void repeatedQueryIsCached() {
    when(store.knownCities()).thenReturn(Map.of("黄冈", 5));
    when(model.configured()).thenReturn(false);

    resolver.resolve("黄梅", List.of("黄梅"));
    resolver.resolve("黄梅", List.of("黄梅"));

    verify(store, times(1)).findByCity("黄梅");
  }

  @Test
  @DisplayName("候选词过短或过长都会被丢弃")
  void candidatesAreFiltered() {
    PlaceResolution r = resolver.resolve("", List.of("a", "这个候选词实在是太长了完全不符合地名长度上限的表达"));

    assertThat(r.resolved()).isFalse();
    assertThat(r.note()).contains("地名");
  }

  @Test
  @DisplayName("前端没给候选时，整句原文交给模型判断")
  void fallsBackToWholeSentence() {
    when(store.knownCities()).thenReturn(Map.of("十堰", 4));
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(), anyString())).thenReturn(
        "{\"city\":\"十堰\",\"level\":\"spot\",\"reason\":\"武当山位于十堰市\"}");

    PlaceResolution r = resolver.resolve("武当山", List.of());

    assertThat(r.resolved()).isTrue();
    assertThat(r.city()).isEqualTo("十堰");
  }

  @Test
  @DisplayName("热门城市：排除省份与直辖市的重名写法，按景点数量排序")
  void popularCitiesSkipProvinces() {
    // 索引里省名（广东/广东省）的聚合数量最高，但它们不该作为「去哪儿」的选项；
    // 直辖市同时有「北京」和「北京市」，只保留一条
    when(store.knownCities()).thenReturn(Map.of(
        "广东省", 300, "广东", 300, "北京", 120, "北京市", 120, "杭州", 90, "西安", 60));

    List<String> cities = resolver.popularCities(3);

    assertThat(cities).containsExactly("北京", "杭州", "西安");
  }
}
