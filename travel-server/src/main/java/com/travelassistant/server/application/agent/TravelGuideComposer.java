package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.RouteMode;
import com.travelassistant.server.domain.location.RouteResult;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** Query budgets scale with unique required endpoints and remain capped; facts are never invented. */
@Service
public class TravelGuideComposer {
  private static final java.util.concurrent.ThreadPoolExecutor QUERY_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
      4,4,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(16),
      runnable -> { Thread thread=new Thread(runnable,"guide-query"); thread.setDaemon(true); return thread; },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
  private static final class QueryDeadline extends RuntimeException {}
  private final AmapClient amap;
  public TravelGuideComposer(AmapClient amap) { this.amap = amap; }

  public TravelGuide compose(PlanningRequest request, List<Attraction> candidates, List<DraftDay> arranged) {
    return composeResolved(request, candidates, arranged, new Lookup(request, arranged, candidates), new LinkedHashMap<>());
  }

  private TravelGuide composeResolved(PlanningRequest request, List<Attraction> candidates, List<DraftDay> original,
      Lookup lookup, Map<String, TravelGuide.Slot> alternatives) {
    lookup.issues.clear();
    lookup.day = 0;
    List<DraftDay> arranged = original.stream().map(day -> new DraftDay(day.dayIndex(),day.date(),day.note(),
        day.items().stream().filter(item -> !alternatives.containsKey("D"+day.dayIndex()+"-visit-"+item.attractionId())).toList())).toList();
    List<TravelGuide.Day> days = new ArrayList<>();
    List<TravelGuide.Cost> costs = new ArrayList<>();
    List<String> notices = new ArrayList<>();
    Map<String, Attraction> byId = new HashMap<>();
    candidates.forEach(a -> byId.put(a.id(), a));
    int count = request.effectiveDays() == null ? 0 : Math.min(30, request.effectiveDays());
    Integer people = request.travelers() != null && request.travelers() > 0 ? request.travelers() : null;
    var details = request.details();
    if(details!=null && details.hotel()==null && details.lodgingPreference()!=null) {
      if(lookup.lodging==null) {
        lookup.lodging=new ArrayList<>();
        if(amap!=null && amap.configured()) try {
          var options=lookup.invoke(() -> amap.searchPlaces(request.destination()+" "+details.lodgingPreference()+" 民宿",request.destination(),3));
          for(var place:options) lookup.lodging.add("住宿检索候选："+place.name()+"；地址="+display(place.address())
              +"；来源=高德地图，查询="+java.time.Instant.now()+"；特色匹配仅依据检索，房型、房价与空房未核验");
        } catch(RuntimeException e) { lookup.issue("HOTEL",lookup.failureReason(e),"住宿候选"); }
      }
      notices.addAll(lookup.lodging);
      notices.add("住宿偏好："+details.lodgingPreference()+"；预计"+Math.max(0,count-1)+"晚，房间数与每晚报价待确认；选定住宿后需重查每日往返路线");
    }
    boolean limited = details != null && ((details.seniors() != null && details.seniors() > 0)
        || (details.children() != null && details.children() > 0) || details.mobility() != null) || request.preferences().stream().anyMatch(p -> p.contains("少走") || p.contains("慢节奏"));
    LatLon hotel = details == null || details.hotel() == null ? null : lookup.geocode(details.hotel(), request.destination());
    LatLon cityReference = null;
    RouteResult outbound = null, inbound = null;
    String parkingNote = "";
    LatLon originPoint = null;
    if (details != null && details.origin() != null && (mode(details) == RouteMode.DRIVING || mode(details) == RouteMode.TRANSIT && details.originCity()!=null)) {
      LatLon origin = lookup.geocode(details.origin(), details.originCity());
      originPoint = origin;
      LatLon target = hotel;
      if (target == null && !arranged.isEmpty() && !arranged.getFirst().items().isEmpty()) target = lookup.coordinate(byId.get(arranged.getFirst().items().getFirst().attractionId()));
      if (target == null) { cityReference = lookup.geocode(request.destination(), request.destination()); target = cityReference; }
      String arrivalCity=details.arrivalCity()==null?request.destination():details.arrivalCity();
      outbound = mode(details)==RouteMode.TRANSIT ? lookup.crossRoute(origin,target,details.originCity(),arrivalCity,request.departDate(),"去程") : lookup.route(origin,target,details,request.destination(),"去程");
      LocalDate backDate=request.returnDate()!=null?request.returnDate():request.departDate()==null?null:request.departDate().plusDays(Math.max(0,count-1));
      inbound = mode(details)==RouteMode.TRANSIT ? lookup.crossRoute(target,origin,arrivalCity,details.originCity(),backDate,"返程") : lookup.route(target,origin,details,request.destination(),"返程");
      if (mode(details)==RouteMode.DRIVING && amap != null && amap.configured() && !lookup.parkingChecked) {
        lookup.parkingChecked = true;
        try {
          var parking = lookup.invoke(() -> amap.searchParkingNearby(details.hotel() == null ? request.destination() : details.hotel(), request.destination()));
          if (parking != null) parkingNote = "；停车候选：" + parking.parkingLots().stream().limit(2).map(lot -> lot.name() + "（" + lot.address() + "）").collect(java.util.stream.Collectors.joining("、")) + "；价格/空位未知；来源 " + parking.source() + "，查询 " + parking.queriedAt();
        } catch (RuntimeException failure) { lookup.issue("PARKING",lookup.failureReason(failure),"停车候选"); parkingNote = "；停车查询未成功，价格与空位未知"; }
        lookup.parkingNote = parkingNote;
      } else parkingNote = lookup.parkingNote;
    }

    boolean removedVisit = false;
    List<Integer> queryOrder = new ArrayList<>();
    if(details != null && details.queries() != null) for(int day:details.queries().priorityDays()) if(day<=count) queryOrder.add(day);
    for(int day=1;day<=count;day++) if(!queryOrder.contains(day)) queryOrder.add(day);
    for (int d : queryOrder) {
      lookup.day = d;
      int index = d;
      DraftDay scheduled = arranged.stream().filter(day -> day.dayIndex() == index).findFirst().orElse(null);
      LocalDate date = request.departDate() == null ? null : request.departDate().plusDays(d - 1);
      List<String> checks = new ArrayList<>();
      if (request.returnDate() != null && date != null && date.isAfter(request.returnDate())) {
        checks.add("本日超过返回日期，须先调整日期或天数"); date = null;
      }
      List<TravelGuide.Slot> slots = new ArrayList<>();
      String prefix = "D" + d;

      if (d == 1 && details != null && details.origin() != null) {
        slots.add(slot("intercity-out", "出发", "TRANSPORT", details.origin() + " → " + request.destination(), outbound == null ? "往返衔接待确认" : mode(details)==RouteMode.TRANSIT?"地图跨城换乘参考":"地图自驾估算", outbound,
            outbound == null ? mode(details)==RouteMode.DRIVING ? "自驾路线尚未查到，请核对出发/到达点；油电过路费未知，不能确认当日游览衔接" : "铁路请先确认实际发到站、班次、余票、到达时间，再查到达站→酒店接驳；未编造火车/高铁班次与报价" : outbound.durationText() + "，" + outbound.distanceText() + (mode(details)==RouteMode.TRANSIT?"；地图换乘参考，班次、发到站、旅行日期与余票须到售票渠道核实":"；地理编码端点须核对，油电等费用未知；长途每2小时休息为规划假设"+parkingNote)));
      }
      List<DraftDay.Item> visits = scheduled == null ? List.of() : scheduled.items();
      Attraction firstVisit = visits.isEmpty() ? null : byId.get(visits.getFirst().attractionId());
      LatLon firstCenter = lookup.coordinate(firstVisit);
      if (firstCenter == null && cityReference == null) cityReference = lookup.geocode(request.destination(), request.destination());
      LatLon breakfastCenter = hotel != null ? hotel : firstCenter != null ? firstCenter : cityReference;
      if (d == 1 && outbound != null && originPoint != null) breakfastCenter = originPoint;
      List<FoodPlace> breakfast = lookup.food(breakfastCenter, details, "早餐附近餐馆");
      TravelGuide.Slot outSlot = slots.stream().filter(it -> it.id().equals("intercity-out")).findFirst().orElse(null);
      if (outSlot != null) slots.remove(outSlot);
      meal(slots, costs, prefix + "-breakfast", "早餐", breakfast, people);
      if (outSlot != null) slots.add(outSlot);
      if (visits.isEmpty()) {
        slots.add(slot(prefix + "-gap", "上午/下午", "GAP", "未覆盖日", "待补充", null,
            "检索候选不足，保留休息或用户指定活动；不据此建议缩短旅行"));
      }

      if (firstCenter == null && cityReference != null) checks.add("餐馆按城市地理参考点召回，不代表靠近景点、酒店或用户；未算往返餐馆耗时");
      if (hotel != null && firstVisit != null) hotelTransfer(slots, costs, lookup, prefix + "-hotel-out", "早餐后", "酒店 → " + firstVisit.name(), hotel, firstCenter, details, request.destination(), people);
      Attraction previous = null;
      for (int v = 0; v < visits.size(); v++) {
        var item = visits.get(v);
        Attraction attraction = byId.get(item.attractionId());
        if (previous != null && attraction != null) {
          LatLon from = lookup.coordinate(previous), to = lookup.coordinate(attraction);
          RouteResult route = lookup.route(from, to, details, attraction.city(), previous.name()+" → "+item.name());
          int walkingLimit = details != null && details.maxWalkingMeters() != null ? Math.max(0, details.maxWalkingMeters()) : limited ? 1500 : 5000;
          if (route != null && route.walkingDistanceMeters() != null && route.walkingDistanceMeters() > walkingLimit) {
            checks.add("景点间步行" + route.walkingDistanceMeters() + "米超过单段上限" + walkingLimit + "米，已撤下步行段，须改乘车或减少景点；景区内部步行量未知");
            route = null;
          }
          slots.add(slot(prefix + "-transfer-" + v, "景点间", "TRANSPORT", previous.name() + " → " + item.name(),
              route == null ? "待查路线" : "地图估算", route,
              route == null ? "缺少可比坐标、配额或可用路线，耗时未知，不能承诺衔接" :
                  route.durationText() + "；" + route.distanceText() + "；另留30分钟机动（规划假设）；费用未知"));
          unknown(costs, prefix + "-transfer-" + v, "TRANSPORT", "景点间交通", people);
        }
        String visitId = prefix + "-visit-" + item.attractionId();
        slots.add(slot(visitId, v == 0 ? "上午" : "下午", "VISIT", item.name(), "待核实可执行性", null,
            item.note() + "；票价：" + display(item.ticket()) + "；开放：" + display(item.openTime())
                + "；来源：" + item.source() + "；查询：" + (attraction == null ? "未知" : attraction.retrievedAt()) + "；信息更新：" + (attraction == null ? "未知" : attraction.informationUpdatedAt()) + "；暂留" + (limited ? "半天" : "2~3小时") + "（游览时长假设）；预约、必玩项目、无障碍设施未核验"));
        BigDecimal price = referencePrice(attraction, date);
        costs.add(new TravelGuide.Cost(visitId, "TICKET", item.name() + (attraction != null && "conflict".equals(attraction.ticketStatus()) ? "门票（来源冲突，未选单一价格）" : "门票（来源参考，票种/年龄资格待确认）"), price, people, "人",
            price == null || people == null ? null : price.multiply(BigDecimal.valueOf(people)),
            price == null ? "UNKNOWN" : "REFERENCE", attraction == null ? item.source() : attraction.fieldSource("ticket"), ticketDate(attraction)));
        previous = attraction;
      }
      LatLon lunchCenter = firstCenter != null ? firstCenter : cityReference;
      List<FoodPlace> food = lookup.food(lunchCenter, details, "午餐附近餐馆");
      if (details != null && !details.dietaryRestrictions().isEmpty()) checks.add("商家未核实忌口、过敏原和交叉接触，餐馆只能待确认，未计参考消费");
      int insert = 0;
      while (insert < slots.size() && !slots.get(insert).kind().equals("VISIT") && !slots.get(insert).kind().equals("GAP")) insert++;
      insert = Math.min(insert + 1, slots.size());
      List<TravelGuide.Slot> midday = new ArrayList<>();
      meal(midday, costs, prefix + "-lunch", "午餐", food, people);
      midday.add(slot(prefix + "-rest", "午间", "REST", "休息", "规划假设", null,
          limited ? "预留60~90分钟坐下休息；设施与位置待确认，身体不适则取消下午候选" : "预留60分钟休息及机动，可按实际调整"));
      slots.addAll(insert, midday);
      LatLon lastCenter = lookup.coordinate(previous);
      meal(slots, costs, prefix + "-dinner", "晚餐", lookup.food(lastCenter != null ? lastCenter : breakfastCenter, details, "晚餐附近餐馆"), people);
      if (hotel != null && previous != null) hotelTransfer(slots, costs, lookup, prefix + "-hotel-back", "晚餐后", previous.name() + " → 酒店", lastCenter, hotel, details, request.destination(), people);
      if (d == count && details != null && details.origin() != null) slots.add(slot("intercity-back", "返程", "TRANSPORT", request.destination() + " → " + details.origin(), inbound == null ? "衔接待确认" : mode(details)==RouteMode.TRANSIT?"地图跨城换乘参考":"地图自驾估算", inbound,
          inbound == null ? "确认返程发站/班次与出发前接驳，报价和余票未接入" : inbound.durationText() + "，" + inbound.distanceText() + "；按同一酒店/目的地参考点返程，实际末日出发点改变须重查，油电/过路费用未知"));
      if (d < count) unknown(costs, prefix + "-hotel", "HOTEL", "住宿（按旅行日之间一晚，提前入住/延住另补）", null);
      if (limited) checks.add("少步行约束：默认单段1500米（可直接告诉我希望的步行上限）；地图返回的公交步行量可校验，未返回或景区内部步行量仍未知，不承诺无障碍");
      unknown(costs, prefix + "-local", "TRANSPORT", "其他未列市内交通/停车", people);
      checks.add("时段为假设；未验证开放、预约、游览时长与餐馆营业，不能视为已可执行");
      slots = timeline(slots, byId, checks, date);
      for (var slot : slots) if (slot.status().equals("UNPLACED") && !alternatives.containsKey(slot.id())) {
        alternatives.put(slot.id(), slot); removedVisit = true;
      }
      // Meal prices are rebound after validating the actual timeline, never to a discarded POI.
      costs.removeIf(c -> c.category().equals("FOOD") && c.itemId().startsWith(prefix+"-"));
      for (var slot : slots) if (slot.kind().equals("MEAL")) costs.add(mealCost(slot, people));
      for (var alternative : alternatives.values()) if (alternative.id().startsWith(prefix+"-")) {
        if (slots.stream().noneMatch(it -> it.id().equals(alternative.id()))) slots.add(alternative);
        checks.add(alternative.title()+"已移到备选，相关路线、餐馆锚点与费用已重新装配"+(alternative.note().contains("；撤下原因=")?"；"+alternative.note().substring(alternative.note().lastIndexOf("；撤下原因=")+6):""));

      }
      days.add(new TravelGuide.Day(d, date, List.copyOf(slots), List.copyOf(checks),
          scheduled == null ? "无可用景点候选；保留空档，不填入未经查询的地点" : scheduled.note()));
    }
    days.sort(Comparator.comparingInt(TravelGuide.Day::dayIndex));
    if (removedVisit) return composeResolved(request, candidates, original, lookup, alternatives);
    unknown(costs, "intercity-out", "TRANSPORT", "去程火车/高铁/自驾：" + (details == null || details.origin() == null ? "出发地待补" : details.origin()) + " → " + request.destination(), people);
    unknown(costs, "intercity-back", "TRANSPORT", "返程火车/高铁/自驾", people);
    Map<String,TravelGuide.Slot> transportSlots=new HashMap<>();
    for(var day:days) for(var slot:day.slots()) if(slot.kind().equals("TRANSPORT")) transportSlots.put(slot.id(),slot);
    List<TravelGuide.Cost> remainingTransport=new ArrayList<>();
    for(int i=0;i<costs.size();i++) {
      var cost=costs.get(i); var slot=transportSlots.get(cost.itemId());
      if(slot==null||slot.route()==null) continue;
      var route=slot.route(); var fare=route.fare();
      Integer quantity=null; BigDecimal price=null; String unit="人"; String description=slot.title();
      if(route.mode()==RouteMode.TRANSIT) {
        quantity=people; price=fare==null?null:fare.transitPerPersonCny(); description+="（地图换乘方案参考总价，非交易报价）";
      } else if(route.mode()==RouteMode.DRIVING) {
        quantity=details==null||details.vehicles()==null||details.vehicles()<=0?null:details.vehicles(); unit="车次";
        price=fare==null?null:fare.drivingTollsPerVehicleCny(); description+="（仅道路收费，车辆数须明确）";
        remainingTransport.add(new TravelGuide.Cost(cost.itemId()+"-operating","TRANSPORT",slot.title()+"油电等未覆盖费用（不含已列道路收费）",null,quantity,"车次",null,"UNKNOWN","未取得报价",null));
      } else continue;
      costs.set(i,new TravelGuide.Cost(cost.itemId(),"TRANSPORT",description,price,quantity,unit,
          price==null||quantity==null?null:price.multiply(BigDecimal.valueOf(quantity)),price==null?"UNKNOWN":"REFERENCE",
          fare==null?route.source():fare.source(),(fare==null?route.queriedAt():fare.queriedAt())==null?null:(fare==null?route.queriedAt():fare.queriedAt()).toString()));
    }
    costs.addAll(remainingTransport);
    if (details != null) {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < costs.size(); i++) {
        var c = costs.get(i); ids.add(c.itemId());
        BigDecimal amount = details.confirmedGroupCosts().get(c.itemId());
        if (amount != null) {
          String description = c.description();
          if (c.category().equals("FOOD")) {
            // 餐馆来自候选检索，用户只确认了餐费。
            description = switch (c.itemId().substring(c.itemId().indexOf('-') + 1)) {
              case "breakfast" -> "早餐";
              case "lunch" -> "午餐";
              case "dinner" -> "晚餐";
              default -> "餐费";
            };
          }
          costs.set(i, new TravelGuide.Cost(c.itemId(), c.category(), description,
              amount, 1, "全团合计", amount, "USER_CONFIRMED", "用户输入，非商家报价", LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()));
        }
      }
      details.confirmedGroupCosts().keySet().stream().filter(id -> !ids.contains(id)).forEach(id -> notices.add("费用键未匹配本次行程，未计入：" + id));
      for (String must : details.mustVisit()) if (arranged.stream().flatMap(day -> day.items().stream()).noneMatch(it -> it.name().equals(must)))
        notices.add("必去点未进入安排：" + must + "；请分别检索并核对位置，不能视为已满足");
    }
    BigDecimal known = BigDecimal.ZERO, reference = BigDecimal.ZERO;
    int unknown = 0;
    for (var cost : costs) {
      if (cost.groupAmountCny() == null) { unknown++; continue; }
      reference = reference.add(cost.groupAmountCny());
      if (cost.priceType().equals("USER_CONFIRMED")) known = known.add(cost.groupAmountCny());
    }
    String verdict = request.budget() == null ? "NO_BUDGET" : !request.currencyOrDefault().equals("CNY") ? "CURRENCY_MISMATCH"
        : known.compareTo(request.budget()) > 0 ? "EXCEEDS_LIMIT" : reference.compareTo(request.budget()) > 0 ? "REFERENCE_EXCEEDS_LIMIT" : unknown > 0 || known.compareTo(reference) != 0 ? "CANNOT_DETERMINE" : "WITHIN_CONFIRMED_SCOPE";
    notices.add("地图可能返回跨城换乘及铁路段参考，不代表指定旅行日可售班次或余票；铁路座席价仅展示备选，不重复累加到换乘总价。道路收费仅是一部分自驾成本，车辆数未提供时不按人数乘车费，油电/停车等仍需补充。");
    notices.add("骑行路线不代表有共享车辆、可停放或适合儿童；老幼优惠、餐馆营业与忌口均须单独确认。参考小计不等于完整预算，未覆盖项不能当零。");
    notices.add("老幼票种未核验：门票金额仅是全团按普通成人全价的参考场景，不代表老幼适用或已享优惠；人均/每日均为未覆盖项之外的小计。三餐候选来自附近检索，早餐供餐、特色菜和营业时间须核实。");
    notices.add("更换人数、日期、酒店或排除景点后重新生成；用 details.confirmedGroupCosts 按费用键录入全团CNY金额。没有交易报价时不承诺精确账单。");
    return new TravelGuide(List.copyOf(days), List.copyOf(costs), known, reference,
        people == null ? null : reference.divide(BigDecimal.valueOf(people), 2, RoundingMode.HALF_UP),
        count == 0 ? null : reference.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP), unknown, verdict, List.copyOf(notices), List.copyOf(lookup.issues), lookup.usage());
  }
  private final class Lookup {
    List<String> lodging;
    int foodCalls, routeCalls, geoCalls, day;
    final int routeLimit, foodLimit, geoLimit;
    final long started=System.nanoTime(), durationLimitMillis, deadline;
    boolean parkingChecked;
    String parkingNote = "";
    final LinkedHashSet<TravelGuide.QueryIssue> issues = new LinkedHashSet<>();
    final Map<String,List<FoodPlace>> food=new HashMap<>();
    final Map<String,RouteResult> routes=new HashMap<>();
    final Map<String,LatLon> geo=new HashMap<>();
    final Map<String,String> failures=new HashMap<>();
    Lookup(PlanningRequest request,List<DraftDay> arranged,List<Attraction> candidates) {
      Map<String,Attraction> known=new HashMap<>(); candidates.forEach(a -> known.put(a.id(),a));
      Set<String> endpoints=new HashSet<>(), pairs=new HashSet<>(), missingNames=new HashSet<>();
      boolean hotel=request.details()!=null && request.details().hotel()!=null;
      for(DraftDay d:arranged) {
        String previous=null;
        for(var item:d.items()) {
          var coordinate=DraftAssembler.coordinate(known.get(item.attractionId()));
          String endpoint=coordinate==null?item.name()+"|"+item.city():point(coordinate); endpoints.add(endpoint);
          if(coordinate==null) missingNames.add(item.name()+"|"+item.city());
          if(previous!=null) pairs.add(previous+"→"+endpoint);
          previous=endpoint;
        }
        if(hotel && !d.items().isEmpty()) { pairs.add("hotel→"+d.items().getFirst().attractionId()); pairs.add(d.items().getLast().attractionId()+"→hotel"); }
      }
      int days=request.effectiveDays()==null?0:Math.min(30,request.effectiveDays());
      var options=request.details()==null?null:request.details().queries();
      durationLimitMillis=Math.min(60000,Math.max(0,options==null||options.maxDurationMillis()==null?Math.min(45000,15000+days*3000):options.maxDurationMillis()));
      deadline=started+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(durationLimitMillis);
      routeLimit=limit(options==null?null:options.maxRouteCalls(),pairs.size()+2+days,64);
      foodLimit=limit(options==null?null:options.maxFoodCalls(),endpoints.size()+3,48);
      geoLimit=limit(options==null?null:options.maxGeocodeCalls(),missingNames.size()+3,32);
    }
    int limit(Integer requested,int needed,int cap) { return Math.min(cap,Math.max(0,requested==null?needed:requested)); }
    void issue(String kind,String reason,String target) { issues.add(new TravelGuide.QueryIssue(day,kind,reason,target)); }
    TravelGuide.QueryUsage usage() { return new TravelGuide.QueryUsage(routeLimit,routeCalls,foodLimit,foodCalls,geoLimit,geoCalls,durationLimitMillis,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)); }
    String failureReason(RuntimeException e) { return e instanceof QueryDeadline?"DEADLINE_EXCEEDED":e instanceof java.util.concurrent.RejectedExecutionException?"QUERY_BUSY":"REQUEST_FAILED"; }
    <T> T invoke(java.util.concurrent.Callable<T> call) {
      long remaining=deadline-System.nanoTime(); if(remaining<=0) throw new QueryDeadline();
      var future=QUERY_EXECUTOR.submit(call);
      try { return future.get(remaining,java.util.concurrent.TimeUnit.NANOSECONDS); }
      catch(java.util.concurrent.TimeoutException e) { future.cancel(true); throw new QueryDeadline(); }
      catch(InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new QueryDeadline(); }
      catch(java.util.concurrent.ExecutionException e) { if(e.getCause() instanceof RuntimeException runtime) throw runtime; throw new IllegalStateException("地图查询未完成"); }
    }
    LatLon coordinate(Attraction a) {
      LatLon point=DraftAssembler.coordinate(a);
      return point!=null?point:a==null?null:geocode(a.name(),a.city());
    }
    LatLon geocode(String name,String city) {
      String key=name+"|"+city, target="地点定位："+name;
      if(geo.containsKey(key)) { if(geo.get(key)==null) issue("GEOCODE",failures.get("g:"+key),target); return geo.get(key); }
      if(amap==null||!amap.configured()) { issue("GEOCODE","NOT_CONFIGURED",target); return null; }
      if(geoCalls>=geoLimit) { issue("GEOCODE","BUDGET_EXHAUSTED",target); return null; }
      geoCalls++; LatLon point=null; String reason="NO_RESULT";
      try { String[] parts=invoke(() -> amap.geocode(name,city)).split(","); point=new LatLon(Double.parseDouble(parts[1]),Double.parseDouble(parts[0])); }
      catch(RuntimeException e) { reason=failureReason(e); }
      geo.put(key,point);
      if(point==null) { failures.put("g:"+key,reason); issue("GEOCODE",reason,target); }
      return point;
    }
    List<FoodPlace> food(LatLon center,PlanningRequest.Details details,String target) {
      if(details!=null && !details.dietaryRestrictions().isEmpty()) { issue("FOOD","DIETARY_UNVERIFIED",target); return List.of(); }
      if(center==null) { issue("FOOD","NO_COORDINATES",target); return List.of(); }
      String key=point(center);
      if(food.containsKey(key)) { if(food.get(key).isEmpty()) issue("FOOD",failures.get("f:"+key),target+"（"+key+"）"); return food.get(key); }
      if(amap==null||!amap.configured()) { issue("FOOD","NOT_CONFIGURED",target); return List.of(); }
      if(foodCalls>=foodLimit) { issue("FOOD","BUDGET_EXHAUSTED",target+"（"+key+"）"); return List.of(); }
      foodCalls++; List<FoodPlace> result=List.of(); String reason="NO_RESULT";
      try { result=invoke(() -> amap.searchFoodNearby(key,2000,12)).stream().filter(FoodPlace::usable)
          .filter(p -> !p.name().matches(".*(星巴克|麦当劳|肯德基|瑞幸|蜜雪冰城).*"))
          .sorted(Comparator.comparing((FoodPlace p) -> p.tags()==null||!p.tags().matches(".*(小吃|地方|特色|中餐|面).*"))).limit(3).toList(); }
      catch(RuntimeException e) { reason=failureReason(e); }
      food.put(key,result);
      if(result.isEmpty()) { failures.put("f:"+key,reason); issue("FOOD",reason,target+"（"+key+"）"); }
      return result;
    }
    RouteResult crossRoute(LatLon from,LatLon to,String originCity,String arrivalCity,LocalDate date,String target) {
      if(from==null||to==null) { issue("ROUTE","NO_COORDINATES",target); return null; }
      String key=point(from)+":"+point(to)+":TRANSIT:"+originCity+":"+arrivalCity+":"+date;
      if(routes.containsKey(key)) { if(routes.get(key)==null) issue("ROUTE",failures.get("r:"+key),target); return routes.get(key); }
      if(amap==null||!amap.configured()) { issue("ROUTE","NOT_CONFIGURED",target); return null; }
      if(routeCalls>=routeLimit) { issue("ROUTE","BUDGET_EXHAUSTED",target); return null; }
      routeCalls++; RouteResult route=null; String reason="NO_RESULT";
      try { route=invoke(() -> amap.route(RouteMode.TRANSIT,point(from),point(to),originCity,arrivalCity,date)); } catch(RuntimeException e) { reason=failureReason(e); }
      routes.put(key,route); if(route==null) { failures.put("r:"+key,reason); issue("ROUTE",reason,target); } return route;
    }
    RouteResult route(LatLon from,LatLon to,PlanningRequest.Details details,String city,String target) {
      if(from==null||to==null) { issue("ROUTE","NO_COORDINATES",target); return null; }
      String key=point(from)+":"+point(to)+":"+mode(details)+":"+city;
      if(routes.containsKey(key)) { if(routes.get(key)==null) issue("ROUTE",failures.get("r:"+key),target); return routes.get(key); }
      if(amap==null||!amap.configured()) { issue("ROUTE","NOT_CONFIGURED",target); return null; }
      if(routeCalls>=routeLimit) { issue("ROUTE","BUDGET_EXHAUSTED",target); return null; }
      routeCalls++; RouteResult route=null; String reason="NO_RESULT";
      try { route=invoke(() -> amap.route(mode(details),point(from),point(to),city)); } catch(RuntimeException e) { reason=failureReason(e); }
      routes.put(key,route);
      if(route==null) { failures.put("r:"+key,reason); issue("ROUTE",reason,target); }
      return route;
    }
  }

  private void hotelTransfer(List<TravelGuide.Slot> slots, List<TravelGuide.Cost> costs, Lookup lookup,
      String id, String period, String title, LatLon from, LatLon to, PlanningRequest.Details details, String city, Integer people) {
    RouteResult route = lookup.route(from, to, details, city, title);
    slots.add(slot(id, period, "TRANSPORT", title, route == null ? "路线待查" : "地图估算", route,
        route == null ? "酒店或景点坐标未确定/路线不可用，耗时与费用未知" : route.durationText() + "，" + route.distanceText() + "；另留30分钟机动（假设），费用未知"));
    unknown(costs, id, "TRANSPORT", title, people);
  }

  private static List<TravelGuide.Slot> timeline(List<TravelGuide.Slot> slots, Map<String, Attraction> byId, List<String> checks, LocalDate date) {
    int cursor = 8 * 60;
    boolean anchored = true;
    List<TravelGuide.Slot> result = new ArrayList<>();
    Set<String> movedMeals=new HashSet<>();
    for (var slot : slots) {
      if(movedMeals.contains(slot.id())) continue;
      if (slot.kind().equals("TRANSPORT") && slot.id().startsWith("intercity-")
          && slot.route()!=null && slot.route().mode()==RouteMode.TRANSIT) {
        result.add(new TravelGuide.Slot(slot.id(),"班次及发到时刻待确认","TRANSPORT",slot.title(),
            "CONNECTION_UNVERIFIED",slot.restaurants(),slot.route(),slot.note()));
        anchored=false;
        checks.add(slot.title()+"只有地图换乘参考，尚无已确认班次，未用假设出发时刻推算当日景点到达时间");
        continue;
      }
      if (slot.kind().equals("TRANSPORT") && (slot.route()==null || slot.route().durationSeconds()<=0)) {
        result.add(new TravelGuide.Slot(slot.id(),"衔接未验证，耗时未知","TRANSPORT",slot.title(),"CONNECTION_UNVERIFIED",slot.restaurants(),null,slot.note()));
        anchored=false;
        checks.add(slot.title()+"未返回有效耗时；后续仅展示顺序，不确认到达、营业或可游览性");
        continue;
      }
      int duration = switch(slot.kind()) {
        case "VISIT" -> slot.note().contains("半天") ? 240 : 150;
        case "MEAL" -> 60;
        case "REST" -> 90;
        case "TRANSPORT" -> (slot.route().durationSeconds()+59)/60+30;
        default -> 180;
      };
      if (slot.kind().equals("MEAL") && slot.title().equals("午餐")) cursor=Math.max(cursor,720);
      if (slot.kind().equals("MEAL") && slot.title().equals("晚餐")) cursor=Math.max(cursor,1080);
      if(slot.kind().equals("MEAL")) {
        int preferred=slot.title().equals("午餐")?720:slot.title().equals("晚餐")?1080:480;
        int latest=preferred+120;
        if(!anchored) {
          result.add(checkedMeal(slot,preferred,duration,false,date)); continue;
        }
        if(cursor>latest) {
          result.add(new TravelGuide.Slot(slot.id(),clock(preferred)+"~"+clock(preferred+60)+"（建议餐时，地点待确认）",
              "MEAL",slot.title(),"MEAL_TIME_UNVERIFIED",List.of(),null,
              "用餐时间与交通/游览衔接未核验，长途期间可自备餐；不将不匹配地点计入餐费"));
          checks.add(slot.title()+"与行程衔接未确认，保留正常餐时，供餐地点与费用待补");
          continue;
        }
      }
      if(slot.kind().equals("REST") && (cursor>=15*60 || !anchored)) continue;
      if(slot.kind().equals("VISIT") && anchored) {
        int latest=18*60;
        var back=slots.stream().filter(it->it.id().equals("intercity-back") && it.route()!=null).findFirst();
        if(back.isPresent() && back.get().route().mode()!=RouteMode.TRANSIT)
          latest=Math.min(latest,21*60-(back.get().route().durationSeconds()+59)/60-60);
        if(cursor+duration>latest) {
          result.add(new TravelGuide.Slot(slot.id(),"备选，未排入","ALTERNATIVE",slot.title(),"UNPLACED",List.of(),null,
              slot.note()+"；撤下原因=交通或返程压缩当日游览时间，不能挤占正常用餐与休息"));
          checks.add(slot.title()+"与到达/返程时间冲突，已移至备选"); continue;
        }
      }
      String status=slot.status(); String note=slot.note();
      List<FoodPlace> restaurants=slot.restaurants();
      if (slot.kind().equals("VISIT")) {
        Attraction a=byId.values().stream().filter(it -> slot.id().endsWith("-visit-"+it.id())).findFirst().orElse(null);
        String opening=a==null?null:a.openTime();
        OpeningHours.Result hours=OpeningHours.evaluate(opening,date);
        if(a!=null && !"conflict".equals(a.openTimeStatus()) && hours.status()==OpeningHours.Status.CLOSED) {
          result.add(new TravelGuide.Slot(slot.id(),"备选，未排入","ALTERNATIVE",slot.title(),"UNPLACED",List.of(),null,note+"；撤下原因=原文明示游览日不开放："+opening));
          checks.add(slot.title()+"原文明示游览日不开放："+opening); continue;
        }
        if(anchored && a!=null && !"conflict".equals(a.openTimeStatus()) && hours.status()==OpeningHours.Status.OPEN) {
          var fit=hours.findStart(cursor,duration);
          if(fit.isEmpty()) {
            result.add(new TravelGuide.Slot(slot.id(),"备选，未排入","ALTERNATIVE",slot.title(),"UNPLACED",List.of(),null,note+"；撤下原因=当天开放时段无法连续游览："+opening));
            checks.add(slot.title()+"在当天各开放时段内均无法连续完成，已撤下；不能把中午闭园算作游览或假定可重复入园"); continue;
          }
          if(fit.getAsInt()>cursor) {
            int lunchStart=Math.max(cursor,720);
            if(lunchStart+60<=fit.getAsInt()) {
              var lunch=slots.stream().filter(it -> it.kind().equals("MEAL") && it.title().equals("午餐") && !movedMeals.contains(it.id()) && result.stream().noneMatch(done -> done.id().equals(it.id()))).findFirst();
              if(lunch.isPresent()) {
                result.add(checkedMeal(lunch.get(),lunchStart,60,true,date)); movedMeals.add(lunch.get().id());
                checks.add("等待下一开放时段期间先安排午餐，闭园/等待时间未计入景点游览");
              }
            }
            checks.add(slot.title()+"须等待到 "+clock(fit.getAsInt())+" 的下一开放时段，等待未计作游览");
          }
          cursor=fit.getAsInt();
        } else {
          status="TIME_UNVERIFIED";
          checks.add(slot.title()+"开放原文未能解析、存在冲突或到达时间未确定，时间安排尚未验证："+hours.reason());
        }
      }
      if (slot.kind().equals("MEAL")) {
        result.add(checkedMeal(slot,cursor,duration,anchored,date));
        if(anchored) cursor+=duration;
        continue;
      }
      result.add(new TravelGuide.Slot(slot.id(),anchored?clock(cursor)+"~"+clock(cursor+duration)+"（假设）":"示意顺序（到达时间待定）",slot.kind(),slot.title(),status,restaurants,slot.route(),note));
      if(anchored) cursor+=duration;
    }
    if(anchored && cursor>1260) checks.add("本日假设安排晚于21点，建议删减/换日，不视为已可执行");
    return result;
  }
  private static TravelGuide.Slot checkedMeal(TravelGuide.Slot slot,int cursor,int duration,boolean anchored,LocalDate date) {
    List<FoodPlace> kept=new ArrayList<>(); List<String> unknown=new ArrayList<>(); List<String> closed=new ArrayList<>();
    for(FoodPlace food:slot.restaurants()) {
      Boolean coverage=anchored?OpeningHours.covers(food.openHours(),date,cursor,duration):null;
      if(Boolean.FALSE.equals(coverage)) closed.add(food.name()+"（"+food.address()+"，GCJ-02 "+food.lon()+","+food.lat()+"）");
      else { kept.add(food); if(coverage==null) unknown.add(food.name()); }
    }
    String status=kept.isEmpty()?"NO_MEAL_MATCH":!unknown.isEmpty()||!anchored?"MEAL_TIME_UNVERIFIED":"HOURS_COVER_PLAN";
    String note=slot.note()+"; 校验用餐时间="+(anchored?clock(cursor)+"~"+clock(cursor+duration):"未定，不能判断供餐");
    if(!unknown.isEmpty()) note+="；营业/供餐未核验的可定位备选："+String.join("、",unknown);
    if(!closed.isEmpty()) note+="；营业原文不覆盖实际餐时，未作为本餐候选/费用："+String.join("、",closed);
    return new TravelGuide.Slot(slot.id(),anchored?clock(cursor)+"~"+clock(cursor+duration)+"（假设）":"示意顺序（到达时间待定）","MEAL",slot.title(),status,List.copyOf(kept),null,note);
  }
  private static String clock(int minutes) { return (minutes >= 1440 ? "次日" : "") + String.format(Locale.ROOT, "%02d:%02d", minutes / 60 % 24, minutes % 60); }

  private static RouteMode mode(PlanningRequest.Details d) {
    if (d != null && d.transportMode() != null) {
      try { return RouteMode.valueOf(d.transportMode().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
    }
    return RouteMode.TRANSIT;
  }
  private static String point(LatLon p) { return String.format(Locale.ROOT, "%.6f,%.6f", p.lon(), p.lat()); }
  private static String display(String s) { return s == null ? "未知" : s; }
  private static TravelGuide.Slot slot(String id, String period, String kind, String title, String status, RouteResult route, String note) {
    return new TravelGuide.Slot(id, period, kind, title, status, List.of(), route, note);
  }
  private static void unknown(List<TravelGuide.Cost> costs, String id, String category, String label, Integer people) {
    costs.add(new TravelGuide.Cost(id, category, label, null, people, people == null ? "全团" : "人", null, "UNKNOWN", "未取得报价", null));
  }
  private static void meal(List<TravelGuide.Slot> slots, List<TravelGuide.Cost> costs, String id, String label, List<FoodPlace> food, Integer people) {
    slots.add(new TravelGuide.Slot(id,label,"MEAL",label,"MEAL_TIME_UNVERIFIED",food,null,
        "预留60分钟（假设）；附近餐馆为可定位备选，营业原文需按实际餐时核对，不能据名称确认早餐/供餐"));
  }
  private static TravelGuide.Cost mealCost(TravelGuide.Slot slot, Integer people) {
    FoodPlace food=slot.restaurants().stream().filter(p -> p.pricePerPersonCny()!=null && Double.isFinite(p.pricePerPersonCny()) && p.pricePerPersonCny()>0).findFirst().orElse(null);
    BigDecimal price=food==null?null:BigDecimal.valueOf(food.pricePerPersonCny());
    return new TravelGuide.Cost(slot.id(),"FOOD",slot.title()+(food==null?"":"参考场景 "+food.name()+"（营业/供餐仍须确认）"),price,people,"人",
        price==null||people==null?null:price.multiply(BigDecimal.valueOf(people)),price==null?"UNKNOWN":"REFERENCE",
        food==null?"未取得参考价格":food.source(),food==null||food.fetchedAt()==null?null:food.fetchedAt().toString());
  }
  private static String ticketDate(Attraction a) {
    if(a==null) return null;
    return a.evidenceFor("ticket").stream().filter(e -> java.util.Objects.equals(e.value(),a.ticket()))
        .map(Attraction.FieldEvidence::informationUpdatedAt).filter(Objects::nonNull).findFirst().orElse(null);
  }
  static BigDecimal referencePrice(Attraction a, LocalDate date) {
    if(a==null || a.ticket()==null || a.sourceText()==null || List.of("conflict","expired","pending").contains(a.ticketStatus()==null?"":a.ticketStatus().toLowerCase(Locale.ROOT))) return null;
    String raw=a.ticket().strip();
    if(raw.contains("已过期")||raw.contains("尚未生效")) return null;
    var validity=java.util.regex.Pattern.compile("有效期 (\\d{4}-\\d{1,2}-\\d{1,2}|未返回) 至 (\\d{4}-\\d{1,2}-\\d{1,2}|未返回)").matcher(raw);
    while(validity.find()) {
      if(date==null) return null;
      try {
        var format=java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d");
        if(!validity.group(1).equals("未返回") && date.isBefore(LocalDate.parse(validity.group(1),format))) return null;
        if(!validity.group(2).equals("未返回") && date.isAfter(LocalDate.parse(validity.group(2),format))) return null;
      } catch(java.time.DateTimeException e) { return null; }
    }
    if(raw.equals("免费")||raw.equals("免票")) return BigDecimal.ZERO;
    var simple=java.util.regex.Pattern.compile("(?:成人票[：:]?\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:元|CNY)?").matcher(raw);
    if(simple.matches()) return new BigDecimal(simple.group(1));
    if(!raw.contains("成人")) return null;
    var quoted=java.util.regex.Pattern.compile("(?:票面价|渠道价)\\s*(\\d+(?:\\.\\d+)?)\\s*元").matcher(raw);
    return quoted.find()?new BigDecimal(quoted.group(1)):null;
  }
}
