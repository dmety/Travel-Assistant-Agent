package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User requirements survive history trimming; provider facts never become user requirements. */
final class TripRequirements {
  private TripRequirements() {}

  static ConversationState update(DeepSeekClient model, ObjectMapper mapper, ConversationState old, String text) {
    ConversationState state = extract(model, mapper, old, text);
    // 模型没抽到预算时，从用户原话做一次确定性兜底：只认明确写出的金额，模糊区间不猜。
    // 放在抽取之外，模型未配置/返回空/抛异常时同样生效——用户说了就是说了，
    // 不该因为模型没接住就把「预算两千」丢成待定。
    if (state.budget() == null && text != null && !text.isBlank()) {
      BigDecimal parsed = budgetFromText(text);
      if (parsed != null) {
        state = new ConversationState(state.city(), state.days(), state.travelers(),
            parsed, state.preferences(), state.planning());
      }
    }
    return state;
  }

  private static ConversationState extract(DeepSeekClient model, ObjectMapper mapper, ConversationState old, String text) {
    if (model == null || !model.configured() || text.isBlank()) return old;
    try {
      String raw = model.complete("""
          你只提取旅行需求，不回答用户，不查询事实。输出一个JSON对象，不要Markdown。
          intent只能是 start/update/confirm/preview/weather/question；否定句如不要规划是question。
          已有需求用来理解指代，只输出当前用户明确新增或修改的字段，没说的省略，不猜日期或景点。
          字段：intent,city(目的地，绝不能把出发地当目的地),days,travelers,preferences(字符串数组),
          budget(全团预算上限，阿拉伯数字，单位元；中文数字须换算，如"两千"=2000、"一万五"=15000、"1.5万"=15000；
          "人均X"仅人数明确时乘人数，否则省略；"两三千"这类模糊区间省略此字段，不要替用户猜一个值),
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
      BigDecimal budget=budget(patch,fresh,old.budget());
      List<String> prefs=new ArrayList<>(fresh?List.of():old.preferences());
      if(patch.path("preferences").isArray()) patch.get("preferences").forEach(v->{if(!prefs.contains(v.asText()))prefs.add(v.asText());});
      return new ConversationState(city,days,people,budget,prefs,planning);
    } catch (RuntimeException | java.io.IOException ignored) { return old; }
  }

  /** 只取模型明确给出的预算；数字字符串、中文数字也接住，解析不了才回退旧值。 */
  private static BigDecimal budget(JsonNode patch, boolean fresh, BigDecimal old) {
    JsonNode node = patch == null ? null : patch.get("budget");
    BigDecimal parsed = budgetValue(nodeText(node));
    return parsed != null ? parsed : (fresh ? null : old);
  }

  private static String nodeText(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isNumber()) return node.decimalValue().toPlainString();
    return node.isTextual() ? node.asText() : null;
  }

  /** 把「两千」「二千五」「一万五」「1.5万」「2000元」稳稳解析成一个数；解析不了返回 null。 */
  private static BigDecimal budgetValue(String raw) {
    if (raw == null) return null;
    String s = raw.trim()
        .replaceAll("[¥￥,，\\s]", "")
        .replace("块钱", "").replace("块", "").replace("元", "")
        .replace("人民币", "").replace("RMB", "").replace("rmb", "")
        .replaceAll("^(预算|全团预算|全团|一共|总共|总|大约|大概|约|差不多)", "")
        .replaceAll("(大约|大概|约|差不多|左右|上下|前后)$", "")
        .trim();
    if (s.isEmpty()) return null;
    Matcher mixed = MIXED_AMOUNT.matcher(s);
    if (mixed.matches()) {
      BigDecimal v = new BigDecimal(mixed.group(1))
          .multiply(BigDecimal.valueOf(unit(mixed.group(2).charAt(0))));
      return v.signum() > 0 ? plain(v) : null;
    }
    if (s.matches("\\d+(?:\\.\\d+)?")) {
      BigDecimal v = new BigDecimal(s);
      return v.signum() > 0 ? plain(v) : null;
    }
    return chineseAmount(s);
  }

  /** 纯中文数字金额；「两三千」这类相邻双数字是模糊区间，不替用户猜一个值。 */
  private static BigDecimal chineseAmount(String s) {
    if (s.matches(".*[一二两三四五六七八九]{2}.*")) return null;
    boolean hasZero = s.indexOf('零') >= 0 || s.indexOf('〇') >= 0;
    long total = 0, section = 0, num = 0, lastUnit = 0;
    for (char c : s.toCharArray()) {
      int d = digit(c);
      if (d >= 0) { num = d; continue; }
      long unit = unit(c);
      if (unit < 0) return null;
      if (unit >= 10000) {
        total += (section + num) * unit;
        section = 0; num = 0;
      } else {
        section += (num == 0 ? 1 : num) * unit;
        num = 0;
      }
      lastUnit = unit;
    }
    long value = total + section;
    // 「二千五 / 一万五 / 一千二」口语省略了末位单位：尾数按最近单位的十分位补齐。
    if (num > 0 && !hasZero && lastUnit >= 10 && digit(s.charAt(s.length() - 1)) >= 0) {
      value += num * (lastUnit / 10);
    } else {
      value += num;
    }
    return value > 0 ? BigDecimal.valueOf(value) : null;
  }

  private static int digit(char c) {
    return switch (c) {
      case '零', '〇' -> 0;
      case '一' -> 1;
      case '二', '两' -> 2;
      case '三' -> 3;
      case '四' -> 4;
      case '五' -> 5;
      case '六' -> 6;
      case '七' -> 7;
      case '八' -> 8;
      case '九' -> 9;
      default -> -1;
    };
  }

  private static long unit(char c) {
    return switch (c) {
      case '十' -> 10L;
      case '百' -> 100L;
      case '千' -> 1000L;
      case '万' -> 10000L;
      case '亿' -> 100000000L;
      default -> -1L;
    };
  }

  /** 规整成无科学计数法的 BigDecimal，避免卡片里出现 1.5E+4 这种值。 */
  private static BigDecimal plain(BigDecimal v) {
    return new BigDecimal(v.stripTrailingZeros().toPlainString());
  }

  /**
   * 从用户原话兜底取预算：只认「预算/花费…+金额」或「金额+元/块」。
   * 天数、人数等数字不带这些信号，不会被误当成钱；人均、模糊区间一律不猜。
   */
  static BigDecimal budgetFromText(String text) {
    if (text == null || text.isBlank()) return null;
    if (text.contains("人均") || text.contains("每人")) return null;
    Matcher hint = BUDGET_HINT.matcher(text);
    Matcher money = MONEY_TOKEN.matcher(text);
    String raw = hint.find() ? hint.group(1) : money.find() ? money.group(1) : null;
    return budgetValue(raw);
  }

  private static final String AMOUNT =
      "([0-9][0-9,]*(?:\\.[0-9]+)?(?:[十百千万亿])?|[零〇一二两三四五六七八九十百千万亿]+)";
  private static final Pattern MIXED_AMOUNT =
      Pattern.compile("^(\\d+(?:\\.\\d+)?)([十百千万亿])$");
  private static final Pattern BUDGET_HINT = Pattern.compile(
      "(?:预算|花费|费用|经费|不超过|控制在|上限|全程|全团|总价|总共)"
      + "[^0-9零〇一二两三四五六七八九十百千万亿]{0,8}"
      + AMOUNT + "(?![天日人晚次个])");
  private static final Pattern MONEY_TOKEN = Pattern.compile(AMOUNT + "\\s*(?:元|块钱|块|人民币)");

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
