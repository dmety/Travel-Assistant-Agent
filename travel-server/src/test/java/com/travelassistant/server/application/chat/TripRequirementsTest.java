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
        "{\"intent\":\"update\",\"departDate\":\"2026-11-20\",\"travelers\":2,\"budget\":10000,\"details\":{\"budgetScope\":\"group_total\",\"transportMode\":\"TRANSIT\",\"seniors\":0,\"children\":0,\"mobility\":\"无\"}}",
        "{\"intent\":\"update\",\"details\":{\"lodgingPreference\":\"当地特色\",\"dietaryRestrictions\":[]}}");
    var s=ConversationState.empty(); var mapper=new ObjectMapper();
    for(String text:java.util.List.of("去大同五天","武汉出发，按天气选日","20号出发，两人全团预算一万元","特色住宿无忌口")) s=TripRequirements.update(model,mapper,s,text);
    assertThat(s.city()).isEqualTo("大同"); assertThat(s.days()).isEqualTo(5); assertThat(s.travelers()).isEqualTo(2);
    assertThat(s.planning()).containsEntry("departDate","2026-11-20").containsEntry("weatherRequest","连续3天无雨");
    assertThat(((java.util.Map<?,?>)s.planning().get("details")).get("includeWeather")).isEqualTo(true);
    assertThat(((java.util.Map<?,?>)s.planning().get("details")).get("origin")).isEqualTo("武汉");
    assertThat(TripRequirements.summary(s)).contains("当地特色","关键条件已齐");
  }

  @Test void ambiguousBudgetDoesNotBecomeGroupTotalAndSummaryAsksForMissingInputs() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"start\",\"city\":\"北京\",\"days\":2,\"travelers\":2,\"budget\":1000,"
            + "\"details\":{\"origin\":\"上海\",\"mustVisit\":[\"故宫\",\"长城\"],\"budgetScope\":\"group_total\"}}");

    var state=TripRequirements.update(model,new ObjectMapper(),ConversationState.empty(),
        "我想去北京玩两天，2个人，预算1000元，从上海出发，想去故宫和长城。");
    String summary=TripRequirements.summary(state);
    var details=(java.util.Map<?,?>)state.planning().get("details");

    assertThat(details.get("budgetScope")).isNull();
    assertThat(summary).contains("预算1000元（口径待确认）", "预算口径（全团总额还是人均）",
        "出发日期或大致月份", "交通方式", "老人和儿童人数", "必去[故宫, 长城]")
        .doesNotContain("全团预算1000元");
  }

  @Test void budgetScopeRequiresExplicitWordsAndNormalizesExplicitTotalOrPerPerson() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"start\",\"city\":\"北京\",\"days\":2,\"travelers\":2,\"budget\":1000,"
            + "\"details\":{\"budgetScope\":\"per_person\"}}",
        "{\"intent\":\"start\",\"city\":\"北京\",\"days\":2,\"travelers\":2,\"budget\":1000,"
            + "\"details\":{\"budgetScope\":\"group_total\"}}");
    var mapper=new ObjectMapper();

    var perPerson=TripRequirements.update(model,mapper,ConversationState.empty(),"北京两天两人，预算每人1000元");
    var total=TripRequirements.update(model,mapper,ConversationState.empty(),"北京两天两人，预算两人总共1000元");
    var perPersonDetails=(java.util.Map<?,?>)perPerson.planning().get("details");
    var totalDetails=(java.util.Map<?,?>)total.planning().get("details");

    assertThat(perPersonDetails.get("budgetScope")).isEqualTo("per_person");
    assertThat(totalDetails.get("budgetScope")).isEqualTo("group_total");
    assertThat(TripRequirements.summary(perPerson)).contains("人均预算1000元（2人折合全团2000元）");
    assertThat(TripRequirements.summary(total)).contains("全团预算1000元");
  }

  @Test void partyPresenceBooleansNormalizeToCountsBeforePlanning() {
    var model=mock(DeepSeekClient.class); when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn(
        "{\"intent\":\"start\",\"city\":\"北京\",\"days\":2,\"travelers\":2,"
            + "\"details\":{\"seniors\":false,\"children\":false}}",
        "{\"intent\":\"update\",\"details\":{\"seniors\":true}}");
    var mapper=new ObjectMapper();
    var state=TripRequirements.update(model,mapper,ConversationState.empty(),"两人去北京玩两天，没有老人儿童");
    var details=(java.util.Map<?,?>)state.planning().get("details");
    assertThat(details.get("seniors")).isEqualTo(0);
    assertThat(details.get("children")).isEqualTo(0);
    assertThat(TripRequirements.summary(state)).contains("老人0位","儿童0位").doesNotContain("false");

    state=TripRequirements.update(model,mapper,state,"有老人同行");
    details=(java.util.Map<?,?>)state.planning().get("details");
    assertThat(details.containsKey("seniors")).isFalse();
    assertThat(TripRequirements.summary(state)).contains("老人和儿童人数");
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
}
