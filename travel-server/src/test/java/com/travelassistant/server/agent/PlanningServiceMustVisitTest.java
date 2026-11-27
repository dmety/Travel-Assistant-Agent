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
}
