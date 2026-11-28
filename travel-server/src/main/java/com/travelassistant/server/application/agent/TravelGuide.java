package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.food.FoodPlace;
import com.travelassistant.server.domain.location.RouteResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Single response model for the guide, transport and group cost ledger. Never a booking guarantee. */
public record TravelGuide(List<Day> days, List<Cost> costs, BigDecimal knownSubtotalCny,
    BigDecimal referenceSubtotalCny, BigDecimal referenceSubtotalMaxCny,
    BigDecimal perPersonReferenceCny, BigDecimal perDayReferenceCny,
    int unknownCostCount, String budgetVerdict, List<String> notices,
    List<QueryIssue> queryIssues, QueryUsage queryUsage) {
  public TravelGuide(List<Day> days, List<Cost> costs, BigDecimal knownSubtotalCny,
      BigDecimal referenceSubtotalCny, BigDecimal perPersonReferenceCny,
      BigDecimal perDayReferenceCny, int unknownCostCount, String budgetVerdict,
      List<String> notices, List<QueryIssue> queryIssues, QueryUsage queryUsage) {
    this(days, costs, knownSubtotalCny, referenceSubtotalCny, referenceSubtotalCny,
        perPersonReferenceCny, perDayReferenceCny, unknownCostCount, budgetVerdict,
        notices, queryIssues, queryUsage);
  }
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
      String source, String informationDate, BigDecimal groupAmountMaxCny) {
    public Cost(String itemId, String category, String description, BigDecimal unitPriceCny,
        Integer quantity, String unit, BigDecimal groupAmountCny, String priceType,
        String source, String informationDate) {
      this(itemId, category, description, unitPriceCny, quantity, unit, groupAmountCny,
          priceType, source, informationDate, groupAmountCny);
    }
  }

  @com.fasterxml.jackson.annotation.JsonProperty("budgetSummary")
  public String budgetSummary() {
    java.util.Map<String, Long> counts = costs.stream().filter(c -> c.groupAmountCny() == null)
        .collect(java.util.stream.Collectors.groupingBy(c -> switch(c.category()) {
          case "HOTEL" -> "住宿"; case "TICKET" -> "门票"; case "TRANSPORT" -> "交通"; case "FOOD" -> "餐饮"; default -> "其他";
        }, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
    String missing = counts.entrySet().stream().map(e -> e.getKey() + e.getValue() + "项")
        .collect(java.util.stream.Collectors.joining("、"));
    if (costs.stream().anyMatch(c -> c.itemId().startsWith("intercity-") && c.groupAmountCny() == null)) missing += "（含往返跨城交通）";
    return (referenceSubtotalCny.signum()==0 && unknownCostCount>0 ? "尚无可计入的确认费用或参考报价；" : "已确认 " + amount(knownSubtotalCny) + "元；已覆盖部分（含已确认及参考）"
        + amount(referenceSubtotalCny) + "~" + amount(referenceSubtotalMaxCny) + "元；")
        + (unknownCostCount > 0 ? "未覆盖：" + missing + "。" : "")
        + "预算判断：" + budgetLabel(budgetVerdict) + "；未知费用不按零计算。";
  }

  public String render() {
    StringBuilder out=new StringBuilder("旅行攻略草案\n时间为规划假设；未确定的衔接只列顺序，出发前请核对开放与预约。");
    java.util.LinkedHashSet<String> evidence=new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> checks=new java.util.LinkedHashSet<>();
    java.util.Map<String,String> mealReferences=new java.util.LinkedHashMap<>();
    for (Day day : days) {
      out.append("\n\n第").append(day.dayIndex()).append("天").append(day.date()==null?"（日期待定）":" · "+day.date());

      for(Slot slot:day.slots()) {
        out.append("\n").append(slot.period()).append(" ").append(slot.title());
        switch(slot.kind()) {
          case "VISIT" -> {
            String opening=between(slot.note(),"；开放：","；来源：");
            if(opening!=null) evidence.add(slot.title()+"；开放原文="+opening);
            String selection=between(slot.note(),"；筛选依据=","；未知项=");
            if(selection!=null) out.append(" · 候选筛选依据：").append(selection);

          }
          case "ALTERNATIVE" -> out.append("（未排入，不计门票；可换日重排）");
          case "MEAL" -> {
            if(slot.note().contains("用餐位置=酒店附近")) out.append(" · 回酒店后在酒店附近用餐");
            if(slot.restaurants().isEmpty()) out.append(" · 尚无匹配餐馆");
            else {
              String names=slot.restaurants().stream().map(FoodPlace::name).collect(java.util.stream.Collectors.joining("、"));
              String key=slot.restaurants().stream().map(food -> food.poiId()+"|"+food.name()+"|"+food.address()+"|"+food.lon()+","+food.lat()).collect(java.util.stream.Collectors.joining(";"))
                  +(slot.note().contains("用餐位置=酒店附近")?"|酒店附近":"|景点或出发地附近");
              String reference=mealReferences.putIfAbsent(key,"第"+day.dayIndex()+"天"+slot.title());
              out.append(reference==null?" · 自选候选："+names:" · 自选候选同"+reference);

              for(FoodPlace food:slot.restaurants()) {

                evidence.add(food.name()+"；地址="+(food.address()==null?"未返回；地图坐标 "+food.lon()+","+food.lat():food.address())
                    +"；人均参考="+(food.pricePerPersonCny()==null?"未知":food.pricePerPersonCny()+"元")+"；营业原文="+(food.openHours()==null?"未知":food.openHours())+"；来源="+food.source()
                    +"，查询于 "+food.fetchedAt()+"；人均非商家结算报价");
              }
            }
            if(slot.note().contains("不覆盖实际餐时")) checks.add("第"+day.dayIndex()+"天"+slot.title()+"：部分餐馆的营业原文不覆盖实际餐时，已移出本餐和费用");
          }
          case "TRANSPORT" -> {
            if(slot.route()==null) out.append(" · ").append(slot.note());
            else {
              out.append(" · ").append(slot.title().contains("（打车）")?"打车路径参考":slot.route().mode().label()).append("约").append(slot.route().durationText()).append("，").append(slot.route().distanceText());
              if(slot.route().walkingDistanceMeters()!=null) out.append("，其中步行约").append(slot.route().walkingDistanceMeters()).append("米");
              evidence.add(slot.route().source()+"路线估算，查询于 "+slot.route().queriedAt());
              if(slot.route().requestedDate()!=null) out.append("，地图查询过滤日期 ").append(slot.route().requestedDate()).append("（非余票验证）");
              if(slot.route().fare()!=null && slot.route().fare().taxiAlternativePerVehicleCny()!=null) out.append("；打车备选约").append(amount(slot.route().fare().taxiAlternativePerVehicleCny())).append("元/车次，不计入当前方案费用");

            }
            if(slot.note().contains("停车候选：")) out.append("；").append(slot.note().substring(slot.note().indexOf("停车候选：")));
          }
          case "HOTEL_REST" -> out.append(" · ").append(slot.note());
          case "REST" -> out.append(" · 休息、用餐后缓冲与机动");
          case "GAP" -> out.append(" · 活动仍待补充，保留休息或自行指定安排");
          default -> { }
        }
      }
      checks.addAll(day.checks());
      if(day.routeReason()!=null && !day.routeReason().isBlank()) evidence.add("路线编排依据：" + day.routeReason());
    }
    out.append("\n\n预算覆盖：").append(budgetSummary());
    out.append("\n\n费用明细（人民币，全团口径；未知项另计）");
    int index=0;
    for(Cost cost:costs) {
      if(cost.groupAmountCny()==null) continue;
      String day=cost.itemId().matches("D[0-9]+-.*")?"第"+cost.itemId().substring(1,cost.itemId().indexOf('-'))+"天 ":"";
      out.append("\n").append(++index).append(". ").append(day).append(cost.description()).append("：")
          .append(cost.groupAmountCny()==null?"金额未知":amount(cost.groupAmountCny())
              + (cost.groupAmountMaxCny()!=null && cost.groupAmountMaxCny().compareTo(cost.groupAmountCny())>0
                  ? "~"+amount(cost.groupAmountMaxCny()) : "") + "元")
          .append("（").append(priceLabel(cost.priceType())).append("）");
      if(cost.unitPriceCny()!=null) {
        out.append("；").append(amount(cost.unitPriceCny()));
        if(cost.groupAmountMaxCny()!=null && cost.groupAmountCny()!=null
            && cost.groupAmountMaxCny().compareTo(cost.groupAmountCny())>0
            && cost.quantity()!=null && cost.quantity()>0) {
          out.append("~").append(amount(cost.groupAmountMaxCny()
              .divide(BigDecimal.valueOf(cost.quantity()), 2, java.math.RoundingMode.HALF_UP)));
        }
        out.append("元 × ").append(cost.quantity()==null?"数量待确认":cost.quantity())
            .append(" ").append(cost.unit());
      }
      if(cost.source()!=null && !cost.source().isBlank() && !cost.source().startsWith("未取得")) evidence.add(cost.source()+"；信息日期 "+(cost.informationDate()==null?"未返回/未核验":cost.informationDate()));
    }
    out.append("\n未知项按预算分类列出，未按零元计；餐馆候选未代表已选定或完成订位。");
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
      int exampleDay=queryIssues.stream().mapToInt(QueryIssue::dayIndex).filter(day -> day>=1 && day<=days.size()).findFirst().orElse(1);
      out.append("\n可直接说‘优先补查第").append(exampleDay).append("天的餐馆和交通’，仅补查本行程内的项目；不会把未查项目当作查无结果。");
    }
    if(!evidence.isEmpty()) {
      out.append("\n\n信息依据"); for(String item:evidence) out.append("\n- ").append(item);
    }
    out.append("\n\n可补充具体酒店地址、交通班次或已确认费用；调整后再核对安排和预算覆盖。");
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
      case "REFERENCE_RANGE" -> "有价候选的参考区间，未选餐馆，非实时报价";
      case "REALTIME" -> "实时报价"; default -> "待补充，未按零元计"; };
  }
  private static String budgetLabel(String verdict) {
    return switch(verdict) { case "EXCEEDS_LIMIT" -> "已确认费用已超过预算";
      case "REFERENCE_EXCEEDS_LIMIT" -> "有价参考费用下限已超过预算，且仍有未知费用";
      case "REFERENCE_RANGE_CROSSES_LIMIT" -> "参考价格区间跨过预算上限，且仍有未知费用，暂不能判断是否超支";
      case "WITHIN_CONFIRMED_SCOPE" -> "当前已确认费用未超预算，请核对是否覆盖全部支出";
      case "NO_BUDGET" -> "尚未填写预算上限"; case "CURRENCY_MISMATCH" -> "预算币种与人民币账本不同，暂不直接比较";
      default -> "仍有未知或参考费用，无法确认总花费是否超预算"; };
  }
}
