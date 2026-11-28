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
    // Intent belongs to the current turn; an extraction failure must not replay a previous confirm/update.
    if (Boolean.TRUE.equals(old.planning().get("_structured"))) {
      Map<String,Object> safe=new LinkedHashMap<>(old.planning()); safe.put("_intent","question");
      old=new ConversationState(old.city(),old.days(),old.travelers(),old.budget(),old.preferences(),safe);
    }
    if (model == null || !model.configured() || text.isBlank()) return old;
    try {
      String raw = model.complete("""
          你只提取旅行需求，不回答用户，不查询事实。输出一个JSON对象，不要Markdown。
          intent只能是 start/update/confirm/preview/weather/question；否定句如不要规划是question。
          已有需求用来理解指代，只输出当前用户明确新增或修改的字段，没说的省略，不猜日期或景点。
          字段：intent,city(目的地，绝不能把出发地当目的地),days,travelers,budget,preferences(字符串数组),
          departDate/returnDate(YYYY-MM-DD，按天气选日尚未确认时省略),weatherRequest(天气选日原话),
          details对象字段：restDays(只休息不游览的第几天整数数组),onlyMustVisit(明确只保留必去点时true),localTransportPreference(市内打车为TAXI，与跨城高铁分开),origin,originCity,arrivalCity,hotel(仅具体酒店名或地址，特色住宿不填这里),
          lodgingPreference,budgetScope,transportPreference(跨城交通原话，如往返高铁；不得被市内公交地铁覆盖),maxAttractionsPerDay(每天最多景点数，正整数),transportMode(明确自驾DRIVING/公交TRANSIT/步行WALKING/骑行CYCLING，委托你选择舒适交通可取TRANSIT),
          seniors不得仅凭爸妈、父母等亲属称谓推断年龄或人数；年龄和老人人数未明确时省略。
          seniors,children(非负整数人数；明确没有=0；只说有但没说数量则省略),mobility,dietaryRestrictions(无忌口为空数组),mustVisit(明确必去的名称数组),
          visitDays(仅用户明确指定的景点名到第几天的对象，如故宫放第二天为{"故宫":2}),excludedAttractionIds(取消/不去的景点名称数组，不编造ID),includeWeather(问天气或按天气选日为true)。
          budgetScope只有用户明确说“全团/总预算”时取group_total，明确说“人均/每人”时取per_person；仅说预算金额时必须省略，不能按人数推断。
          用户明确确认已发生或已报价的费用，提取details.confirmedGroupCosts对象，全团人民币金额。
          键：D1-breakfast/D1-lunch/D1-dinner表示第1天三餐，D1-hotel表示第1晚，intercity-out/intercity-back表示去返程；其他天替换数字。
          预算和期望花费不是确认费用，不能填此对象；人均报价仅在人数明确时乘人数，未知键不猜。
          纠错、指出漏掉景点是update，必须提取被遗漏的必去景点及交通限制，不能作为新旅行清空旧条件。只询问修改方法且暂不生成时是question。
          start仅用于发起新旅行；补充日期人数偏好用update；明确开始规划用confirm；先出初稿用preview。
          问天气用weather；一般问答、读图、否定规划用question。数组仅输出本轮涉及内容。
          """, "今天=" + LocalDate.now(ZoneId.of("Asia/Shanghai")) + "\n已有需求="
          + mapper.writeValueAsString(extractionState(old)) + "\n用户=" + text);
      if (raw == null) return old;
      int begin=raw.indexOf('{'), end=raw.lastIndexOf('}');
      if (begin < 0 || end < begin) return old;
      JsonNode patch=mapper.readTree(raw.substring(begin,end+1));
      String intent=patch.path("intent").asText("question");
      if (!Set.of("start","update","confirm","preview","weather","question").contains(intent)) return old;
      if (!intent.equals("start")) {
        Map<String,Integer> datedVisits=explicitVisitDates(old,text);
        if(!datedVisits.isEmpty() && patch instanceof com.fasterxml.jackson.databind.node.ObjectNode mutable) {
          if(!text.matches("(?s).*(出发|启程|返程|返回|回程|改期|行程日期|旅行日期|旅行时间).*")) {
            mutable.remove("departDate"); mutable.remove("returnDate");
          }
          var detail=mutable.withObject("details");
          Map<String,Object> assignments=new LinkedHashMap<>();
          if(old.planning().get("details") instanceof Map<?,?> prior && prior.get("visitDays") instanceof Map<?,?> saved)
            saved.forEach((k,v)->assignments.put(k.toString(),v));
          assignments.putAll(datedVisits); detail.set("visitDays",mapper.valueToTree(assignments));
        }
      }
      Map<String,Object> planning=new LinkedHashMap<>(old.planning());
      if (intent.equals("start")) planning.clear();
      if (!intent.equals("question")) planning.remove("_budgetSummary");
      if (intent.equals("update") && planning.containsKey("_planContext")) planning.put("_phase","revision_pending");
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
        // 预算口径是财务语义，不能接受模型从人数和金额推断；只从用户原话的显式词语提取。
        if(e.getKey().equals("budgetScope")) return;
        if(Set.of("mustVisit","excludedAttractionIds").contains(e.getKey())) {
          LinkedHashSet<String> names=new LinkedHashSet<>();
          if(details.get(e.getKey()) instanceof List<?> values) values.forEach(v->names.add(v.toString()));
          e.getValue().forEach(v->names.add(v.asText())); details.put(e.getKey(),List.copyOf(names));
        } else if(Set.of("seniors","children").contains(e.getKey())) {
          if(e.getKey().equals("seniors") && text.matches("(?s).*(爸妈|父母|爸爸|妈妈).*")
              && !text.matches("(?s).*(老人|老年|[0-9一二三四五六七八九十]+岁).*")) return;
          Integer count=partyCount(e.getValue());
          if(count!=null) details.put(e.getKey(),count);
          else if(e.getValue().isBoolean() && e.getValue().asBoolean()) details.remove(e.getKey());
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
      if(details.get("visitDays") instanceof Map<?,?> assignments) {
        LinkedHashSet<String> names=new LinkedHashSet<>();
        if(details.get("mustVisit") instanceof List<?> must) must.forEach(v -> names.add(v.toString()));
        assignments.keySet().forEach(v -> names.add(v.toString())); details.put("mustVisit",List.copyOf(names));
      }
      String explicitBudgetScope = explicitBudgetScope(text);
      if (explicitBudgetScope != null) details.put("budgetScope", explicitBudgetScope);
      if(details.get("mustVisit") instanceof List<?> must && details.get("excludedAttractionIds") instanceof List<?> excluded)
        details.put("mustVisit",must.stream().filter(v->!excluded.contains(v)).toList());
      String hotel=String.valueOf(details.get("hotel"));
      boolean unselected=text.matches("(?s).*(酒店|住宿).{0,4}(未订|没订|未定|没定|未选|没选|还没订).*" );
      if(unselected || hotel.matches(".*(附近|周边|一带|片区)$")) {
        if(!hotel.equals("null")) details.putIfAbsent("lodgingPreference",hotel);
        details.remove("hotel");
      }
      if(text.matches("(?s).*(首末日|首尾两天|第一天和最后一天).*") && text.matches("(?s).*(只|仅).*(休息|车站).*")) {
        if(old.days()!=null) details.put("restDays",List.of(1,old.days()));
      }
      if(text.contains("只保留") || text.contains("仅保留")) details.put("onlyMustVisit",true);
      if(text.contains("打车") || text.contains("出租车") || "TAXI".equals(details.get("transportMode"))) details.put("localTransportPreference","TAXI");
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

  private static Map<String,Integer> explicitVisitDates(ConversationState state,String text) {
    Map<String,Integer> result=new LinkedHashMap<>();
    if(!(state.planning().get("details") instanceof Map<?,?> details) || !(details.get("mustVisit") instanceof List<?> names)) return result;
    try {
      LocalDate departure=LocalDate.parse(String.valueOf(state.planning().get("departDate")));
      for(Object raw:names) {
        String name=raw.toString().replaceAll("(博物院|博物馆|公园|景区)$", "");
        if(name.length()<2) continue;
        var match=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)+".{0,12}?(\\d{1,2})月(\\d{1,2})日").matcher(text);
        boolean found=match.find();
        if(!found) {
          match=java.util.regex.Pattern.compile("(\\d{1,2})月(\\d{1,2})日.{0,5}?"+java.util.regex.Pattern.quote(name)).matcher(text);
          found=match.find();
        }
        if(found) {
          LocalDate date=LocalDate.of(departure.getYear(),Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)));
          long day=java.time.temporal.ChronoUnit.DAYS.between(departure,date)+1;
          if(day>=1 && day<=30) result.put(raw.toString(),(int)day);
        }
      }
    } catch(java.time.DateTimeException ignored) { }
    return result;
  }

  private static ConversationState extractionState(ConversationState state) {
    Map<String,Object> planning=new LinkedHashMap<>(state.planning());
    planning.keySet().removeIf(key -> key.startsWith("_") && !key.equals("_phase"));
    return new ConversationState(state.city(),state.days(),state.travelers(),state.budget(),state.preferences(),planning);
  }

  private static Integer positive(JsonNode p,String key,Integer fallback) {
    if(p.path(key).canConvertToInt() && p.path(key).asInt()>0) return p.get(key).asInt();
    return fallback;
  }

  private static Integer partyCount(JsonNode value) {
    if(value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=0) return value.intValue();
    if(value.isTextual() && value.asText().strip().matches("\\d+")) {
      try { return Integer.valueOf(value.asText().strip()); }
      catch(NumberFormatException ignored) { return null; }
    }
    return value.isBoolean() && !value.asBoolean() ? 0 : null;
  }

  static String summary(ConversationState s) {
    Map<?,?> d=s.planning().get("details") instanceof Map<?,?> m?m:Map.of();
    List<String> known=new ArrayList<>(),missing=new ArrayList<>(),optional=new ArrayList<>();
    if(s.city()!=null) known.add("目的地"+s.city()); else missing.add("目的地");
    if(d.get("origin")!=null) known.add(d.get("origin")+"出发"); else missing.add("出发地");
    if(s.days()!=null) known.add(s.days()+"天"); else missing.add("游玩天数");
    if(s.travelers()!=null) known.add(s.travelers()+"人"); else missing.add("同行人数");
    if(s.budget()!=null) {
      String scope = budgetScopeKey(d.get("budgetScope"));
      if ("group_total".equals(scope)) known.add("全团预算"+amount(s.budget())+"元");
      else if ("per_person".equals(scope)) {
        String total = s.travelers() == null ? "" : "（"+s.travelers()+"人折合全团"+amount(s.budget().multiply(java.math.BigDecimal.valueOf(s.travelers())))+"元）";
        known.add("人均预算"+amount(s.budget())+"元"+total);
      } else {
        known.add("预算"+amount(s.budget())+"元（口径待确认）");
        missing.add("预算口径（全团总额还是人均）");
      }
    } else missing.add("预算金额及口径");
    if(s.planning().get("departDate")!=null) known.add(s.planning().get("departDate")+"出发"); else missing.add("出发日期或大致月份");
    if (d.get("transportMode") != null) known.add("交通"+transportLabel(d.get("transportMode")));
    else if (d.get("transportPreference") != null) known.add("交通偏好"+d.get("transportPreference"));
    else missing.add("交通方式（自驾/公共交通/包车或由我安排）");
    if (d.get("seniors") != null) known.add("老人"+d.get("seniors")+"位");
    if (d.get("children") != null) known.add("儿童"+d.get("children")+"位");
    if (d.get("seniors") == null || d.get("children") == null) missing.add("老人和儿童人数（没有可写0）");
    if (d.get("visitDays") != null) known.add("指定游览日"+d.get("visitDays"));
    if (d.get("mobility") != null) known.add("步行要求"+d.get("mobility"));
    else if (s.preferences().stream().noneMatch(TripRequirements::mentionsWalkingLimit)) optional.add("步行强度或限制（没有可写无）");
    if (d.get("lodgingPreference") != null) known.add("住宿偏好"+d.get("lodgingPreference"));
    if(!s.preferences().isEmpty())known.add("偏好"+String.join("、",s.preferences()));
    if(d.get("transportPreference") != null) known.add("跨城交通要求" + d.get("transportPreference"));
    if(d.get("maxAttractionsPerDay") instanceof Number limit) known.add("每天最多" + limit + "个景点");
    if(d.get("mustVisit") instanceof List<?> names && !names.isEmpty())known.add("必去"+names);
    if(d.get("excludedAttractionIds") instanceof List<?> names && !names.isEmpty())known.add("不去"+names);
    if(s.preferences().isEmpty() && !(d.get("mustVisit") instanceof List<?> names && !names.isEmpty())) optional.add("游玩偏好或必去点");
    boolean structured = Boolean.TRUE.equals(s.planning().get("_structured"));
    StringBuilder reply = new StringBuilder(structured ? "【规划条件待确认】" : "【旅行需求收集】");
    if (!known.isEmpty()) reply.append("\n已知：\n- ").append(String.join("\n- ", known)).append("\n");
    if (missing.isEmpty()) reply.append("关键条件已齐，回复‘确认开始规划’即可；也可继续修改。");
    else reply.append("\n还需确认：\n- ").append(String.join("\n- ", missing)).append("\n");
    if (!optional.isEmpty()) reply.append("\n可选补充：").append(String.join("、", optional)).append("。");
    if (!missing.isEmpty()) reply.append("\n未定项可写‘待定’；也可回复‘先出初稿’查看预览。");
    return reply.toString();
  }

  private static String explicitBudgetScope(String text) {
    String value = text == null ? "" : text.replaceAll("\\s+", "");
    boolean budgetMention = value.contains("预算") || value.contains("经费") || value.contains("人均")
        || value.contains("每人") || value.contains("每位");
    if (!budgetMention) return null;
    if (value.contains("人均") || value.contains("每人") || value.contains("每位")) return "per_person";
    if (value.contains("全团") || value.contains("总预算") || value.contains("总共")
        || value.contains("合计") || value.contains("一共")) return "group_total";
    return null;
  }

  private static String budgetScopeKey(Object scope) {
    if (scope == null) return null;
    String value = scope.toString().toLowerCase(Locale.ROOT);
    if (value.contains("per_person") || value.contains("人均") || value.contains("每人")) return "per_person";
    if (value.contains("group_total") || value.contains("全团") || value.contains("总额")) return "group_total";
    return null;
  }

  static boolean budgetScopeConfirmed(ConversationState state) {
    if (state == null || state.budget() == null) return true;
    Map<?, ?> details = state.planning().get("details") instanceof Map<?, ?> map ? map : Map.of();
    return budgetScopeKey(details.get("budgetScope")) != null;
  }

  private static String amount(java.math.BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static String transportLabel(Object mode) {
    return switch (mode.toString().toUpperCase(Locale.ROOT)) {
      case "DRIVING" -> "自驾";
      case "TRANSIT" -> "公共交通";
      case "WALKING" -> "步行";
      case "CYCLING" -> "骑行";
      default -> mode.toString();
    };
  }

  private static boolean mentionsWalkingLimit(String preference) {
    return preference.contains("走路") || preference.contains("步行") || preference.contains("少走")
        || preference.contains("慢节奏") || preference.contains("行动不便");
  }
}
