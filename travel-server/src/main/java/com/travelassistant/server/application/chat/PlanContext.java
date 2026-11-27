package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Bounded snapshot of the current draft. Provider facts remain separate from user requirements. */
final class PlanContext {
  private PlanContext() {}

  static Map<String,Object> snapshot(JsonNode draft, Object previous, ObjectMapper mapper) {
    Map<String,Object> out=new LinkedHashMap<>();
    int version=previous instanceof Map<?,?> prior && prior.get("version") instanceof Number n ? n.intValue()+1 : 1;
    out.put("version",version); out.put("status","draft_unverified");
    out.put("dailySchedules",pick(draft.path("dailySchedules"),30,mapper));
    JsonNode guide=draft.path("guide");
    List<Object> days=new ArrayList<>();
    for(JsonNode day:guide.path("days")) {
      if(days.size()==30) break;
      Map<String,Object> row=fields(day,List.of("dayIndex","date","checks"),mapper);
      List<Object> slots=new ArrayList<>();
      for(JsonNode slot:day.path("slots")) {
        if(slots.size()==20) break;
        Map<String,Object> item=fields(slot,List.of("id","period","kind","title","status"),mapper);
        List<Object> restaurants=new ArrayList<>();
        for(JsonNode restaurant:slot.path("restaurants")) {
          if(restaurants.size()==3) break;
          restaurants.add(fields(restaurant,List.of("id","name","address","pricePerPersonCny","openHours","source","fetchedAt"),mapper));
        }
        item.put("restaurants",restaurants); slots.add(item);
      }
      row.put("slots",slots); days.add(row);
    }
    out.put("days",days);
    out.put("costs",pick(guide.path("costs"),300,mapper));
    out.put("budgetSummary",guide.path("budgetSummary").asText("费用覆盖未知"));
    out.put("checks",pick(draft.path("checks"),30,mapper));
    return out;
  }

  static String prompt(Object snapshot) {
    if(!(snapshot instanceof Map<?,?> raw)) return "方案快照格式无效，需要重新查询";
    Map<String,Object> data=new LinkedHashMap<>(); raw.forEach((key,value)->data.put(key.toString(),value));
    // Keep whole structured entries and disclose omissions; never cut JSON or claim omitted facts are known.
    for(String key:List.of("costs","dailySchedules","days")) {
      if(data.toString().length()<=24000) break;
      Object entries=data.get(key);
      if(entries instanceof List<?> list) {
        List<Object> kept=new ArrayList<>(list);
        int removed=0;
        while(!kept.isEmpty() && data.toString().length()>24000) {
          kept.removeLast(); removed++; data.put(key,kept);
        }
        if(removed>0) data.put(key+"Omitted",removed);
      }
    }
    return data.toString();
  }

  private static List<Object> pick(JsonNode array,int max,ObjectMapper mapper) {
    List<Object> out=new ArrayList<>();
    if(array.isArray()) for(JsonNode node:array) {
      if(out.size()==max) break;
      out.add(mapper.convertValue(node,Object.class));
    }
    return out;
  }

  private static Map<String,Object> fields(JsonNode node,List<String> names,ObjectMapper mapper) {
    Map<String,Object> out=new LinkedHashMap<>();
    for(String name:names) if(node.hasNonNull(name)) out.put(name,mapper.convertValue(node.get(name),Object.class));
    return out;
  }
}
