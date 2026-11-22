package com.travelassistant.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.*;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.jumdata.JumdataScenicSpot;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelGuideTest {
  PlanningRequest request(int days, String details) throws Exception {
    return new PlanningRequest("大同", days, List.of(), new BigDecimal("100"), "CNY", 2,
        LocalDate.of(2026,11,23), null, new ObjectMapper().readValue(details, PlanningRequest.Details.class));
  }
  Attraction spot(String id, String name, double lon, String opening) {
    return new Attraction(id, name, "大同", "山西", "景点", null, null, opening, "60元", "地址" + id,
        null, null, null, null, 40.0, lon, "KB", List.of("KB"), "verified", "unverified", "GCJ-02");
  }
  TravelGuide compose(PlanningRequest r, List<Attraction> spots) {
    return new TravelGuideComposer(null).compose(r, spots, DraftAssembler.assemble(r, spots).dailySchedules());
  }
  @Test void keepsRequestedDaysAndUnknownCosts() throws Exception {
    var g = compose(request(5,"{}"), List.of(spot("a","云冈石窟",113,"09:00-17:00")));
    assertThat(g.days()).hasSize(5);
    assertThat(g.days().get(4).slots()).anyMatch(s -> s.kind().equals("GAP"));
    assertThat(g.costs().stream().filter(c -> c.category().equals("HOTEL"))).hasSize(4);
    assertThat(g.unknownCostCount()).isPositive();
    assertThat(g.budgetVerdict()).isEqualTo("REFERENCE_EXCEEDS_LIMIT");
  }
  @Test void separatesDistantSpotsAndNoDuplicateVisits() throws Exception {
    var r = request(3,"{}"); var a = spot("a","云冈石窟",113,"09:00-17:00");
    var b = spot("b","远郊候选",114,"09:00-17:00");
    var days = DraftAssembler.arrange(r,List.of(a,b,a));
    assertThat(days).hasSize(2);
    assertThat(days).allSatisfy(d -> assertThat(d.items()).hasSize(1));
  }
  @Test void duplicateVendorIdsAndExcludedNamesNeverReappear() throws Exception {
    var r=request(3,"{\"excludedAttractionIds\":[\"觉山寺塔\"]}");
    var candidates=List.of(spot("map:a","云冈石窟",113,"09:00-17:00"),
        spot("vendor:b","云冈石窟",113.002,"09:00-17:00"),spot("map:c","觉山寺塔",114,"09:00-17:00"));
    var draft=DraftAssembler.assemble(r,candidates);
    assertThat(draft.dailySchedules().stream().flatMap(d->d.items().stream()).map(DraftDay.Item::name).toList()).containsExactly("云冈石窟");
  }
  @Test void invalidRailAndZeroTaxiAreRemovedAtProviderBoundary() {
    var fare=new com.travelassistant.server.domain.location.RouteResult.Fare(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"CNY","REFERENCE","MAP",Instant.now());
    var invalid=new com.travelassistant.server.domain.location.RouteResult.RailSegment(null,null,null,null,null,List.of(),"unknown");
    var route=new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.TRANSIT,"1,1","2,2",1000,600,"1公里","10分钟",List.of(),"MAP",Instant.now(),0,fare,List.of(invalid));
    assertThat(route.railways()).isEmpty(); assertThat(route.fare().taxiAlternativePerVehicleCny()).isNull();
    assertThat(route.fare().transitPerPersonCny()).isNull();
    assertThat(route.fare().drivingTollsPerVehicleCny()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test void differentVendorAddressesWithoutCoordinatesDoNotDuplicateSchedule() throws Exception {
    var first=mock(com.travelassistant.server.domain.attraction.Attraction.class);
    var second=mock(com.travelassistant.server.domain.attraction.Attraction.class);
    when(first.id()).thenReturn("vendor:1"); when(second.id()).thenReturn("map:2");
    when(first.name()).thenReturn("云冈石窟"); when(second.name()).thenReturn("云冈石窟");
    when(first.city()).thenReturn("大同"); when(second.city()).thenReturn("大同市");
    when(first.address()).thenReturn("武周山南麓"); when(second.address()).thenReturn("云冈镇1号");
    var draft=DraftAssembler.assemble(request(3,"{}"),List.of(first,second));
    assertThat(draft.dailySchedules().stream().flatMap(d->d.items().stream())).hasSize(1);
  }
  @Test void flagsOpeningConflictRatherThanClaimingExecutable() throws Exception {
    var g = compose(request(1,"{}"),List.of(spot("a","博物馆",113,"周一闭馆；周二至周日09:00-17:00")));
    assertThat(g.days().getFirst().slots()).anyMatch(s -> s.status().equals("UNPLACED"));
    assertThat(g.days().getFirst().checks()).anyMatch(c -> c.contains("周一"));
  }
  @Test void groupCostIsNotMultipliedAgainAndUnknownRemains() throws Exception {
    var g = compose(request(2,"{\"confirmedGroupCosts\":{\"intercity-out\":80}}"), List.of());
    assertThat(g.knownSubtotalCny()).isEqualByComparingTo("80");
    assertThat(g.perPersonReferenceCny()).isEqualByComparingTo("40");
    assertThat(g.budgetVerdict()).isEqualTo("CANNOT_DETERMINE");
    assertThat(g.costs()).anySatisfy(c -> { if(c.itemId().equals("intercity-out")) assertThat(c.unit()).isEqualTo("全团合计"); });
  }
  @Test void confirmedLunchAmountDoesNotImplyUserChoseTheSuggestedRestaurant() throws Exception {
    var r = request(1,"{\"confirmedGroupCosts\":{\"D1-lunch\":120}}");
    var spots = List.of(spot("a","云冈石窟",113,"09:00-17:00"));
    var guide = new TravelGuideComposer(completeMap()).compose(r, spots, DraftAssembler.arrange(r, spots));
    assertThat(guide.days().getFirst().slots()).anyMatch(s ->
        s.id().equals("D1-lunch") && s.restaurants().stream().anyMatch(p -> p.name().equals("馆子")));
    assertThat(guide.costs()).filteredOn(c -> c.itemId().equals("D1-lunch")).singleElement().satisfies(c -> {
      assertThat(c.description()).isEqualTo("午餐");
      assertThat(c.groupAmountCny()).isEqualByComparingTo("120");
      assertThat(c.priceType()).isEqualTo("USER_CONFIRMED");
    });
  }
  @Test void reusedCoordinatesUseFoodCacheAcrossMealsAndDays() throws Exception {
    AmapClient api = mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of(new FoodPlace("p", "本地面馆",40.0,113.0,"小吃",4.0,30.0,null,null,"AMAP",Instant.now())));
    var r=request(5,"{}"); var spots=List.of(spot("a","景点甲",113,"09:00-17:00"),spot("b","景点乙",113,"09:00-17:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    verify(api,times(1)).searchFoodNearby(anyString(),anyInt(),anyInt());
    assertThat(g.days().getFirst().slots().stream().filter(s -> s.kind().equals("MEAL"))).allSatisfy(s -> assertThat(s.restaurants()).isNotEmpty());
  }
  @Test void unknownDietarySuitabilityDoesNotBecomeSafeRecommendation() throws Exception {
    var g=compose(request(1,"{\"dietaryRestrictions\":[\"花生过敏\"]}"),List.of());
    assertThat(g.days().getFirst().checks()).anyMatch(c -> c.contains("交叉接触"));
    assertThat(g.days().getFirst().slots()).allSatisfy(s -> assertThat(s.restaurants()).isEmpty());
  }
  @Test void tripDateControlsTicketValidity() {
    var ticket=new JumdataScenicSpot.TicketPrice("成人票","成人票","100",null,"2026-11-01","2026-11-20");
    var spot=new JumdataScenicSpot("id","黄鹤楼","湖北","武汉",null,null,null,null,null,null,null,List.of(ticket),Instant.now(),null);
    assertThat(spot.toAttraction(LocalDate.of(2026,11,19)).ticket()).contains("100");
    assertThat(spot.toAttraction(LocalDate.of(2026,11,21)).ticket()).isNull();
    assertThat(spot.toAttraction(LocalDate.of(2026,11,21)).ticketStatus()).isEqualTo("expired");
  }
  @Test void seasonalOpeningTextIsNotParsedAsCurrentDayFact() throws Exception {
    var g=compose(request(1,"{}"),List.of(spot("a","黄鹤楼",113,"冬季09:00-10:00，夏季08:00-18:00")));
    assertThat(g.days().getFirst().checks()).anyMatch(c -> c.contains("开放原文未能解析"));
    assertThat(g.days().getFirst().slots()).noneMatch(slot -> slot.status().equals("UNPLACED"));
  }
  @Test void drivingOriginCreatesBothRoutesAndKeepsUnknownOperatingCosts() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.geocode(eq("北京"),any())).thenReturn("116.400000,39.900000");
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(
        inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),350000,14400,"350公里","4小时",List.of(),"AMAP",Instant.now()));
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of());
    var r=request(2,"{\"origin\":\"北京\",\"transportMode\":\"DRIVING\"}"); var spots=List.of(spot("a","云冈石窟",113,"09:00-17:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.id().equals("intercity-out") && slot.route()!=null);
    assertThat(g.days().getLast().slots()).anyMatch(slot -> slot.id().equals("intercity-back") && slot.route()!=null);
    verify(api).route(eq(com.travelassistant.server.domain.location.RouteMode.DRIVING),eq("116.400000,39.900000"),eq("113.000000,40.000000"),eq("大同"));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("intercity-out") && c.groupAmountCny()==null);
  }
  @Test void lateOpeningRestaurantIsNotBreakfastAndCoordinatesAreVisible() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of(new FoodPlace("p","本地菜馆",40.0,113.0,"中餐",4.0,50.0,"10:00-22:00",null,"AMAP",Instant.now(),"大同市某街1号")));
    var r=request(1,"{}"); var spots=List.of(spot("a","景点甲",113,"09:00-17:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.title().equals("早餐") && slot.restaurants().isEmpty() && slot.note().contains("不覆盖实际餐时"));
    assertThat(g.render()).contains("大同市某街1号").contains("10:00-22:00");
  }
  @Test void excessiveWalkingRouteIsWithdrawn() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of());
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(
        com.travelassistant.server.domain.location.RouteMode.TRANSIT,inv.getArgument(1),inv.getArgument(2),5000,1800,"5公里","30分钟",List.of(),"AMAP",Instant.now(),2000));
    var r=request(1,"{\"maxWalkingMeters\":1000}"); var spots=List.of(spot("a","甲",113,"09:00-22:00"),spot("b","乙",113.01,"09:00-22:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().checks()).anyMatch(c -> c.contains("2000米超过单段上限1000米"));
    assertThat(g.days().getFirst().slots()).filteredOn(slot -> slot.kind().equals("TRANSPORT")).allSatisfy(slot -> assertThat(slot.route()).isNull());
  }
  @Test void sameNameDifferentLocationsRemainDistinctEvenInSchedule() throws Exception {
    var a=spot("a","同名公园",113,"09:00-18:00"); var b=spot("b","同名公园",114,"09:00-18:00");
    var merged=PlanningService.mergeEntities(List.of(a,b));
    assertThat(merged).hasSize(2);
    assertThat(DraftAssembler.arrange(request(2,"{}"),merged)).hasSize(2);
  }
  @Test void matchedMapAndCommercialFieldsKeepTheirOwnEvidence() {
    var map=new Attraction("map:a","景点","大同",null,"景点",null,null,null,null,"共同地址",null,null,null,null,40.0,113.0,"AMAP",List.of("AMAP"),null,null,"GCJ-02","2026-11-17T00:00:00Z",null);
    var vendor=new Attraction("vendor:a","景点","大同",null,"景点","5A",null,"09:00-17:00","成人票：票面价 80 元；有效期 2026-11-01 至 2026-11-30","共同地址",null,null,null,null,null,null,"Vendor",List.of("Vendor"),"unverified","unverified",null,"2026-11-17T01:00:00Z","2026-11-16");
    var a=PlanningService.mergeEntities(List.of(map,vendor)).getFirst();
    assertThat(a.lat()).isEqualTo(40.0); assertThat(a.level()).isEqualTo("5A");
    assertThat(a.evidenceFor("coordinates")).allSatisfy(e -> assertThat(e.source()).isEqualTo("AMAP"));
    assertThat(a.evidenceFor("ticket")).allSatisfy(e -> assertThat(e.source()).isEqualTo("Vendor"));
    var g=new TravelGuideComposer(null).compose(new PlanningRequest("大同",1,List.of(),null,"CNY",2,LocalDate.of(2026,11,23),null),List.of(a),DraftAssembler.arrange(new PlanningRequest("大同",1,List.of(),null,"CNY",2),List.of(a)));
    assertThat(g.costs()).anyMatch(c -> c.category().equals("TICKET") && c.groupAmountCny().compareTo(new BigDecimal("160"))==0 && c.source().contains("Vendor") && "2026-11-16".equals(c.informationDate()));
  }
  @Test void conflictingPricesRetainBothSourcesAndDoNotBecomeOneQuote() throws Exception {
    var a=spot("a","景点",113,"09:00-18:00");
    var b=new Attraction("b","景点","大同","山西","景点",null,null,"09:00-17:00","100元","地址a",null,null,null,null,40.0,113.0,"Other",List.of("Other"),"unverified","unverified","GCJ-02");
    var merged=PlanningService.mergeEntities(List.of(a,b));
    assertThat(merged.getFirst().ticketStatus()).isEqualTo("conflict");
    assertThat(merged.getFirst().evidenceFor("ticket")).hasSize(2);
    assertThat(compose(request(1,"{}"),merged).costs()).anyMatch(c -> c.category().equals("TICKET") && c.groupAmountCny()==null);
  }
  @Test void unknownBreakfastHoursStayUnverifiedRegardlessOfName() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of(new FoodPlace("p","早餐面馆",40.0,113.0,"小吃",null,20.0,null,null,"AMAP",Instant.now())));
    var r=request(1,"{}"); var spots=List.of(spot("a","甲",113,"09:00-20:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.title().equals("早餐") && slot.status().equals("MEAL_TIME_UNVERIFIED") && slot.note().contains("营业/供餐未核验"));
  }
  @Test void dinnerHoursAreCheckedAtActualDelayedTime() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of(new FoodPlace("p","晚饭馆",40.0,113.0,"中餐",null,50.0,"10:00-19:00",null,"AMAP",Instant.now())));
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.DRIVING,inv.getArgument(1),inv.getArgument(2),1000,7200,"1公里","2小时",List.of(),"AMAP",Instant.now()));
    var r=request(1,"{}"); var spots=List.of(spot("a","甲",113,"09:00-23:00"),spot("b","乙",113.01,"09:00-23:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.title().equals("乙") && slot.kind().equals("ALTERNATIVE"));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.title().equals("晚餐") && slot.period().startsWith("18:00"));
    assertThat(g.costs()).noneMatch(c -> c.itemId().equals("D1-visit-b"));
  }
  @Test void cancelledEveningVisitRebindsFoodAndHotelRoutes() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.geocode(anyString(),any())).thenReturn("113.000000,40.000000");
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.DRIVING,inv.getArgument(1),inv.getArgument(2),100,600,"100米","10分钟",List.of(),"AMAP",Instant.now()));
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenAnswer(inv -> List.of(new FoodPlace("p", inv.getArgument(0).toString().startsWith("113.010")?"取消点餐厅":"保留点餐厅",40.0,113.0,"中餐",null,30.0,"00:00-24:00",null,"AMAP",Instant.now())));
    var r=request(1,"{\"hotel\":\"酒店\"}"); var spots=List.of(spot("a","保留点",113,"09:00-23:00"),spot("b","取消点",113.01,"09:00-15:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.status().equals("UNPLACED") && slot.title().equals("取消点"));
    assertThat(g.days().getFirst().slots()).filteredOn(slot -> slot.kind().equals("TRANSPORT")).noneMatch(slot -> slot.title().contains("取消点"));
    assertThat(g.days().getFirst().slots()).flatExtracting(TravelGuide.Slot::restaurants).noneMatch(food -> food.name().equals("取消点餐厅"));
    assertThat(g.costs()).noneMatch(c -> c.itemId().contains("visit-b") || c.description().contains("取消点"));
  }
  @Test void unknownIntercityDurationProducesSequenceWithoutClock() throws Exception {
    var r=request(1,"{\"origin\":\"北京\"}"); var spots=List.of(spot("a","甲",113,"09:00-17:00"));
    var g=compose(r,spots);
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.id().equals("intercity-out") && slot.status().equals("CONNECTION_UNVERIFIED"));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.kind().equals("VISIT") && !slot.period().matches(".*[0-9]{2}:[0-9]{2}.*") && slot.status().equals("TIME_UNVERIFIED"));
  }
  @Test void longDriveArrivalRemovesFirstDayClosedVisitAndRequeriesEndpoint() throws Exception {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.geocode(eq("北京"),any())).thenReturn("116.400000,39.900000");
    when(api.geocode(eq("大同"),any())).thenReturn("113.100000,40.000000");
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of());
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.DRIVING,inv.getArgument(1),inv.getArgument(2),350000,18000,"350公里","5小时",List.of(),"AMAP",Instant.now()));
    var r=request(1,"{\"origin\":\"北京\",\"transportMode\":\"DRIVING\"}"); var spots=List.of(spot("a","下午闭馆点",113,"09:00-16:00"));
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().getFirst().slots()).noneMatch(slot -> slot.kind().equals("VISIT"));
    assertThat(g.days().getFirst().slots()).anyMatch(slot -> slot.id().equals("intercity-out") && slot.route()!=null && slot.route().destinationLngLat().equals("113.100000,40.000000"));
    assertThat(g.costs()).noneMatch(c -> c.category().equals("TICKET"));
  }
  @Test void guideUsesChineseLabelsAndNaturalLanguageEdits() throws Exception {
    String text=compose(request(1,"{}"),List.of(spot("a","甲",113,"09:00-20:00"))).render();
    assertThat(text).contains("费用明细").contains("来源参考价格").contains("改为3个人").contains("未知项");
    assertThat(text).doesNotContain("UNKNOWN","REFERENCE","EXCEEDS_LIMIT","confirmedGroupCosts","details.");
  }
  @Test void guideExplainsPopularitySignalsAndRouteOrderingWithoutClaimingHeatData() throws Exception {
    var base=spot("a","云冈石窟",113,"09:00-17:00");
    var rated=new Attraction(base.id(),base.name(),base.city(),base.province(),base.type(),"5A","4.8",
        base.openTime(),base.ticket(),base.address(),base.tel(),base.website(),base.image(),base.description(),
        base.lat(),base.lon(),base.source(),base.sources(),base.ticketStatus(),base.openTimeStatus(),
        base.coordinateSystem(),base.retrievedAt(),base.informationUpdatedAt());
    String text=compose(request(1,"{}"),List.of(rated,spot("b","华严寺",113.01,"09:00-17:00"))).render();
    assertThat(text).contains("路线编排依据：第 1 天；首站按景点候选排序")
        .contains("等级「5A」", "评分=4.8")
        .contains("评价量/热度未返回，热门程度未核验");
  }
  AmapClient completeMap() {
    AmapClient api=mock(AmapClient.class); when(api.configured()).thenReturn(true);
    when(api.geocode(anyString(),any())).thenReturn("113.000000,40.000000");
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenReturn(List.of(new FoodPlace("p","馆子",40.0,113.0,"中餐",null,30.0,"00:00-24:00",null,"AMAP",Instant.now())));
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),1000,60,"1公里","1分钟",List.of(),"AMAP",Instant.now()));
    return api;
  }
  List<DraftDay> fiveDays(List<Attraction> spots) {
    List<DraftDay> days=new ArrayList<>();
    for(int d=1;d<=5;d++) days.add(new DraftDay(d,LocalDate.of(2026,11,22).plusDays(d),"",List.of(DraftAssembler.itemOf(spots.get((d-1)*2)),DraftAssembler.itemOf(spots.get((d-1)*2+1)))));
    return days;
  }
  List<Attraction> tenSpots() {
    List<Attraction> spots=new ArrayList<>();
    for(int i=0;i<10;i++) spots.add(spot("a"+i,"景点"+i,113.1+i*.01,"00:00-24:00"));
    return spots;
  }
  @Test void fiveIndependentDaysGetAllNecessaryRoutesAndMeals() throws Exception {
    AmapClient api=completeMap(); var spots=tenSpots();
    var g=new TravelGuideComposer(api).compose(request(5,"{\"hotel\":\"酒店\"}"),spots,fiveDays(spots));
    assertThat(g.days()).hasSize(5);
    assertThat(g.queryIssues()).isEmpty();
    assertThat(g.queryUsage().routeCalls()).isEqualTo(15);
    assertThat(g.queryUsage().foodCalls()).isEqualTo(11);
    assertThat(g.days().getLast().slots()).filteredOn(slot -> slot.kind().equals("TRANSPORT")).allSatisfy(slot -> assertThat(slot.route()).isNotNull());
    assertThat(g.days().getLast().slots()).filteredOn(slot -> slot.kind().equals("MEAL")).allSatisfy(slot -> assertThat(slot.restaurants()).isNotEmpty());
  }
  @Test void limitedBudgetPrioritizesSpecifiedDayAndExplainsUnqueriedLegs() throws Exception {
    AmapClient api=completeMap(); var spots=tenSpots();
    var g=new TravelGuideComposer(api).compose(request(5,"{\"hotel\":\"酒店\",\"queries\":{\"maxRouteCalls\":3,\"maxFoodCalls\":3,\"priorityDays\":[5]}}"),spots,fiveDays(spots));
    assertThat(g.queryUsage().routeCalls()).isEqualTo(3);
    assertThat(g.queryUsage().foodCalls()).isEqualTo(3);
    assertThat(g.queryIssues()).anyMatch(issue -> issue.dayIndex()==1 && issue.reason().equals("BUDGET_EXHAUSTED") && issue.kind().equals("ROUTE"));
    assertThat(g.queryIssues()).noneMatch(issue -> issue.dayIndex()==5);
    assertThat(g.render()).contains("优先补查第5天").contains("尚未查询");
    assertThat(g.days()).extracting(TravelGuide.Day::dayIndex).containsExactly(1,2,3,4,5);
  }
  @Test void slowQueriesRespectTotalDeadlineWithoutUnlimitedThreads() throws Exception {
    AmapClient api=completeMap();
    when(api.searchFoodNearby(anyString(),anyInt(),anyInt())).thenAnswer(inv -> { Thread.sleep(500); return List.of(); });
    var spots=List.of(spot("a","景点",113,"00:00-24:00")); long start=System.nanoTime();
    var g=new TravelGuideComposer(api).compose(request(1,"{\"queries\":{\"maxDurationMillis\":40}}"),spots,DraftAssembler.arrange(request(1,"{}"),spots));
    assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThan(1500);
    assertThat(g.queryIssues()).anyMatch(issue -> issue.reason().equals("DEADLINE_EXCEEDED"));
    verify(api,atMost(1)).searchFoodNearby(anyString(),anyInt(),anyInt());
  }
  @Test void networkFailureAndMissingCoordinatesAreNotBudgetExhaustion() throws Exception {
    AmapClient api=completeMap(); when(api.route(any(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("offline"));
    var spots=List.of(spot("a","甲",113,"00:00-24:00"),spot("b","乙",113.01,"00:00-24:00"));
    var g=new TravelGuideComposer(api).compose(request(1,"{}"),spots,DraftAssembler.arrange(request(1,"{}"),spots));
    assertThat(g.queryIssues()).anyMatch(issue -> issue.reason().equals("REQUEST_FAILED") && issue.kind().equals("ROUTE"));
    assertThat(g.queryIssues()).noneMatch(issue -> issue.reason().equals("BUDGET_EXHAUSTED"));
  }
  @Test void transitFareUsesPeopleWhileTaxiStaysAlternative() throws Exception {
    AmapClient api=completeMap(); var fare=new com.travelassistant.server.domain.location.RouteResult.Fare(new BigDecimal("6"),null,new BigDecimal("40"),"CNY","REFERENCE","AMAP",Instant.now());
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.TRANSIT,inv.getArgument(1),inv.getArgument(2),1000,60,"1公里","1分钟",List.of(),"AMAP",Instant.now(),0,fare,List.of()));
    var spots=List.of(spot("a","甲",113,"00:00-24:00"),spot("b","乙",113.01,"00:00-24:00")); var r=request(1,"{}");
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("D1-transfer-1") && c.groupAmountCny().compareTo(new BigDecimal("12"))==0);
    assertThat(g.costs()).noneMatch(c -> c.description().contains("打车"));
    assertThat(g.render()).contains("打车备选约40元/车次，不计入");
  }
  @Test void drivingTollsUseVehiclesAndKeepOperatingCostsUnknown() throws Exception {
    AmapClient api=completeMap(); var fare=new com.travelassistant.server.domain.location.RouteResult.Fare(null,new BigDecimal("20"),new BigDecimal("80"),"CNY","REFERENCE","AMAP",Instant.now());
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.DRIVING,inv.getArgument(1),inv.getArgument(2),1000,60,"1公里","1分钟",List.of(),"AMAP",Instant.now(),null,fare,List.of()));
    var spots=List.of(spot("a","甲",113,"00:00-24:00"),spot("b","乙",113.01,"00:00-24:00")); var r=request(1,"{\"transportMode\":\"DRIVING\",\"vehicles\":2}");
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("D1-transfer-1") && c.groupAmountCny().compareTo(new BigDecimal("40"))==0 && c.unit().equals("车次"));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("D1-transfer-1-operating") && c.groupAmountCny()==null);
    var missing=request(1,"{\"transportMode\":\"DRIVING\"}");
    assertThat(new TravelGuideComposer(api).compose(missing,spots,DraftAssembler.arrange(missing,spots)).costs()).anyMatch(c -> c.itemId().equals("D1-transfer-1") && c.quantity()==null && c.groupAmountCny()==null);
  }
  @Test void explicitlyScopedCrossCityTransitCanBeQueriedAsMapReference() throws Exception {
    AmapClient api=completeMap();
    when(api.route(eq(com.travelassistant.server.domain.location.RouteMode.TRANSIT),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.TRANSIT,inv.getArgument(1),inv.getArgument(2),350000,7200,"350公里","2小时",List.of(),"AMAP",Instant.now(),null,new com.travelassistant.server.domain.location.RouteResult.Fare(new BigDecimal("100"),null,null,"CNY","REFERENCE","AMAP",Instant.now()),List.of(),inv.getArgument(5),inv.getArgument(3),inv.getArgument(4)));
    var spots=List.of(spot("a","甲",113,"00:00-24:00")); var r=request(2,"{\"origin\":\"北京北站\",\"originCity\":\"北京\",\"arrivalCity\":\"大同\",\"transportMode\":\"TRANSIT\"}");
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("intercity-out") && c.groupAmountCny().compareTo(new BigDecimal("200"))==0);
    verify(api).route(eq(com.travelassistant.server.domain.location.RouteMode.TRANSIT),anyString(),anyString(),eq("北京"),eq("大同"),eq(LocalDate.of(2026,11,23)));
    verify(api).route(eq(com.travelassistant.server.domain.location.RouteMode.TRANSIT),anyString(),anyString(),eq("大同"),eq("北京"),eq(LocalDate.of(2026,11,24)));
  }
  @Test void unconfirmedOvernightReturnDoesNotInventSameDayArrivalDeadline() throws Exception {
    AmapClient api=completeMap();
    when(api.route(eq(com.travelassistant.server.domain.location.RouteMode.TRANSIT),anyString(),anyString(),anyString(),anyString(),any()))
      .thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.TRANSIT,inv.getArgument(1),inv.getArgument(2),1000000,90000,"1000公里","25小时",List.of(),"AMAP",Instant.now()));
    var spots=List.of(spot("a","甲",113,"00:00-24:00"),spot("b","乙",114,"00:00-24:00"));
    var r=request(2,"{\"origin\":\"武汉\",\"originCity\":\"武汉\",\"arrivalCity\":\"大同\",\"transportMode\":\"TRANSIT\"}");
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    assertThat(g.days().get(1).slots()).anyMatch(s->s.kind().equals("VISIT"));
    assertThat(g.days().get(1).slots()).anyMatch(s->s.id().equals("intercity-back")&&s.status().equals("CONNECTION_UNVERIFIED"));
  }
  @Test void middayClosureUsesAfternoonWindowAndMovesLunchIntoWaitingGap() throws Exception {
    AmapClient api=completeMap();
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.DRIVING,inv.getArgument(1),inv.getArgument(2),1000,7200,"1公里","2小时",List.of(),"AMAP",Instant.now()));
    var spots=List.of(spot("a","分段开放馆",113,"09:00-12:00、14:00-17:00")); var r=request(1,"{\"hotel\":\"酒店\"}");
    var g=new TravelGuideComposer(api).compose(r,spots,DraftAssembler.arrange(r,spots));
    var slots=g.days().getFirst().slots();
    assertThat(slots).anyMatch(slot -> slot.kind().equals("VISIT") && slot.period().startsWith("14:00~16:30"));
    assertThat(slots).anyMatch(slot -> slot.title().equals("午餐") && slot.period().startsWith("12:00~13:00"));
    assertThat(slots.stream().filter(slot -> slot.title().equals("午餐"))).hasSize(1);
    assertThat(g.costs().stream().filter(c -> c.itemId().equals("D1-lunch"))).hasSize(1);
  }
  @Test void changingPeopleRecomputesTransitReference() throws Exception {
    AmapClient api=completeMap(); var fare=new com.travelassistant.server.domain.location.RouteResult.Fare(new BigDecimal("6"),null,null,"CNY","REFERENCE","AMAP",Instant.now());
    when(api.route(any(),anyString(),anyString(),any())).thenAnswer(inv -> new com.travelassistant.server.domain.location.RouteResult(com.travelassistant.server.domain.location.RouteMode.TRANSIT,inv.getArgument(1),inv.getArgument(2),1000,60,"1公里","1分钟",List.of(),"AMAP",Instant.now(),0,fare,List.of()));
    var spots=List.of(spot("a","甲",113,"00:00-24:00"),spot("b","乙",113.01,"00:00-24:00")); var r=request(1,"{}");
    var three=new PlanningRequest(r.destination(),r.days(),r.preferences(),r.budget(),r.currency(),3,r.departDate(),r.returnDate(),r.details());
    var g=new TravelGuideComposer(api).compose(three,spots,DraftAssembler.arrange(three,spots));
    assertThat(g.costs()).anyMatch(c -> c.itemId().equals("D1-transfer-1") && c.groupAmountCny().compareTo(new BigDecimal("18"))==0);
  }
}
