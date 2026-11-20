package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.RouteResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Single response model for the guide, transport and group cost ledger. Never a booking guarantee. */
public record TravelGuide(List<Day> days, List<Cost> costs, BigDecimal knownSubtotalCny,
    BigDecimal referenceSubtotalCny, BigDecimal perPersonReferenceCny, BigDecimal perDayReferenceCny,
    int unknownCostCount, String budgetVerdict, List<String> notices,
    List<QueryIssue> queryIssues, QueryUsage queryUsage) {
  public record QueryIssue(int dayIndex, String kind, String reason, String target) {}
  public record QueryUsage(int routeLimit, int routeCalls, int foodLimit, int foodCalls, int geocodeLimit, int geocodeCalls, long durationLimitMillis, long elapsedMillis) {}

  public record Day(int dayIndex, LocalDate date, List<Slot> slots, List<String> checks,
      String routeReason) {
    public Day(int dayIndex, LocalDate date, List<Slot> slots, List<String> checks) {
      this(dayIndex, date, slots, checks, null);
    }
  }
  public record Slot(String id, String period, String kind, String title, String status,
      List<FoodPlace> restaurants, RouteResult route, String note) {}
  public record Cost(String itemId, String category, String description, BigDecimal unitPriceCny,
      Integer quantity, String unit, BigDecimal groupAmountCny, String priceType,
      String source, String informationDate) {}

  public String render() {
    StringBuilder out=new StringBuilder("旅行攻略草案\n时间为规划假设；未确定的衔接只列顺序，出发前请核对开放与预约。");
    java.util.LinkedHashSet<String> evidence=new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> checks=new java.util.LinkedHashSet<>();
    for (Day day : days) {
      out.append("\n\n第").append(day.dayIndex()).append("天").append(day.date()==null?"（日期待定）":" · "+day.date());
      if(day.routeReason()!=null && !day.routeReason().isBlank()) out.append("\n路线编排依据：").append(day.routeReason());
      for(Slot slot:day.slots()) {
        out.append("\n").append(slot.period()).append(" ").append(slot.title());
        switch(slot.kind()) {
          case "VISIT" -> {
            String opening=between(slot.note(),"；开放：","；来源：");
            if(opening!=null) out.append(" · 开放参考：").append(opening);
            String selection=between(slot.note(),"；筛选依据=","；未知项=");
            if(selection!=null) out.append(" · 候选筛选依据：").append(selection);
            out.append(slot.status().equals("TIME_UNVERIFIED")?"（到达或开放条件尚未验证）":"");
          }
          case "ALTERNATIVE" -> out.append("（未排入，不计门票；可换日重排）");
          case "MEAL" -> {
            if(slot.restaurants().isEmpty()) out.append(" · 尚无匹配餐馆");
            else {
              out.append(slot.status().equals("HOURS_COVER_PLAN")?" · 营业原文覆盖此餐时段，仍需确认供餐/订位":" · 以下为可定位备选，供餐时段待确认");
              for(FoodPlace food:slot.restaurants()) {
                out.append("\n  ").append(food.name()).append(" · ").append(food.address()==null?"地址未返回；地图坐标 "+food.lon()+","+food.lat():food.address())
                    .append(" · 人均参考 ").append(food.pricePerPersonCny()==null?"未知":food.pricePerPersonCny()+"元");
                if(food.openHours()!=null) out.append(" · 营业原文 ").append(food.openHours());
                evidence.add(food.source()+"餐馆信息，查询于 "+food.fetchedAt()+"；人均非商家结算报价");
              }
            }
            if(slot.note().contains("不覆盖实际餐时")) checks.add("第"+day.dayIndex()+"天"+slot.title()+"：部分餐馆的营业原文不覆盖实际餐时，已移出本餐和费用");
          }
          case "TRANSPORT" -> {
            if(slot.route()==null) out.append(" · 路线耗时未确定，后续只作顺序参考");
            else {
              out.append(" · ").append(slot.route().mode().label()).append("约").append(slot.route().durationText()).append("，").append(slot.route().distanceText());
              if(slot.route().walkingDistanceMeters()!=null) out.append("，其中步行约").append(slot.route().walkingDistanceMeters()).append("米");
              evidence.add(slot.route().source()+"路线估算，查询于 "+slot.route().queriedAt());
              if(slot.route().requestedDate()!=null) out.append("，地图查询过滤日期 ").append(slot.route().requestedDate()).append("（非余票验证）");
              if(slot.route().fare()!=null && slot.route().fare().taxiAlternativePerVehicleCny()!=null) out.append("；打车备选约").append(amount(slot.route().fare().taxiAlternativePerVehicleCny())).append("元/车次，不计入当前方案费用");
              for(var rail:slot.route().railways()) {
                out.append("；地图铁路参考 ").append(rail.trip()).append(" ").append(rail.departureStation()).append(" ").append(rail.departureTime()).append(" → ").append(rail.arrivalStation()).append(" ").append(rail.arrivalTime()).append("（日期、售票和余票未核验）");
                for(var seat:rail.seats()) if(seat.referencePriceCny()!=null) out.append("，席别代码").append(seat.code()).append("参考").append(amount(seat.referencePriceCny())).append("元/人，作为备选不重复累计");
              }
            }
            if(slot.note().contains("停车候选：")) out.append("；").append(slot.note().substring(slot.note().indexOf("停车候选：")));
          }
          case "REST" -> out.append(" · 休息、用餐后缓冲与机动");
          case "GAP" -> out.append(" · 活动仍待补充，保留休息或自行指定安排");
          default -> { }
        }
      }
      checks.addAll(day.checks());
    }
    out.append("\n\n费用明细（人民币，全团口径；未知项另计）");
    int index=0;
    for(Cost cost:costs) {
      String day=cost.itemId().matches("D[0-9]+-.*")?"第"+cost.itemId().substring(1,cost.itemId().indexOf('-'))+"天 ":"";
      out.append("\n").append(++index).append(". ").append(day).append(cost.description()).append("：")
          .append(cost.groupAmountCny()==null?"金额未知":amount(cost.groupAmountCny())+"元")
          .append("（").append(priceLabel(cost.priceType())).append("）");
      if(cost.unitPriceCny()!=null) out.append("；").append(amount(cost.unitPriceCny())).append("元 × ")
          .append(cost.quantity()==null?"数量待确认":cost.quantity()).append(" ").append(cost.unit());
      if(cost.source()!=null && !cost.source().isBlank() && !cost.source().startsWith("未取得")) evidence.add(cost.source()+"；信息日期 "+(cost.informationDate()==null?"未返回/未核验":cost.informationDate()));
    }
    out.append("\n你确认的费用小计：").append(amount(knownSubtotalCny)).append("元；含参考价格的小计：").append(amount(referenceSubtotalCny))
        .append("元，另有").append(unknownCostCount).append("项费用未覆盖。")
        .append("\n人均参考小计：").append(perPersonReferenceCny==null?"人数待补":amount(perPersonReferenceCny)+"元")
        .append("；每日参考小计：").append(perDayReferenceCny==null?"天数待补":amount(perDayReferenceCny)+"元")
        .append("。\n预算判断：").append(budgetLabel(budgetVerdict));
    if(!checks.isEmpty()) {
      out.append("\n\n出发前需确认"); for(String check:checks) out.append("\n- ").append(check);
    }
    for(String notice:notices) {
      if(notice.contains("details.")||notice.contains("confirmedGroupCosts")) continue;
      if(notice.startsWith("费用键未匹配")) out.append("\n- 有一笔旧费用未匹配新安排，未计入；请重新确认它属于哪一天。");
      else out.append("\n- ").append(notice);
    }
    if(!queryIssues.isEmpty()) {
      out.append("\n\n尚未查齐的项目");
      for(QueryIssue issue:queryIssues) out.append("\n- ").append(issue.dayIndex()>0?"第"+issue.dayIndex()+"天 ":"全程 ").append(issue.target()).append("：").append(queryReason(issue.reason()));
      out.append("\n可直接说‘优先补查第5天的餐馆和交通’，重新生成时会把指定日期放到查询队列前面；不会把未查项目当作查无结果。");
    }
    if(!evidence.isEmpty()) {
      out.append("\n\n信息依据"); for(String item:evidence) out.append("\n- ").append(item);
    }
    out.append("\n\n可直接告诉我：‘改为3个人’、‘第一天午餐全团120元’、‘第二天少安排一个景点’或‘把酒店换成XX酒店’。调整后请再核对安排和费用。");
    return out.toString();
  }
  private static String queryReason(String reason) {
    return switch(reason) { case "BUDGET_EXHAUSTED" -> "本轮查询次数已用完，尚未查询，可指定这一天优先补查";
      case "REQUEST_FAILED" -> "接口查询失败，可重试"; case "NO_COORDINATES" -> "缺少已标注坐标系的坐标，尚未完成路线查询";
      case "DEADLINE_EXCEEDED" -> "本轮查询等待时间已到，未完成，可指定某天优先补查";
      case "QUERY_BUSY" -> "查询工作线程暂忙，本轮未查询，可重试";
      case "NOT_CONFIGURED" -> "地图接口未配置"; case "DIETARY_UNVERIFIED" -> "忌口/过敏尚未核实，未代选餐馆";
      default -> "本次查询未返回可用结果"; };
  }
  private static String between(String text,String from,String to) {
    int begin=text.lastIndexOf(from); if(begin<0) return null;
    int end=text.indexOf(to,begin+from.length()); return end<0?null:text.substring(begin+from.length(),end);
  }
  private static String amount(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
  private static String priceLabel(String type) {
    return switch(type) { case "USER_CONFIRMED" -> "你确认的金额"; case "REFERENCE" -> "来源参考价格，非实时报价";
      case "REALTIME" -> "实时报价"; default -> "待补充，未按零元计"; };
  }
  private static String budgetLabel(String verdict) {
    return switch(verdict) { case "EXCEEDS_LIMIT" -> "已确认费用已超过预算";
      case "REFERENCE_EXCEEDS_LIMIT" -> "含参考价格的小计已超过预算，尚有未知项未计入";
      case "WITHIN_CONFIRMED_SCOPE" -> "当前已确认费用未超预算，请核对是否覆盖全部支出";
      case "NO_BUDGET" -> "尚未填写预算上限"; case "CURRENCY_MISMATCH" -> "预算币种与人民币账本不同，暂不直接比较";
      default -> "仍有未知或参考费用，无法确认总花费是否超预算"; };
  }
}
