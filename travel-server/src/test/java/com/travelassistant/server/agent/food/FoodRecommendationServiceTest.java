package com.travelassistant.server.agent.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.food.FoodProviderConfiguration;
import com.travelassistant.server.application.agent.food.FoodRecommendationService;
import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.ContractValidator;
import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 内置美食能力：契约合规、忌口硬过滤、降级、去重与坐标系换算。 */
@DisplayName("内置美食推荐能力")
class FoodRecommendationServiceTest {

  private final ContractRules rules = new ContractRules();
  private final ContractValidator validator = new ContractValidator(rules);
  private AmapClient amap;
  private BaiduPlaceClient baidu;
  private FoodRecommendationService service;

  @BeforeEach
  void setUp() {
    amap = mock(AmapClient.class);
    baidu = mock(BaiduPlaceClient.class);
    service = new FoodRecommendationService(amap, baidu);
  }

  private static Map<String, Object> baseContext() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", "杭州");
    context.put("mealSlot", "LUNCH");
    context.put("budgetCny", 100);
    context.put("dietaryFlags", List.of());
    return context;
  }

  private static Fact anchor(double lat, double lon, String crs) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("lat", lat);
    value.put("lon", lon);
    value.put("coordinateSystem", crs);
    return new Fact("anchor:a1", "ATTRACTION", value,
        new Evidence("KB", "a1", Evidence.UNVERIFIED, Instant.now(), "锚点"));
  }

  private static FoodPlace place(String id, String name, String source, String tags,
      Double lat, Double lon, Double rating, Double price, String hours) {
    return new FoodPlace(id, name, lat, lon, tags, rating, price, hours, null,
        source, Instant.now());
  }

  private AgentContext context(Map<String, Object> input, List<Fact> facts) {
    return new AgentContext("run-000001", null, "FOOD_RECOMMEND", input, facts,
        Map.of(), Instant.now().plusSeconds(10));
  }

  private AgentResult recommend(Map<String, Object> input) {
    return service.recommend(context(input, List.of(anchor(30.259, 120.13, "GCJ-02"))));
  }

  private void assertContractCompliant(AgentResult result) {
    assertThat(validator.validateResult(validator.toResponseMap("builtin.food", result)))
        .isEmpty();
  }

  @Test
  @DisplayName("缺城市或缺锚点直接 REFUSED，不发起任何外部调用")
  void refusesWithoutCityOrAnchor() {
    Map<String, Object> noCity = baseContext();
    noCity.remove("city");
    AgentResult withoutCity = service.recommend(context(noCity, List.of()));
    assertThat(withoutCity.status()).isEqualTo("REFUSED");
    assertThat(withoutCity.unknownFields()).contains("city");
    assertContractCompliant(withoutCity);

    AgentResult withoutAnchor = service.recommend(context(baseContext(), List.of()));
    assertThat(withoutAnchor.status()).isEqualTo("REFUSED");
    assertThat(withoutAnchor.unknownFields()).contains("anchor");
    assertContractCompliant(withoutAnchor);
    verify(amap, never()).searchFoodNearby(anyString(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("两个数据源都没配置时 DEGRADED、零候选，不编造 POI")
  void degradesWithoutKeys() {
    AgentResult result = recommend(baseContext());
    assertThat(result.status()).isEqualTo("DEGRADED");
    assertThat(result.facts()).isEmpty();
    assertThat(result.advice()).isNull();
    assertThat(result.notices()).anyMatch(n -> n.contains("未编造候选"));
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("半径被就近取档时要说出来，零候选要给出可执行的下一步")
  void announcesSnappedRadiusOnEmptyResult() {
    Map<String, Object> input = baseContext();
    input.put("radiusMeters", 102);
    AgentResult result = recommend(input);
    assertThat(result.status()).isEqualTo("DEGRADED");
    assertThat(result.facts()).isEmpty();
    assertThat(result.notices())
        .anyMatch(n -> n.contains("就近取档") && n.contains("500"));
    assertThat(result.notices())
        .anyMatch(n -> n.contains("半径 500 米内") && n.contains("可扩大半径"));
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("半径本来就在档位上时不重复提示")
  void staysQuietWhenRadiusAlreadyOnStep() {
    Map<String, Object> input = baseContext();
    input.put("radiusMeters", 1000);
    AgentResult result = recommend(input);
    assertThat(result.notices()).noneMatch(n -> n.contains("就近取档"));
  }

  @Test
  @DisplayName("显式传空忌口数组视为不限制，不算缺项")
  void explicitEmptyDietaryIsNotMissing() {
    AgentResult result = recommend(baseContext());
    assertThat(result.unknownFields()).doesNotContain("dietaryFlags");
    assertThat(result.notices()).noneMatch(n -> n.contains("不能替代商户确认"));
  }

  @Test
  @DisplayName("连锁快餐与连锁饮品默认剔除，并在 notice 里报条数")
  void dropsChainsByDefault() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B2", "星巴克(大岳武当金街店)", FoodPlace.SOURCE_AMAP, "咖啡厅;咖啡厅",
            30.2595, 120.1305, 4.3, 35.0, "08:00-22:00"),
        place("B1", "农家香菜馆(金街店)", FoodPlace.SOURCE_AMAP, "中餐厅;农家菜",
            30.2592, 120.1302, 4.5, 60.0, "09:00-21:00")));

    AgentResult result = recommend(baseContext());

    assertThat(result.facts()).hasSize(1);
    assertThat(result.facts().get(0).value()).containsEntry("name", "农家香菜馆(金街店)");
    assertThat(result.notices()).anyMatch(n -> n.contains("已剔除 1 条连锁快餐或饮品候选"));
    assertThat(result.facts()).noneMatch(f -> String.valueOf(f.value().get("name")).contains("星巴克"));
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("勾选含连锁时保留连锁候选")
  void keepsChainsWhenAsked() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B2", "华莱士(武当山店)", FoodPlace.SOURCE_AMAP, "快餐厅",
            30.2595, 120.1305, 4.0, 25.0, "09:00-22:00"),
        place("B1", "农家香菜馆(金街店)", FoodPlace.SOURCE_AMAP, "中餐厅;农家菜",
            30.2592, 120.1302, 4.5, 60.0, "09:00-21:00")));
    Map<String, Object> input = baseContext();
    input.put("includeChains", true);

    AgentResult result = recommend(input);

    assertThat(result.facts()).hasSize(2);
    assertThat(result.notices()).noneMatch(n -> n.contains("连锁快餐或饮品候选"));
  }

  @Test
  @DisplayName("本地口味候选排在前面，并标注 locality")
  void ranksLocalFlavorFirst() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("A1", "某小馆", FoodPlace.SOURCE_AMAP, "中餐厅",
            30.2595, 120.1305, 4.2, 55.0, "10:00-21:00"),
        place("Z9", "老字号土菜馆", FoodPlace.SOURCE_AMAP, "中餐厅;土菜",
            30.2591, 120.1309, 4.6, 70.0, "10:00-21:00")));

    AgentResult result = recommend(baseContext());

    assertThat(result.facts()).hasSize(2);
    assertThat(result.facts().get(0).value()).containsEntry("name", "老字号土菜馆");
    assertThat(result.facts().get(0).value()).containsEntry("locality", "LOCAL");
    assertThat(result.facts().get(1).value()).containsEntry("locality", "OTHER");
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("真实 POI 装配成 CONTENT + category=FOOD，证据一律 unverified")
  void buildsContractShapedFoodFacts() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B1", "知味观", FoodPlace.SOURCE_AMAP, "杭帮菜;老字号",
            30.2595, 120.1305, 4.7, 68.0, "10:00-21:00")));
    when(baidu.configured()).thenReturn(true);
    when(baidu.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("BD1", "另一家", FoodPlace.SOURCE_BAIDU, "面馆",
            30.2600, 120.1310, null, null, null)));

    AgentResult result = recommend(baseContext());
    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.facts()).hasSize(2);
    assertThat(result.advice()).isNotBlank();
    Fact fact = result.facts().get(0);
    assertThat(fact.type()).isEqualTo("CONTENT");
    assertThat(fact.value()).containsEntry("category", "FOOD")
        .containsEntry("sourceLayer", "FACT")
        .containsKey("distanceMeters").containsKey("constraintNotes");
    assertThat(fact.evidence().verificationStatus()).isEqualTo(Evidence.UNVERIFIED);
    assertThat(fact.evidence().sourceKind()).isEqualTo("AMAP");
    assertThat(fact.evidence().fetchedAt()).isNotNull();
    assertThat(result.facts().get(1).evidence().sourceKind()).isEqualTo("EXTERNAL");
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("忌口硬约束整条剔除，不回显枚举原文与被剔除店名")
  void dietaryFlagsHardFilterWithoutEcho() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B1", "花生小馆", FoodPlace.SOURCE_AMAP, "花生", 30.2595, 120.1305,
            4.0, 30.0, null),
        place("B2", "素面馆", FoodPlace.SOURCE_AMAP, "素面", 30.2596, 120.1306,
            null, 25.0, null)));
    Map<String, Object> input = baseContext();
    input.put("dietaryFlags", List.of("PEANUT"));

    AgentResult result = recommend(input);
    assertThat(result.facts()).hasSize(1);
    String serialized = result.toString();
    assertThat(serialized).doesNotContain("花生小馆").doesNotContain("PEANUT");
    assertThat(result.notices()).anyMatch(n -> n.contains("剔除 1 条"));
    assertThat(result.notices()).anyMatch(n -> n.contains("不能替代商户确认"));
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("showReviews / showReasons 关掉时不带评分外链与理由标签")
  void respectsDisplayToggles() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B1", "知味观", FoodPlace.SOURCE_AMAP, "杭帮菜",
            30.2595, 120.1305, 4.7, 68.0, null)));
    Map<String, Object> input = baseContext();
    input.put("showReviews", false);
    input.put("showReasons", false);

    AgentResult result = recommend(input);
    Map<String, Object> value = result.facts().get(0).value();
    assertThat(value).doesNotContainKeys("rating", "ratingSource", "ratingFetchedAt",
        "reviewLinks");
    assertThat(value.get("reasonTags")).isEqualTo(List.of());
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("缺预算、时段、忌口时写进 unknownFields，不替用户填空")
  void missingChoicesAreReported() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of());
    AgentResult result = recommend(Map.of("city", "杭州"));
    assertThat(result.unknownFields()).contains("budgetCny", "mealSlot", "dietaryFlags");
    assertThat(result.notices()).anyMatch(n -> n.contains("缺项未替用户填空"));
  }

  @Test
  @DisplayName("跨来源同名且 50 米内只留一条")
  void dedupesCrossSourceByNameAndDistance() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("B1", "知味观", FoodPlace.SOURCE_AMAP, "杭帮菜",
            30.2595, 120.1305, 4.7, 68.0, null)));
    when(baidu.configured()).thenReturn(true);
    when(baidu.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of(
        place("BD1", "知味观", FoodPlace.SOURCE_BAIDU, "杭帮菜",
            30.2596, 120.1306, 4.6, 70.0, null)));
    assertThat(recommend(baseContext()).facts()).hasSize(1);
  }

  @Test
  @DisplayName("锚点 WGS-84 先转 GCJ-02 再查高德；百度拿到 BD-09LL 锚点")
  void convertsAnchorsForBothSources() {
    when(amap.configured()).thenReturn(true);
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of());
    when(baidu.configured()).thenReturn(true);
    when(baidu.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of());
    service.recommend(context(baseContext(), List.of(anchor(39.9087, 116.3975, "WGS-84"))));

    ArgumentCaptor<String> amapLocation = ArgumentCaptor.forClass(String.class);
    verify(amap).searchFoodNearby(amapLocation.capture(), anyInt(), anyInt());
    String[] amapParts = amapLocation.getValue().split(",");
    assertThat(Double.parseDouble(amapParts[0]))
        .isCloseTo(116.403743573, Offset.offset(1e-6));
    assertThat(Double.parseDouble(amapParts[1]))
        .isCloseTo(39.910103499, Offset.offset(1e-6));

    ArgumentCaptor<String> baiduLocation = ArgumentCaptor.forClass(String.class);
    verify(baidu).searchFoodNearby(baiduLocation.capture(), anyInt(), anyInt());
    String[] baiduParts = baiduLocation.getValue().split(",");
    // GCJ-02 (39.910103499, 116.403743573) -> BD-09LL
    assertThat(Double.parseDouble(baiduParts[0]))
        .isCloseTo(39.916442750, Offset.offset(1e-6));
    assertThat(Double.parseDouble(baiduParts[1]))
        .isCloseTo(116.410116586, Offset.offset(1e-6));
  }

  @Test
  @DisplayName("候选超上限按固定顺序截取 50 条，标 DEGRADED 且响应仍合规")
  void capsAtFiftyFacts() {
    when(amap.configured()).thenReturn(true);
    List<FoodPlace> many = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      many.add(place("B" + i, "店" + i, FoodPlace.SOURCE_AMAP, "中餐",
          30.26 + i * 0.0001, 120.13, null, null, null));
    }
    when(amap.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(many);
    when(baidu.configured()).thenReturn(true);
    when(baidu.searchFoodNearby(anyString(), anyInt(), anyInt())).thenReturn(List.of());

    AgentResult result = recommend(baseContext());
    assertThat(result.facts()).hasSize(50);
    assertThat(result.status()).isEqualTo("DEGRADED");
    assertThat(result.notices()).anyMatch(n -> n.contains("超过契约上限"));
    assertContractCompliant(result);
  }

  @Test
  @DisplayName("内置 Bean 注册后 descriptor 合规，异常不外泄")
  void builtinFoodBeanIsRegisteredAndSafe() {
    AgentProvider bean = new FoodProviderConfiguration()
        .builtinFood(rules, service);
    assertThat(bean.descriptor().id()).isEqualTo("builtin.food");
    assertThat(bean.descriptor().capabilities()).containsExactly("FOOD_RECOMMEND");
    assertThat(bean.descriptor().active()).isTrue();
    AgentResult result = bean.invoke(context(baseContext(), List.of()),
        new RunBudget(Instant.now().plusSeconds(10), 1, 4));
    assertThat(result.status()).isEqualTo("REFUSED");
    assertContractCompliant(result);
  }
}
