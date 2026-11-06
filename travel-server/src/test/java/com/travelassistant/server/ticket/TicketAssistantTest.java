package com.travelassistant.server.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.ticket.*;
import com.travelassistant.server.application.ticket.TicketAssistantService.*;
import com.travelassistant.server.application.itinerary.*;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.domain.itinerary.*;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketAssistantTest {
  ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  TicketService tickets=mock(TicketService.class);
  ItineraryService trips=mock(ItineraryService.class);
  DeepSeekClient model=mock(DeepSeekClient.class);
  TicketCatalog catalog = new TicketCatalog(json,"../data/ticket_rules.json");
  TicketAssistantService service;
  @BeforeEach void setup() { service=new TicketAssistantService(catalog,tickets,trips,model,json); }
  Needs needs(String site,String date,String product,Integer age,Integer height,Boolean citizen,Boolean student) {
    return new Needs(site,LocalDate.parse(date),product,List.of(new Visitor(age,height,citizen,student)),null,null);
  }
  Result advise(Needs n) { return service.advise(new Request(null,n,null,null),null); }
  @ParameterizedTest
  @CsvSource({"八达岭长城,2026-11-21,STANDARD,30,170,true,false,35",
      "八达岭长城,2027-04-01,STANDARD,30,170,true,false,40",
      "八达岭长城,2026-11-21,STANDARD,19,170,true,true,17.5",
      "八达岭长城,2027-04-01,STANDARD,19,170,true,true,20",
      "八达岭长城,2026-11-21,STANDARD,17,170,true,false,0",
      "八达岭长城,2026-11-21,STANDARD,60,170,true,false,0",
      "中山公园,2026-11-21,STANDARD,6,125,true,false,0",
      "中山公园,2026-11-21,STANDARD,7,120,true,false,0",
      "中山公园,2026-11-21,STANDARD,7,121,true,false,1.5",
      "中山公园,2026-11-21,STANDARD,18,170,true,false,1.5",
      "中山公园,2026-11-21,STANDARD,19,170,true,false,3",
      "中山公园,2026-11-21,STANDARD,19,170,true,true,1.5",
      "首都博物馆,2026-11-21,STANDARD,30,170,true,false,0"})
  void knownPrices(String site,String date,String product,int age,int height,boolean citizen,boolean student,String total) {
    Result r=advise(needs(site,date,product,age,height,citizen,student));
    assertThat(r.estimatedTotal()).isEqualByComparingTo(total);
    assertThat(r.stockVerified()).isFalse();
    assertThat(r.evidence()).containsKey("price");
    if(site.equals("首都博物馆")) { assertThat(r.registrationSuggested()).isFalse(); }
  }
  @ParameterizedTest @CsvSource({"八达岭长城,2026-11-21,STANDARD,18,true,false",
      "八达岭长城,2026-11-21,STANDARD,17,false,false",
      "八达岭长城,2026-11-21,COMBINED,30,true,false",
      "中山公园,2027-04-01,STANDARD,30,true,false",
      "中山公园,2026-11-21,COMBINED,30,true,false",
      "中山公园,2027-06-01,STANDARD,10,true,false",
      "中山公园,2026-11-21,STANDARD,60,true,false",
      "首都博物馆,2026-11-21,SPECIAL,30,true,false",
      "八达岭长城,2026-11-21,TRANSPORT,30,true,false",
      "首都博物馆,2025-01-01,STANDARD,30,true,false"})
  void unknownIsNotFree(String site,String date,String product,int age,boolean citizen,boolean student) {
    assertThat(advise(needs(site,date,product,age,170,citizen,student)).estimatedTotal()).isNull();
  }
  @Test void incompleteAndFallback() {
    assertThat(advise(null).questions()).hasSizeGreaterThan(1);
    assertThat(service.advise(new Request("周六去八达岭",null,null,null),null).questions().toString()).contains("相对日期");
    assertThat(service.advise(new Request("2026-99-99",null,null,null),null).questions().toString()).contains("日期无效");
    assertThat(advise(new Needs("杭州西湖",null,null,null,null,null)).questions().toString()).contains("原官方入口");
    var missing = new TicketCatalog(json,"absent-file");
    assertThat(new TicketAssistantService(missing,tickets,trips,model,json).advise(new Request(null,null,null,null),null).questions().toString()).contains("损坏");
    assertThat(advise(needs("八达岭长城","2026-11-21","STANDARD",null,null,null,null)).questions()).isNotEmpty();
    assertThat(advise(needs("八达岭长城","2026-11-21","STANDARD",30,null,null,null)).questions()).isNotEmpty();
    assertThat(advise(needs("中山公园","2026-11-21","STANDARD",30,null,null,null)).questions()).isNotEmpty();
    assertThat(advise(needs("首都博物馆","2026-11-17","STANDARD",30,null,null,null)).notices().toString()).contains("闭馆日");
  }
  @Test void rejectsBadInputs() {
    assertThatThrownBy(()->service.advise(null,null)).isInstanceOf(InvalidTicketQueryException.class);
    for(String msg:List.of("1".repeat(18),"a".repeat(1501))) {
      assertThatThrownBy(()->service.advise(new Request(msg,null,null,null),null)).isInstanceOf(InvalidTicketQueryException.class);
    }
    for(Needs n:List.of(needs("八达岭","2026-11-21","BAD",30,170,true,false),
        needs("八达岭","2026-11-21","STANDARD",-1,170,true,false),
        needs("八达岭","2026-11-21","STANDARD",121,170,true,false),
        needs("八达岭","2026-11-21","STANDARD",30,2,true,false),
        new Needs("八达岭",null,"STANDARD",null,LocalTime.NOON,LocalTime.MIN))) {
      assertThatThrownBy(()->advise(n)).isInstanceOf(InvalidTicketQueryException.class);
    }
  }
  @Test void modelOnlySuppliesBoundedNeeds() {
    when(model.configured()).thenReturn(true);
    when(model.complete(anyString(),anyString())).thenReturn("{\"attraction\":\"八达岭长城\",\"date\":\"2026-11-21\",\"visitors\":[{\"age\":12}]}");
    var r=service.advise(new Request("2026-11-21去八达岭，12岁",null,null,null),null);
    assertThat(r.needs().visitors().getFirst().age()).isEqualTo(12);
    verify(model,times(1)).complete(anyString(),anyString());
    when(model.complete(anyString(),anyString())).thenReturn("{\"price\":0,\"url\":\"https://evil.test\",\"text\":\"免费\",\"extra\":1}");
    r=service.advise(new Request("怎么买",null,null,null),null);
    assertThat(r.notices().toString()).doesNotContain("evil.test");
    when(model.complete(anyString(),anyString())).thenThrow(new AgentUnavailableException("timeout"));
    assertThat(service.advise(new Request("怎么买",null,null,null),null).notices().toString()).contains("不可用");
    doReturn("{\"visitors\":[{\"age\":99}]}").when(model).complete(anyString(),anyString());
    assertThat(service.advise(new Request("一位成人",null,null,null),null).needs().visitors()).isNull();
  }
  @Test void contextAndTimeChecks() {
    var a=new DailyActivity("a",null,"首都博物馆",null,null,LocalTime.of(16,0),LocalTime.of(18,0));
    var b=new DailyActivity("b",null,"晚餐",null,null,LocalTime.of(17,0),LocalTime.of(19,0));
    var trip=Itinerary.create("u","北京",null).withDaily(List.of(new DailySchedule("d",LocalDate.parse("2026-11-21"),null,List.of(a,b))));
    when(trips.get("trip","u")).thenReturn(trip);
    var r=service.advise(new Request(null,null,"trip","a"),"u");
    assertThat(r.needs().date()).isEqualTo(LocalDate.parse("2026-11-21"));
    assertThat(r.notices().toString()).contains("入园时段","晚于闭园","时间重叠");
    var n=needs("首都博物馆","2026-11-22","STANDARD",30,170,true,false);
    assertThat(service.advise(new Request(null,n,"trip","a"),"u").notices().toString()).contains("日期与目标");
    assertThatThrownBy(()->service.advise(new Request(null,null,"trip","missing"),"u")).isInstanceOf(InvalidItineraryException.class);
    assertThatThrownBy(()->service.advise(new Request(null,null,"trip","a"),null)).isInstanceOf(ItineraryForbiddenException.class);
    assertThatThrownBy(()->service.advise(new Request(null,null,null,"a"),null)).isInstanceOf(InvalidTicketQueryException.class);
  }
  @Test void brokenAndUnverifiedCatalog(@TempDir Path dir) throws Exception {
    Path file=dir.resolve("rules.json"); Files.writeString(file,"broken");
    assertThat(new TicketCatalog(json,file.toString()).all()).isEmpty();
    Files.writeString(file,"[{\"id\":\"x\",\"name\":\"x\",\"officialUrl\":\"javascript:x\",\"fields\":{}}]");
    assertThat(new TicketCatalog(json,file.toString()).all()).isEmpty();
    var site=new TicketCatalog.Site("x","x","x","https://example.test",Map.of("price",new TicketCatalog.Fact(json.readTree("0"),null,null,null,null,null)));
    assertThat(site.value("price")).isNull(); assertThat(site.value("absent")).isNull();
    assertThat(catalog.find(" ")).isNull();
  }
}
