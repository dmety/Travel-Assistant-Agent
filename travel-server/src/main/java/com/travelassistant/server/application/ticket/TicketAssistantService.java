package com.travelassistant.server.application.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.application.itinerary.InvalidItineraryException;
import com.travelassistant.server.domain.itinerary.*;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 一轮最多一次理解调用；模型输出不作为价格、资格、链接或写入指令。 */
@Service
public class TicketAssistantService {
  public record Visitor(Integer age, Integer heightCm, Boolean chineseEligible, Boolean student) {}
  public record Needs(String attraction, LocalDate date, String product, List<Visitor> visitors,
      LocalTime startTime, LocalTime endTime) {}
  public record Request(String message, Needs needs, String itineraryId, String activityId) {}
  public record Match(int visitor, String ticket, BigDecimal price) {}
  public record Result(Needs needs, List<String> questions, List<Match> matches,
      BigDecimal estimatedTotal, boolean stockVerified, boolean registrationSuggested,
      TicketAdvice channels, Map<String, TicketCatalog.Fact> evidence, List<String> notices) {}
  private final TicketCatalog catalog;
  private final TicketService tickets;
  private final ItineraryService itineraries;
  private final DeepSeekClient model;
  private final ObjectMapper json;
  public TicketAssistantService(TicketCatalog catalog, TicketService tickets,
      ItineraryService itineraries, DeepSeekClient model, ObjectMapper json) {
    this.catalog = catalog; this.tickets = tickets; this.itineraries = itineraries;
    this.model = model; this.json = json;
  }
  public Result advise(Request request, String user) {
    if (request == null) { throw new InvalidTicketQueryException("缺少需求"); }
    String message = request.message() == null ? "" : request.message().strip();
    if (message.length() > 1500 || message.matches("(?s).*\\d{15,}.*")) {
      throw new InvalidTicketQueryException("消息过长或疑似含证件号码，请只提供年龄等购票条件");
    }
    List<String> notices = new ArrayList<>();
    List<String> questions = new ArrayList<>();
    Needs n = request.needs() == null ? new Needs(null,null,null,null,null,null) : request.needs();
    Itinerary trip = null; DailyActivity activity = null; LocalDate activityDate = null;
    if (request.itineraryId() != null && !request.itineraryId().isBlank()) {
      if (user == null) { throw new com.travelassistant.server.application.itinerary.ItineraryForbiddenException(); }
      trip = itineraries.get(request.itineraryId(), user);
      if (request.activityId() != null) {
        for (DailySchedule day : trip.dailySchedules()) {
          for (DailyActivity a : day.activities()) {
            if (Objects.equals(a.id(), request.activityId())) { activity = a; activityDate = day.date(); }
          }
        }
        if (activity == null) { throw new InvalidItineraryException("活动不存在"); }
        n = new Needs(n.attraction() == null ? activity.title() : n.attraction(),
            n.date() == null ? activityDate : n.date(), n.product(), n.visitors(),
            n.startTime() == null ? activity.startTime() : n.startTime(),
            n.endTime() == null ? activity.endTime() : n.endTime());
      }
    } else if (request.activityId() != null) { throw new InvalidTicketQueryException("活动必须关联行程"); }
    // 先处理确定文本；相对日期要求用户明确，不能依赖模型猜测。
    String name = n.attraction(); LocalDate date = n.date();
    if (name == null) {
      for (var site : catalog.all()) { if (message.contains(site.name())) { name = site.name(); break; } }
      if (name == null && message.contains("八达岭")) { name = "八达岭长城"; }
    }
    var dates = java.util.regex.Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])").matcher(message);
    if (date == null && dates.find()) {
      try { date = LocalDate.parse(dates.group(1)); }
      catch (java.time.format.DateTimeParseException e) { questions.add("日期无效，请填写具体日期"); }
    }
    boolean relative = message.matches("(?s).*(今天|明天|后天|周末|周[一二三四五六日天]|星期|下周).* ".strip());
    Needs proposal = null;
    if (!message.isBlank() && model.configured()) {
      try {
        String output = model.complete("你只提取用户明确给出的购票需求，返回纯JSON。字段仅 attraction、date(YYYY-MM-DD)、visitors(数组，每项只有age整数或null)。不得推测年龄、国籍、学生资格、日期；相对日期返回null。不要返回价格、网址或说明。", message);
        JsonNode node = json.readTree(output);
        boolean allowedKeys = node.isObject();
        var keys = node.fieldNames();
        while (keys.hasNext()) {
          if (!Set.of("attraction","date","visitors").contains(keys.next())) { allowedKeys = false; }
        }
        if (allowedKeys && node.size() <= 3) {
          String extractedName = node.path("attraction").isTextual() ? node.get("attraction").asText() : null;
          LocalDate extractedDate = null;
          // 仅接受消息里原样出现的绝对日期。
          if (!relative && node.path("date").isTextual() && message.contains(node.get("date").asText())) {
            extractedDate = LocalDate.parse(node.get("date").asText());
          }
          List<Visitor> extracted = null;
          if (node.path("visitors").isArray() && node.get("visitors").size() <= 30) {
            extracted = new ArrayList<>();
            for (JsonNode v : node.get("visitors")) {
              if (v.path("age").isIntegralNumber()) {
                int age = v.get("age").asInt();
                if (!message.contains(Integer.toString(age))) { throw new IllegalArgumentException(); }
                extracted.add(new Visitor(age,null,null,null));
              } else { extracted.add(new Visitor(null,null,null,null)); }
            }
          }
          proposal = new Needs(catalog.find(extractedName) == null ? null : extractedName,
              extractedDate, null, extracted, null, null);
          notices.add("模型仅提取需求，请核对表单；价格与链接来自规则数据");
        } else { notices.add("模型输出不合约，已忽略，请使用表单"); }
      } catch (Exception e) { notices.add("模型理解不可用或输出不合约，请使用表单补全"); }
    } else { notices.add("本轮使用结构化表单和规则判断（无模型调用）"); }
    if (proposal != null) {
      if (name == null) { name = proposal.attraction(); }
      if (date == null) { date = proposal.date(); }
    }
    List<Visitor> visitors = n.visitors() != null && !n.visitors().isEmpty() ? n.visitors() : proposal == null ? n.visitors() : proposal.visitors();
    n = new Needs(name, date, n.product() == null ? "STANDARD" : n.product(), visitors,n.startTime(),n.endTime());
    validate(n);
    if (relative && date == null) { questions.add("请把相对日期明确为 YYYY-MM-DD"); }
    if (date == null) { questions.add("请选择具体游览日期"); }
    if (visitors == null || visitors.isEmpty()) { questions.add("请添加每位游客（无需姓名、证件号）"); }
    if (date != null && date.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
      notices.add("游览日期已过去，本结果仅供历史规则核对，不代表当时实际规则");
    }
    TicketCatalog.Site site = catalog.find(name);
    List<Match> matches = new ArrayList<>();
    BigDecimal total = null;
    TicketAdvice channels = name == null ? null : tickets.lookup(null,name,null);
    boolean register = true;
    if (site == null) {
      questions.add(catalog.all().isEmpty() ? "深度票务规则未配置或损坏，仍可使用原购票入口查询" : "请选择北京首批三个景点之一；其他景点仍可查询原官方入口");
    } else {
      channels = tickets.lookup(site.id(),site.name(),"北京");
      for (var f : site.fields().values()) {
        if (f.note() != null) { notices.add(f.note()); }
        if (date != null && ((f.validFrom() != null && date.isBefore(f.validFrom()))
            || (f.validTo() != null && date.isAfter(f.validTo())))) {
          notices.add("所选日期超出部分来源适用范围，相关结论待核实");
        }
      }
      LocalDate selectedDate = n.date();
      boolean inRange = site.fields().values().stream().allMatch(f -> dateInRange(selectedDate,f));
      if (inRange && visitors != null && !visitors.isEmpty() && date != null) {
        total = BigDecimal.ZERO;
        for (int i=0;i<visitors.size();i++) {
          Match match = price(site,n,visitors.get(i),i+1,questions,notices);
          matches.add(match);
          if (match.price() == null) { total = null; }
          else if (total != null) { total = total.add(match.price()); }
        }
      }
      for (String key : List.of("eligibility", "extras", "notice")) {
        JsonNode text = site.value(key);
        if (text != null && text.isTextual()) { notices.add(text.asText()); }
      }
      JsonNode reservation = site.value("reservation");
      // 「无需购票／预约」是场馆属性，不能挂在本次合计上：游客未填时合计未知，
      // 但免费场馆依然不需要购票。免费以定价档位全为 0 判定，且预约与定价都须有来源。
      boolean freeEntry = "STANDARD".equals(n.product()) && reservation != null
          && reservation.isBoolean() && !reservation.asBoolean() && freeEntrySite(site);
      register = !freeEntry;
      if (freeEntry) { notices.add("普通参观无需购票／预约，按官方指南登记入馆；不要求购票登记"); }
      checkHours(site,n,notices);
      if (trip != null && date != null) {
        if (activityDate != null && !date.equals(activityDate)) { notices.add("日期与目标活动不一致，不能直接登记"); }
        for (DailySchedule day : trip.dailySchedules()) {
          if (date.equals(day.date())) {
            for (DailyActivity a : day.activities()) {
              if (!Objects.equals(a.id(),request.activityId()) && a.hasTimeRange()
                  && n.startTime() != null && n.endTime() != null
                  && n.startTime().isBefore(a.endTime()) && n.endTime().isAfter(a.startTime())) {
                notices.add("与同日活动「" + a.title() + "」时间重叠");
              }
            }
          }
        }
      }
    }
    notices.add("余票未核验；费用仅为人工核验快照估算，不是实时售价。退改未知请查看官方购买页。");
    notices.add("未核验游玩时长和交通耗时，不保证行程可行；临时公告请出发前复核。");
    return new Result(n,questions,matches,total,false,register,channels,
        site == null ? Map.of() : site.fields(),notices);
  }
  private boolean dateInRange(LocalDate date, TicketCatalog.Fact f) {
    return date == null || ((f.validFrom() == null || !date.isBefore(f.validFrom()))
        && (f.validTo() == null || !date.isAfter(f.validTo())));
  }
  /**
   * 场馆自身定价档位全部为 0 视为免费入场，与游客构成无关。
   * 任一档位非 0 或不可解析都不作免费推定——未知不等于免费。
   */
  private boolean freeEntrySite(TicketCatalog.Site site) {
    JsonNode prices = site.value("price");
    if (prices == null || !prices.isObject() || prices.isEmpty()) { return false; }
    var it = prices.elements();
    while (it.hasNext()) {
      JsonNode v = it.next();
      if (!v.isNumber() || v.decimalValue().signum() != 0) { return false; }
    }
    return true;
  }
  private void validate(Needs n) {
    if (n.attraction() != null && n.attraction().length() > 100
        || !Set.of("STANDARD","COMBINED","SPECIAL","TRANSPORT").contains(n.product())
        || n.visitors() != null && (n.visitors().size() > 30 || n.visitors().stream().anyMatch(v -> v == null
        || v.age() != null && (v.age()<0 || v.age()>120)
        || v.heightCm() != null && (v.heightCm()<30 || v.heightCm()>250)))
        || n.startTime() != null && n.endTime() != null && !n.endTime().isAfter(n.startTime())) {
      throw new InvalidTicketQueryException("请检查产品、游客年龄／身高与起止时间");
    }
  }
  private Match price(TicketCatalog.Site site, Needs n, Visitor v, int index,
      List<String> questions, List<String> notices) {
    JsonNode prices = site.value("price"); String key = null; String label = "票种／费用待核实";
    if (prices == null || !Set.of("STANDARD","COMBINED").contains(n.product())) {
      return new Match(index,label,null);
    }
    if (site.kind().equals("CAPITAL")) {
      if (n.product().equals("STANDARD")) { key="regular"; label="北京通史基本陈列"; }
    } else if (v.age() == null) { questions.add("请填写游客"+index+"在游览日的周岁年龄"); }
    else if (site.kind().equals("BADALING") && n.product().equals("STANDARD")) {
      boolean peak=n.date().getMonthValue()>=4 && n.date().getMonthValue()<=10;
      if (v.age()<18 || v.age()>=60) {
        if (Boolean.TRUE.equals(v.chineseEligible())) { return new Match(index,"年龄免票（须符合官方身份条件）",BigDecimal.ZERO); }
        questions.add("请向官方确认游客"+index+"的免票身份条件；外籍等资格不自动推断");
      } else if (v.age()==18 || v.student()==null) {
        questions.add("请确认游客"+index+"是否符合全日制本科及以下学生优惠；18岁边界向官方核实");
      } else { key = Boolean.TRUE.equals(v.student()) ? (peak?"studentPeak":"studentOffPeak") : (peak?"peak":"offPeak"); label=Boolean.TRUE.equals(v.student())?"学生优惠门票":"成人门票"; }
    } else if (site.kind().equals("ZHONGSHAN")) {
      int month=n.date().getMonthValue();
      if (n.product().equals("COMBINED") || month>=3 && month<=5 || month==6 && n.date().getDayOfMonth()==1) {
        notices.add("联票开售／花期起止／节日优惠须核对当期公告，本次不作确定报价");
      } else if (v.age()<=6 || v.heightCm()!=null && v.heightCm()<=120) {
        return new Match(index,"儿童免票（仅普通入园）",BigDecimal.ZERO);
      } else if (v.age()>=60) { questions.add("老人免票需核对所在地及有效凭证，请向官方确认"); }
      else if (v.age()<=18 || Boolean.TRUE.equals(v.student())) { key="half"; label="半价门票（需资格凭证）"; }
      else if (v.student()==null || v.heightCm()==null) { questions.add("请补充游客"+index+"身高与学生资格，或向官方确认其他优惠"); }
      else { key="regular"; label="普通门票（其他特殊优惠另核）"; }
    }
    JsonNode price = key == null ? null : prices.get(key);
    return new Match(index,label, price != null && price.isNumber() ? price.decimalValue() : null);
  }
  private void checkHours(TicketCatalog.Site site, Needs n, List<String> notices) {
    JsonNode hours=site.value("hours");
    if (hours==null || n.date()==null) { notices.add("开放／入园时间尚不能完整判断"); return; }
    var fact=site.fields().get("hours");
    if (!dateInRange(n.date(),fact)) { return; }
    if (hours.has("closedWeekday") && n.date().getDayOfWeek().getValue()==hours.get("closedWeekday").asInt()) {
      notices.add("所选日期为常规闭馆日，请核对临时公告并调整行程");
    }
    String season = site.kind().equals("BADALING") ? (n.date().getMonthValue()>=4 && n.date().getMonthValue()<=10?"peak":"offPeak") : "regular";
    JsonNode range=hours.path(season);
    if (range.isArray() && n.startTime()!=null) {
      if (n.startTime().isBefore(LocalTime.parse(range.get(0).asText()))
          || !n.startTime().isBefore(LocalTime.parse(range.get(1).asText()))) {
        notices.add("计划到达时间不在已核验入园时段内");
      }
      if (n.endTime()!=null && n.endTime().isAfter(LocalTime.parse(range.get(2).asText()))) {
        notices.add("计划结束时间晚于闭园时间");
      }
    }
  }
}
