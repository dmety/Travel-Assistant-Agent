package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** User requirements survive history trimming; provider facts never become user requirements. */
final class TripRequirements {
  private TripRequirements() {}

  static ConversationState update(DeepSeekClient model, ObjectMapper mapper, ConversationState old, String text) {
    if (model == null || !model.configured() || text.isBlank()) return old;
    try {
      String raw = model.complete("""
          你只提取旅行需求，不回答用户，不查询事实。输出一个JSON对象，不要Markdown。
          intent只能是 start/update/confirm/preview/weather/question；否定句如不要规划是question。
          已有需求用来理解指代，只输出当前用户明确新增或修改的字段，没说的省略，不猜日期或景点。
          字段：intent,city(目的地，绝不能把出发地当目的地),days,travelers,budget,preferences(字符串数组),
          departDate/returnDate(YYYY-MM-DD，按天气选日尚未确认时省略),weatherRequest(天气选日原话),
          details对象字段：origin,originCity,arrivalCity,hotel(仅具体酒店名或地址，特色住宿不填这里),
          lodgingPreference,budgetScope,transportPreference,transportMode(明确自驾DRIVING/公交TRANSIT/步行WALKING/骑行CYCLING，委托你选择舒适交通可取TRANSIT),
          seniors,children,mobility,dietaryRestrictions(无忌口为空数组),mustVisit(明确必去的名称数组),
          excludedAttractionIds(取消/不去的景点名称数组，不编造ID),includeWeather(问天气或按天气选日为true)。
          用户明确确认已发生或已报价的费用，提取details.confirmedGroupCosts对象，全团人民币金额。
          键：D1-breakfast/D1-lunch/D1-dinner表示第1天三餐，D1-hotel表示第1晚，intercity-out/intercity-back表示去返程；其他天替换数字。
          预算和期望花费不是确认费用，不能填此对象；人均报价仅在人数明确时乘人数，未知键不猜。
          start仅用于发起新旅行；补充日期人数偏好用update；明确开始规划用confirm；先出初稿用preview。
          问天气用weather；一般问答、读图、否定规划用question。数组仅输出本轮涉及内容。
          """, "今天=" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + "\n已有需求="
          + mapper.writeValueAsString(old) + "\n用户=" + text);
      if (raw == null) return old;
      int begin=raw.indexOf('{'), end=raw.lastIndexOf('}');
      if (begin < 0 || end < begin) return old;
      JsonNode patch=mapper.readTree(raw.substring(begin,end+1));
      String intent=patch.path("intent").asText("question");
      if (!Set.of("start","update","confirm","preview","weather","question").contains(intent)) return old;
      Map<String,Object> planning=new LinkedHashMap<>(old.planning());
      if (intent.equals("start")) planning.clear();
      planning.put("_structured",true); planning.put("_intent",intent);
      if (intent.equals("start")) planning.put("_phase","collecting");
      for (String field:List.of("departDate","returnDate")) if(patch.hasNonNull(field)) {
        String value=patch.get(field).asText(); LocalDate.parse(value); planning.put(field,value);
      }
      if(patch.hasNonNull("weatherRequest")) planning.put("weatherRequest",patch.get("weatherRequest").asText());
      Map<String,Object> details=new LinkedHashMap<>();
      if(planning.get("details") instanceof Map<?,?> prior) prior.forEach((k,v)->details.put(k.toString(),v));
      // Clear stale totals before applying explicitly confirmed amounts from this turn.
      if(patch.has("travelers") || patch.has("days") || patch.has("departDate") || patch.path("details").has("transportMode"))
        details.remove("confirmedGroupCosts");
      if(patch.path("details").isObject()) patch.get("details").fields().forEachRemaining(e->{
        if(e.getValue().isNull()) return;
        if(Set.of("mustVisit","excludedAttractionIds").contains(e.getKey())) {
          LinkedHashSet<String> names=new LinkedHashSet<>();
          if(details.get(e.getKey()) instanceof List<?> values) values.forEach(v->names.add(v.toString()));
          e.getValue().forEach(v->names.add(v.asText())); details.put(e.getKey(),List.copyOf(names));
        } else if(e.getKey().equals("confirmedGroupCosts") && e.getValue().isObject()) {
          Map<String,Object> amounts=new LinkedHashMap<>();
          if(details.get(e.getKey()) instanceof Map<?,?> prior) prior.forEach((k,v)->amounts.put(k.toString(),v));
          e.getValue().fields().forEachRemaining(cost->{
            if(cost.getValue().isNumber() && cost.getValue().decimalValue().signum()>=0)
              amounts.put(cost.getKey(),cost.getValue().decimalValue());
          });
          details.put(e.getKey(),amounts);
        } else details.put(e.getKey(),mapper.convertValue(e.getValue(),Object.class));
      });
      if(details.get("mustVisit") instanceof List<?> must && details.get("excludedAttractionIds") instanceof List<?> excluded)
        details.put("mustVisit",must.stream().filter(v->!excluded.contains(v)).toList());
      planning.put("details",details);
      boolean fresh=intent.equals("start");
      String city=patch.hasNonNull("city")?patch.get("city").asText():fresh?null:old.city();
      Integer days=positive(patch,"days",fresh?null:old.days());
      Integer people=positive(patch,"travelers",fresh?null:old.travelers());
      var budget=patch.path("budget").isNumber()&&patch.get("budget").decimalValue().signum()>0?patch.get("budget").decimalValue():fresh?null:old.budget();
      List<String> prefs=new ArrayList<>(fresh?List.of():old.preferences());
      if(patch.path("preferences").isArray()) patch.get("preferences").forEach(v->{if(!prefs.contains(v.asText()))prefs.add(v.asText());});
      return new ConversationState(city,days,people,budget,prefs,planning);
    } catch (RuntimeException | java.io.IOException ignored) { return old; }
  }

  private static Integer positive(JsonNode p,String key,Integer fallback) {
    if(p.path(key).canConvertToInt() && p.path(key).asInt()>0) return p.get(key).asInt();
    return fallback;
  }

  static String summary(ConversationState s) {
    Map<?,?> d=s.planning().get("details") instanceof Map<?,?> m?m:Map.of();
    List<String> known=new ArrayList<>(),missing=new ArrayList<>();
    if(s.city()!=null) known.add("目的地"+s.city()); else missing.add("目的地");
    if(d.get("origin")!=null) known.add(d.get("origin")+"出发"); else missing.add("出发地");
    if(s.days()!=null) known.add(s.days()+"天"); else missing.add("天数");
    if(s.travelers()!=null) known.add(s.travelers()+"人"); else missing.add("人数及老幼情况");
    if(s.budget()!=null) known.add("全团预算"+s.budget()+"元"); else missing.add("预算");
    if(s.planning().get("departDate")!=null) known.add(s.planning().get("departDate")+"出发"); else missing.add("出发日期（可先查天气选日）");
    for(String key:List.of("budgetScope","transportPreference","lodgingPreference")) if(d.get(key)!=null) known.add(d.get(key).toString());
    if(!s.preferences().isEmpty())known.add(String.join("、",s.preferences()));
    if(d.get("mustVisit") instanceof List<?> names && !names.isEmpty())known.add("必去"+names);
    if(d.get("excludedAttractionIds") instanceof List<?> names && !names.isEmpty())known.add("不去"+names);
    return "【规划条件待确认】"+String.join("；",known)+"。\n"+(missing.isEmpty()?"关键条件已齐，回复‘确认开始规划’即可；也可继续修改。":"还需确认："+String.join("、",missing)+"。未定也可回复‘先出初稿’查看预览。");
  }
}
