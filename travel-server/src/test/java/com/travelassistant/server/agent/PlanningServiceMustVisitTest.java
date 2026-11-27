package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import com.travelassistant.server.application.agent.PlanningTools;
import com.travelassistant.server.application.agent.ToolOutcome;
import com.travelassistant.server.application.agent.TravelGuideComposer;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningServiceMustVisitTest {

  @Test
  void matchesBroadMustVisitNameToLocalAttractionAliasAndSchedulesIt() throws Exception {
    Attraction temple = attraction("temple", "天坛公园", "北京市");
    Attraction greatWall = attraction("great-wall", "八达岭长城", "北京市");
    Attraction anotherSection = attraction("mutianyu", "慕田峪长城", "北京市");
    Attraction anotherSection2 = attraction("juyongguan", "居庸关长城", "北京市");
    PlanDraft draft = plan(List.of(temple, greatWall, anotherSection, anotherSection2), List.of(), "长城");

    assertThat(draft.candidates()).extracting(Attraction::name)
        .contains("八达岭长城", "天坛公园")
        .doesNotContain("慕田峪长城", "居庸关长城");
    assertThat(draft.dailySchedules().stream().flatMap(day -> day.items().stream())
        .map(item -> item.attractionId()).toList()).contains("great-wall");
    assertThat(draft.notices()).noneMatch(notice -> notice.contains("必去点「长城」未匹配"));
    assertThat(draft.notices()).anyMatch(notice -> notice.contains("必去点「长城」按当地候选匹配为「八达岭长城」"));
  }

  @Test
  void doesNotMatchMustVisitAliasFromAnotherCity() throws Exception {
    Attraction local = attraction("temple", "天坛公园", "北京市");
    Attraction outOfCity = attraction("great-wall", "八达岭长城", "河北省");
    PlanDraft draft = plan(List.of(local), List.of(outOfCity), "长城");

    assertThat(draft.candidates()).extracting(Attraction::name).doesNotContain("八达岭长城");
    assertThat(draft.notices()).anyMatch(notice -> notice.contains("必去点「长城」未匹配到「北京」"));
  }

  private static PlanDraft plan(List<Attraction> local, List<Attraction> nameMatches,
      String mustVisit) throws Exception {
    PlanningTools tools = mock(PlanningTools.class);
    when(tools.searchByCity(anyString(), anyInt(), org.mockito.ArgumentMatchers.any(LocalDate.class)))
        .thenReturn(ToolOutcome.ok(local));
    when(tools.searchLive(anyString(), anyInt())).thenReturn(ToolOutcome.ok(List.of()));
    when(tools.searchByName(anyString(), anyInt())).thenReturn(ToolOutcome.ok(nameMatches));
    when(tools.ticket(anyString(), anyString())).thenReturn(ToolOutcome.failed("票务未配置"));
    DeepSeekClient model = mock(DeepSeekClient.class);
    when(model.configured()).thenReturn(false);

    PlanningRequest.Details details = new ObjectMapper().readValue(
        "{\"mustVisit\":[\"" + mustVisit + "\"]}", PlanningRequest.Details.class);
    PlanningRequest request = new PlanningRequest("北京", 2, List.of(), null, "CNY", 2,
        LocalDate.of(2026, 12, 5), null, details);
    return new PlanningService(tools, model, new TravelGuideComposer(null)).plan(request);
  }

  private static Attraction attraction(String id, String name, String city) {
    String province = city.equals("河北省") ? "河北省" : "北京市";
    return new Attraction(id, name, city, province, "风景名胜", "5A景区", "4.8",
        null, null, "北京市内", null, null, null, null, 39.9, 116.4,
        "本地知识库", List.of("本地知识库"), null, null, "GCJ-02", null, null);
  }
  @Test
  void missingMandatoryAttractionProducesBlockingFinalCheck() throws Exception {
    var draft = plan(List.of(attraction("temple", "天坛公园", "北京市")), List.of(), "颐和园");
    assertThat(draft.checks()).anySatisfy(c -> {
      assertThat(c.code()).isEqualTo("MUST_VISIT_MISSING");
      assertThat(c.severity()).isEqualTo("HIGH");
    });
  }

  @Test
  void aliasSatisfiedDoesNotProduceMissingCheck() throws Exception {
    var draft = plan(List.of(attraction("palace", "故宫博物院", "北京市")), List.of(), "故宫");
    assertThat(draft.checks()).noneMatch(c -> c.code().equals("MUST_VISIT_MISSING"));
    assertThat(draft.guide().notices()).noneMatch(n -> n.contains("必去点未进入安排"));
  }

  @Test
  void preservesBothMandatoryPointsAndHighSpeedRailRequirement() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var details = mapper.readValue("{\"origin\":\"上海\",\"originCity\":\"上海\",\"transportMode\":\"TRANSIT\",\"transportPreference\":\"往返高铁\",\"mustVisit\":[\"故宫\",\"颐和园\"],\"maxAttractionsPerDay\":1}", PlanningRequest.Details.class);
    var candidates = List.of(attraction("palace", "故宫博物院", "北京市"), attraction("summer", "颐和园", "北京市"));
    var request = new PlanningRequest("北京", 3, List.of(), new java.math.BigDecimal("6000"), "CNY", 2, LocalDate.of(2026,12,12), null, details);
    var days = com.travelassistant.server.application.agent.DraftAssembler.arrange(request, candidates);
    assertThat(days).allSatisfy(d -> assertThat(d.items()).hasSizeLessThanOrEqualTo(1));
    assertThat(days.stream().flatMap(d -> d.items().stream()).map(i -> i.name()).toList()).contains("故宫博物院", "颐和园");
    var guide = new TravelGuideComposer(null).compose(request, candidates, days);
    assertThat(guide.days().stream().flatMap(d -> d.slots().stream()).filter(slot -> slot.id().startsWith("intercity-")).toList())
        .allSatisfy(slot -> assertThat(slot.route()).isNull());
    assertThat(guide.budgetSummary()).contains("未覆盖", "往返跨城交通", "住宿", "无法确认");
    assertThat(mapper.valueToTree(guide).path("budgetSummary").asText()).isEqualTo(guide.budgetSummary());
  }

  @Test
  void movesClosedMandatoryVisitToOpenDay() throws Exception {
    var details = new ObjectMapper().readValue("{\"mustVisit\":[\"故宫\"],\"maxAttractionsPerDay\":1}", PlanningRequest.Details.class);
    var palace = new Attraction("palace", "故宫博物院", "北京市", "北京市", "景点", null, null,
        "周一闭馆；周二至周日 08:30-16:30", null, "北京市内", null, null, null, null, 39.9, 116.4,
        "本地知识库", List.of("本地知识库"), null, null, "GCJ-02", null, null);
    var request = new PlanningRequest("北京", 2, List.of(), null, "CNY", 2, LocalDate.of(2026,12,14), null, details);
    var days = com.travelassistant.server.application.agent.DraftAssembler.arrange(request, List.of(palace));
    assertThat(days).hasSize(1);
    assertThat(days.getFirst().date()).isEqualTo(LocalDate.of(2026,12,15));
  }

}
