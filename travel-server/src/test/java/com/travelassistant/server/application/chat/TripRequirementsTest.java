package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class TripRequirementsTest {
  @Test void confirmedAmountsMergeAndNewPartyClearsOnlyOldTotals() {
    var model=mock(DeepSeekClient.class);when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
      "{\"intent\":\"update\",\"details\":{\"confirmedGroupCosts\":{\"D1-lunch\":120}}}",
      "{\"intent\":\"update\",\"details\":{\"confirmedGroupCosts\":{\"D1-dinner\":200}}}",
      "{\"intent\":\"update\",\"travelers\":3,\"details\":{\"confirmedGroupCosts\":{\"D1-lunch\":180}}}");
    var m=new ObjectMapper(); var s=ConversationState.empty();
    s=TripRequirements.update(model,m,s,"午餐已付120");
    s=TripRequirements.update(model,m,s,"晚餐已付200");
    assertThat(((java.util.Map<?,?>)((java.util.Map<?,?>)s.planning().get("details")).get("confirmedGroupCosts")).size()).isEqualTo(2);
    s=TripRequirements.update(model,m,s,"改3人，午餐实际180");
    var costs=(java.util.Map<?,?>)((java.util.Map<?,?>)s.planning().get("details")).get("confirmedGroupCosts");
    assertThat(costs.size()).isEqualTo(1);assertThat(costs.get("D1-lunch")).isEqualTo(new java.math.BigDecimal("180"));
  }
  @Test void dateWeatherAndBudgetSurviveUnrelatedUpdatesAndHistoryTrimming() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"start\",\"city\":\"大同\",\"days\":5}",
        "{\"intent\":\"weather\",\"weatherRequest\":\"连续3天无雨\",\"details\":{\"origin\":\"武汉\",\"includeWeather\":true}}",
        "{\"intent\":\"update\",\"departDate\":\"2026-11-20\",\"travelers\":2,\"budget\":10000}",
        "{\"intent\":\"update\",\"details\":{\"lodgingPreference\":\"当地特色\",\"dietaryRestrictions\":[]}}");
    var s=ConversationState.empty(); var mapper=new ObjectMapper();
    for(String text:java.util.List.of("去大同五天","武汉出发，按天气选日","20号出发，两人一万元","特色住宿无忌口")) s=TripRequirements.update(model,mapper,s,text);
    assertThat(s.city()).isEqualTo("大同"); assertThat(s.days()).isEqualTo(5); assertThat(s.travelers()).isEqualTo(2);
    assertThat(s.planning()).containsEntry("departDate","2026-11-20").containsEntry("weatherRequest","连续3天无雨");
    assertThat(((java.util.Map<?,?>)s.planning().get("details")).get("includeWeather")).isEqualTo(true);
    assertThat(((java.util.Map<?,?>)s.planning().get("details")).get("origin")).isEqualTo("武汉");
    assertThat(TripRequirements.summary(s)).contains("当地特色","关键条件已齐");
  }
  @Test void exclusionsPersistAcrossLaterUpdates() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"update\",\"details\":{\"excludedAttractionIds\":[\"觉山寺塔\"],\"mustVisit\":[\"云冈石窟\"]}}",
        "{\"intent\":\"update\",\"travelers\":3}");
    var mapper=new ObjectMapper(); var s=TripRequirements.update(model,mapper,ConversationState.empty(),"不去觉山寺塔，改去云冈石窟");
    s=TripRequirements.update(model,mapper,s,"改成3人");
    assertThat(TripRequirements.summary(s)).contains("不去[觉山寺塔]","必去[云冈石窟]","3人");
  }

  @Test void chineseBudgetInPatchAndRawTextFallback() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    // 模型返回中文字符串；以及模型完全漏掉 budget 字段（实测「预算两千」丢失的场景）。
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"start\",\"city\":\"武当山\",\"days\":4,\"budget\":\"两千\"}",
        "{\"intent\":\"start\",\"city\":\"武当山\",\"days\":4}",
        "{\"intent\":\"start\",\"city\":\"武当山\",\"days\":4}");
    var m=new ObjectMapper();
    assertThat(TripRequirements.update(model,m,ConversationState.empty(),"武当山四日，预算两千").budget())
        .isEqualByComparingTo("2000");
    assertThat(TripRequirements.update(model,m,ConversationState.empty(),"武当山四日，预算两千").budget())
        .isEqualByComparingTo("2000");
    // 数字字符串也要接住
    assertThat(TripRequirements.update(model,m,ConversationState.empty(),"预算2000元").budget())
        .isEqualByComparingTo("2000");
  }

  @Test void commonAmountExpressionsParseDeterministically() {
    assertThat(TripRequirements.budgetFromText("预算两千")).isEqualByComparingTo("2000");
    assertThat(TripRequirements.budgetFromText("预算一万五")).isEqualByComparingTo("15000");
    assertThat(TripRequirements.budgetFromText("预算1.5万")).isEqualByComparingTo("15000");
    assertThat(TripRequirements.budgetFromText("预算2千")).isEqualByComparingTo("2000");
    assertThat(TripRequirements.budgetFromText("花费五千块")).isEqualByComparingTo("5000");
    assertThat(TripRequirements.budgetFromText("全团预算 3,000 元")).isEqualByComparingTo("3000");
    assertThat(TripRequirements.budgetFromText("不超过八百")).isEqualByComparingTo("800");
    assertThat(TripRequirements.budgetFromText("预算二万三")).isEqualByComparingTo("23000");
    assertThat(TripRequirements.budgetFromText("预算一千二")).isEqualByComparingTo("1200");
    assertThat(TripRequirements.budgetFromText("预算一百零八元")).isEqualByComparingTo("108");
  }

  @Test void ambiguousRangesAndNonMonetaryNumbersAreNotGuessed() {
    assertThat(TripRequirements.budgetFromText("预算两三千")).isNull();
    assertThat(TripRequirements.budgetFromText("预算大概四五千")).isNull();
    assertThat(TripRequirements.budgetFromText("人均两千")).isNull();
    assertThat(TripRequirements.budgetFromText("每人两千块")).isNull();
    assertThat(TripRequirements.budgetFromText("预算不多，先看看")).isNull();
    assertThat(TripRequirements.budgetFromText("武当山四日，三个人")).isNull();
    assertThat(TripRequirements.budgetFromText("预算，四天后再说")).isNull();
    assertThat(TripRequirements.budgetFromText("这个月花了三天")).isNull();
  }

  @Test void budgetSurvivesUpdatesAndResetsOnFreshStart() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"update\",\"budget\":10000}",
        "{\"intent\":\"update\",\"city\":\"大理\"}",
        "{\"intent\":\"start\",\"city\":\"大理\"}");
    var m=new ObjectMapper();
    var s=TripRequirements.update(model,m,ConversationState.empty(),"20号出发，两人一万元");
    assertThat(s.budget()).isEqualByComparingTo("10000");
    s=TripRequirements.update(model,m,s,"改去大理");
    assertThat(s.budget()).isEqualByComparingTo("10000");
    s=TripRequirements.update(model,m,s,"重新规划");
    assertThat(s.budget()).isNull();
  }

  @Test void rawTextFallbackWorksEvenWhenModelIsUnavailable() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(false);
    var s=TripRequirements.update(model,new ObjectMapper(),ConversationState.empty(),"武当山四日，预算两千");
    assertThat(s.budget()).isEqualByComparingTo("2000");
  }
}
