package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.application.agent.orchestration.AgentEnvelope;
import com.travelassistant.server.application.agent.orchestration.AgentOrchestrator;
import com.travelassistant.server.application.agent.orchestration.DispatchOutcome;
import com.travelassistant.server.application.agent.orchestration.DispatchRequest;
import com.travelassistant.server.application.agent.vote.JudgeProviderConfiguration;
import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.application.place.RatingOrder;
import com.travelassistant.server.application.place.ReviewLinks;
import com.travelassistant.server.application.ticket.TicketService;
import com.travelassistant.server.application.map.MapTools;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.ticket.TicketChannel;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对话 Agent 能调用的工具。
 *
 * <p>设计原则：<b>工具是唯一的事实来源</b>。模型只负责「决定查什么、怎么讲」，
 * 数字、地名、票价、餐馆全部来自这里，回给模型的也是这些结果的紧凑摘要。
 * 这样即使模型想编，也编不出一个不在知识库里的景点名。
 *
 * <p>每个工具同时返回两样东西：
 * <ul>
 *   <li>{@code summary}：给模型看的纯文本（截断到 1.2k 字符，避免把上下文撑爆）；</li>
 *   <li>{@code card}：给前端渲染的结构化数据。行程与美食直接复用 L1 编排的信封形状，
 *       前端已有的草案/餐馆渲染器不用改一行。</li>
 * </ul>
 */
@Component
public class ChatTools {
  private static final Logger log = LoggerFactory.getLogger(ChatTools.class);

  /** 回给模型的摘要上限：一个工具的结果不该比提问本身长十倍。 */
  private static final int MAX_SUMMARY = 1200;
  private static final int MAX_PLACES = 8;
  private static final int MAX_FOOD = 8;

  /** 一次工具执行的结果。{@code card} 为 null 表示这次没有可渲染的卡片。 */
  public record Outcome(boolean ok, String summary, Card card) {
  }

  /** 前端要渲染的卡片：{@code type} 决定用哪个渲染器，{@code intent} 仅行程/美食信封才有。 */
  public record Card(String type, String intent, Object data) {
  }

  private final PlaceResolver places;
  private final AttractionStore store;
  private final AgentOrchestrator orchestrator;
  private final VoteOrchestrator votes;
  private final ObjectMapper mapper;
  private final MapTools maps;
  private final TicketService tickets;

  public ChatTools(PlaceResolver places, AttractionStore store,
      AgentOrchestrator orchestrator, VoteOrchestrator votes, ObjectMapper mapper,
      MapTools maps, TicketService tickets) {
    this.places = places;
    this.store = store;
    this.orchestrator = orchestrator;
    this.votes = votes;
    this.mapper = mapper;
    this.maps = maps;
    this.tickets = tickets;
  }

  /**
   * 给模型看的工具清单（OpenAI function calling 格式）。
   *
   * <p>描述写得像给人看的说明书：「什么时候该用」比「参数是什么」更能决定模型的选择。
   */
  public static final List<Map<String, Object>> SPECS = List.of(
      fn("resolve_place", "核实一个地名属于哪座城市、是不是具体景点。地名写法不确定、"
          + "或用户说的可能是景区名时先调它，不要自己猜归属。",
          Map.of("name", str("地名原文，例如「武当山」「婺源」")), List.of("name")),
      fn("list_attractions", "按城市列出景点，好评高的排前面，附真实评分、等级、票价原文与"
          + "人工核验过的官方购票入口；没有票价的明确标「未核验」。"
          + "问「有什么好玩的」「值不值得去」「门票多少」时用它。",
          Map.of("city", str("城市名，例如「武汉」"),
              "limit", Map.of("type", "integer", "description", "最多返回几条，默认 8，上限 12")),
          List.of("city")),
      fn("get_weather", "查某座城市未来几天的天气预报（高德预报，不是实况）。"
          + "问天气、气温、要不要带伞、穿什么时用它。",
          Map.of("city", str("城市名，例如「天津」"),
              "days", Map.of("type", "integer", "description", "查几天，默认 3，最多 4")),
          List.of("city")),
      fn("book_ticket", "按一句自然语言查购票信息：给出经核验的官方购票链接与购票方法、"
          + "规则匹配景区票种、指出还缺什么条件。"
          + "用户问「门票怎么买」「在哪买票」「带学生证多少钱」「预订要注意什么」时用它；"
          + "它只给入口、方法与缺口，不代下单、不查余票、不处理退款。",
          Map.of("message", str("用户的购票原话，尽量保留年龄、日期、人数等条件，例如"
              + "「两个成人一个1.2米儿童下周六去黄鹤楼」")), List.of("message")),
      fn("get_inspiration", "按地名给主题灵感候选，好评高的排前面，附真实评分、等级、"
          + "票价原文与官方购票入口；没有票价的只说「未核验」，不要补数字。"
          + "未收录的地名（如区县「黄梅」「婺源」）会自动改用地图实时检索，结果未经核验。"
          + "用户说「不知道去哪」「随便逛逛」「有什么好玩的推荐一下」时用它；"
          + "传用户提到的原始地名，不要先替换成地级市。",
          Map.of("city", str("地名，如「杭州」「黄梅」")), List.of("city")),
      fn("vote_plan", "让三位评委 Agent（票务/美食/停车视角）对行程方案做两轮投票："
          + "主 Agent 先汇总本轮所有工具查到的信息整理成一份逐日草案（含官方购票方式），"
          + "再生成多套侧重不同的候选，评委第二轮按草案内容评分计票选出胜者。"
          + "用户已经给了目的地和天数、想比较不同侧重（省钱/美食/少走路）怎么安排时用它。"
          + "先调过查景点、找餐馆、问票务的工具再来投票，汇总的信息才完整；"
          + "投票比较耗时，用户只是闲聊或问天气时不要调。",
          Map.of("city", str("目的地城市，例如「武汉」"),
              "days", Map.of("type", "integer", "description", "游玩天数"),
              "travelers", Map.of("type", "integer", "description", "同行总人数，含本人"),
              "budget", Map.of("type", "number", "description", "预算上限，单位元"),
              "preferences", Map.of("type", "array", "items", Map.of("type", "string"),
                  "description", "偏好，只能取：自然/人文/美食/亲子/摄影/购物/夜游/温泉/海岛/慢节奏")),
          List.of("city")),
      fn("find_food", "查某座城市真实的餐馆候选（按该城市一个景点的坐标找周边，含人均、评分、距离）。"
          + "问吃什么、找馆子、本地小吃时用它。",
          Map.of("city", str("城市名，例如「武汉」")), List.of("city")),
      fn("draft_itinerary", "按城市与天数生成逐日行程草案，含候选景点、步行段、预算估算与待补问题。"
          + "用户要行程、问怎么安排、说「玩几天」时用它。天数没给就先问用户，不要默认填 3。",
          Map.of("city", str("城市名，例如「武汉」"),
              "days", Map.of("type", "integer", "description", "游玩天数，1~30"),
              "travelers", Map.of("type", "integer", "description", "同行总人数，含本人"),
              "budget", Map.of("type", "number", "description", "预算上限，单位元"),
              "preferences", Map.of("type", "array", "items", Map.of("type", "string"),
                  "description", "偏好，只能取：自然/人文/美食/亲子/摄影/购物/夜游/温泉/海岛/慢节奏"),
              "departDate", str("出发日期 YYYY-MM-DD，不知道就省略")),
          List.of("city", "days")));

  /** 执行一次工具调用；任何异常都收敛成一句可读的失败摘要，不把堆栈丢给模型。 */
  public Outcome execute(String name, String rawArgs) {
    return execute(name, rawArgs, List.of());
  }

  /**
   * 带「本轮已汇集卡片」的工具调用。用户定案的信息流：所有子 Agent 的结果先全量交给
   * 主 Agent，由它整理草案再让评委投票，所以 vote_plan 会收到此前工具产生的全部卡片。
   * 卡片在工具层注入，模型看不到这个内部字段，也不需要改变 function calling 规格。
   */
  public Outcome execute(String name, String rawArgs, List<Card> collected) {
    JsonNode args = read(rawArgs);
    if ("vote_plan".equals(name) && collected != null && !collected.isEmpty()
        && args instanceof ObjectNode node) {
      node.set(JudgeProviderConfiguration.COLLECTED_CARDS_KEY,
          mapper.valueToTree(compactCards(collected)));
    }
    try {
      return switch (name == null ? "" : name) {
        case "resolve_place" -> resolvePlace(args);
        case "list_attractions" -> listAttractions(args);
        case "get_weather" -> weather(args);
        case "find_food" -> food(args);
        case "draft_itinerary" -> plan(args);
        case "book_ticket" -> ticket(args);
        case "get_inspiration" -> inspiration(args);
        case "vote_plan" -> votePlan(args);
        default -> new Outcome(false, "没有名为 " + name + " 的工具，请从给定工具里选择。", null);
      };
    } catch (RuntimeException e) {
      log.warn("工具 {} 执行失败：{}", name, e.getMessage());
      return new Outcome(false, "调用 " + name + " 失败：" + brief(e.getMessage()) + "。"
          + "可以换个参数再试，或直接告诉用户这次没查到。", null);
    }
  }

  private Outcome resolvePlace(JsonNode args) {
    String name = text(args, "name");
    if (name == null) {
      return new Outcome(false, "缺少 name 参数。", null);
    }
    PlaceResolution place = places.resolve(name, List.of(name));
    if (!place.resolved()) {
      String alts = place.alternatives().isEmpty() ? ""
          : "；相近城市：" + String.join("、", place.alternatives());
      return new Outcome(false, "没认出「" + name + "」属于哪座城市" + alts
          + "。不要让用户重说一遍——直接把「" + name + "」原名传给 get_inspiration 或"
          + " draft_itinerary，未收录的地名会自动实时检索本地景点。", null);
    }
    String summary = "「" + name + "」→ 城市 " + place.city()
        + (place.spot() == null || place.spot().isBlank() ? "" : "，景点 " + place.spot())
        + (place.note() == null || place.note().isBlank() ? "" : "（" + place.note() + "）");
    if ("county".equals(place.level()) && place.city() != null) {
      // 县级地名不在按地级市建的索引里：不提示的话模型会拿地级市排程，给的景点全在别的县区
      summary += "。排「" + name + "」本地的行程时把原名传给 draft_itinerary，不要换成上级市";
    } else if ("alias".equals(place.source()) && name.equals(place.city())) {
      // 著名景区在库里没有可核验条目，按原名实时检索；别拿归属城市去查库里的同名冷门点
      summary += "。排本地行程把原名「" + name + "」传给 draft_itinerary 或 get_inspiration";
    }
    return new Outcome(true, summary, null);
  }

  private Outcome listAttractions(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    int limit = Math.min(Math.max(number(args, "limit", MAX_PLACES), 1), 12);
    List<Attraction> found = store.findByCity(city);
    if (found.isEmpty()) {
      // 用户口径：先查知识库，没有就自动调地图工具，不要让用户换个说法再试一遍
      try {
        MapTools.ScenicResult live = maps.findScenicSpots(city, limit);
        List<Map<String, Object>> liveRows = new ArrayList<>();
        for (MapTools.ScenicSpot s : live.spots()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", s.name());
          row.put("type", s.type());
          row.put("rating", s.rating());
          row.put("commentCount", s.commentCount());
          row.put("address", s.address());
          row.put("district", s.district());
          row.put("tel", s.tel());
          row.put("city", s.district() != null && !s.district().isBlank() ? s.district() : city);
          row.put("channels", channelsFor(s.name()));
          // 评论正文地图 API 不返回：只透出官方 POI 详情页入口，卡片点击后跳过去看真实评论
          row.put("reviewUrl", s.reviewUrl());
          row.put("reviewSource", s.reviewSource());
          row.put("reviewViaSearch", false);
          liveRows.add(row);
        }
        if (!liveRows.isEmpty()) {
          return new Outcome(true, cut(city + " 的实时检索景点（按地图评分排序，无评分的排后）：\n"
                  + placeLines(liveRows)
                  + "\n候选未经核验：地图不提供票价与开放时间，未核验的不补数字；"
                  + "购票请到景区官方公众号/官网或现场窗口，本系统不代购。"),
              new Card("places", null, liveRows));
        }
      } catch (RuntimeException ignored) {
        // 实时检索不可用就走统一的查不到话术
      }
      return new Outcome(false, "没查到「" + city + "」的景点。不要编造景点名，如实告诉用户没查到。", null);
    }
    // 与灵感卡片同一口径：好评高的放前面，明显非景点的科教条目剔除
    List<Attraction> shown = RatingOrder.ranked(found, limit);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Attraction a : shown) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", a.name());
      row.put("level", a.level());
      row.put("rating", a.rating());
      row.put("ticket", a.ticket());
      row.put("ticketStatus", a.ticketStatus());
      row.put("openTime", a.openTime());
      row.put("city", a.city());
      row.put("channels", channelsFor(a.name()));
      // 知识库没有 POI id，给官方地图搜索页，让用户在地图里找到该景点再看评论
      row.put("reviewUrl", ReviewLinks.amapSearch(a.name(), a.city()));
      row.put("reviewSource", "高德地图搜索");
      row.put("reviewViaSearch", true);
      rows.add(row);
    }
    String summary = city + " 的景点（共 " + found.size() + " 条，按评分列出 "
        + rows.size() + " 条）：\n" + placeLines(rows)
        + "\n票价原文与开放时间可能有变化，以景区公告为准；没有票价的只说未核验，不补数字。";
    return new Outcome(true, cut(summary), new Card("places", null, rows));
  }

  /**
   * 给模型看的景点逐条文本：评分、票价原文、开放时间都来自数据，缺票价明确写「未核验」。
   * 票价原文可能很长（含适用人群与来源），这里只取第一句避免撑爆上下文；卡片里是全文。
   */
  private static String placeLines(List<Map<String, Object>> rows) {
    List<String> lines = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      StringBuilder line = new StringBuilder("- ").append(String.valueOf(row.getOrDefault("name", "")));
      Object rating = row.get("rating");
      if (rating != null && !String.valueOf(rating).isBlank()) {
        line.append("｜评分 ").append(rating);
      }
      Object comments = row.get("commentCount");
      if (comments != null && !String.valueOf(comments).isBlank()) {
        line.append("（").append(comments).append(" 条评价）");
      }
      String ticket = briefTicket(row.get("ticket"));
      line.append(ticket == null ? "｜门票未核验" : "｜门票 ").append(ticket == null ? "" : ticket);
      Object openTime = row.get("openTime");
      if (openTime != null && !String.valueOf(openTime).isBlank()) {
        line.append("｜开放 ").append(openTime);
      }
      Object level = row.get("level");
      if (level != null && !String.valueOf(level).isBlank()) {
        line.append("｜").append(level);
      }
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  private static String briefTicket(Object raw) {
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).strip();
    if (text.isEmpty()) {
      return null;
    }
    String head = text.split("[；;(（]")[0].strip();
    return head.length() > 40 ? head.substring(0, 40) + "…" : head;
  }

  /**
   * 只透出配置里人工核验过的官方购票入口。链接是配置的原文，不拼、不猜、不抓；
   * 没有配置就返回空列表，由前端明确告诉用户去景区官方渠道查询。
   */
  private List<Map<String, Object>> channelsFor(String name) {
    if (tickets == null || name == null || name.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> out = new ArrayList<>();
      for (TicketChannel channel : tickets.lookup(null, name, null).channels()) {
        if (!channel.usable()) {
          continue;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", channel.channelName());
        item.put("url", channel.officialUrl());
        item.put("note", channel.note() == null || channel.note().isBlank()
            ? "余票未核验，购票前请以官方页面显示为准" : channel.note());
        out.add(item);
      }
      return out;
    } catch (RuntimeException e) {
      log.debug("官方购票入口查询失败：{}", e.getMessage());
      return List.of();
    }
  }

  /**
   * 天气走编排层的 {@code builtin.weather} 子 Agent：与其余能力同一入口、同一契约校验。
   * fact value 与 {@code WeatherResult} 的 JSON 字段一致，前端天气渲染器零改动。
   */
  private Outcome weather(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    Integer days = number(args, "days", 0);
    if (days > 0) {
      context.put("days", Math.min(days, 4));
    }
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("WEATHER_ADVICE", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "WEATHER");
    if (value == null) {
      String why = outcome.result() == null || outcome.result().notices().isEmpty()
          ? "编排层没有给出原因" : outcome.result().notices().get(0);
      return new Outcome(false, city + " 的天气没查到：" + why
          + "。不要编造天气，可以如实告诉用户这次没查到。", null);
    }
    StringBuilder sb = new StringBuilder(value.get("city") + " 天气预报（发布时间 "
        + value.get("reportTime") + "，是预报不是实况）：\n");
    Object rawDays = value.get("days");
    int shown = 0;
    if (rawDays instanceof List<?> list) {
      int limit = days > 0 ? Math.min(days, 4) : list.size();
      for (Object item : list) {
        if (shown >= limit) {
          break;
        }
        if (item instanceof Map<?, ?> d) {
          sb.append("- ").append(d.get("date"))
              .append("｜白天 ").append(d.get("dayWeather")).append(' ').append(d.get("dayTemp")).append("℃")
              .append("｜夜间 ").append(d.get("nightWeather")).append(' ').append(d.get("nightTemp")).append("℃")
              .append("｜").append(d.get("dayWind")).append(' ').append(d.get("dayPower")).append(" 级\n");
          shown++;
        }
      }
    }
    return new Outcome(true, cut(sb.toString()),
        new Card("weather", null, withoutCategory(value)));
  }

  private Outcome food(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    String anchorId = anchorOf(city);
    if (anchorId == null) {
      return new Outcome(false, "「" + city + "」的知识库里没有带坐标的景点，附近餐馆无法定位。"
          + "不要编餐厅，如实说明查不到即可。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    context.put("attractionIds", List.of(anchorId));
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("FOOD_RECOMMEND", context, Map.of()));
    return envelopeOutcome(outcome, "FOOD_RECOMMEND", namesOf(outcome, "FOOD", "name"));
  }

  private Outcome plan(JsonNode args) {
    String city = text(args, "city");
    Integer days = number(args, "days", 0) > 0 ? number(args, "days", 0) : null;
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    if (days == null) {
      return new Outcome(false, "缺少 days 参数。先问用户玩几天，不要默认成 3 天。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("destination", city);
    context.put("days", days);
    Integer travelers = number(args, "travelers", 0) > 0 ? number(args, "travelers", 0) : null;
    if (travelers != null) {
      context.put("travelers", travelers);
    }
    double budget = decimal(args, "budget", 0);
    if (budget > 0) {
      context.put("budget", budget);
    }
    String depart = text(args, "departDate");
    if (depart != null) {
      context.put("departDate", depart);
    }
    List<String> prefs = strings(args, "preferences");
    if (!prefs.isEmpty()) {
      context.put("preferences", prefs);
    }
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("PLAN_DRAFT", context, Map.of()));
    return envelopeOutcome(outcome, "PLAN_DRAFT", planHighlight(outcome));
  }

  /**
   * 草案摘要必须带逐日安排：只给目的地的话模型会以为「工具没排出来」，
   * 转而凭记忆补景点、或回一句「只返回了空壳」。逐日条目全在 fact 里，摘要摊开即可。
   * 编排层已把空天收敛掉，这里还要明确告诉模型「没排的天不要补」，否则模型会凭常识
   * 把 D3~D10 又填回用户面前。
   */
  private String planHighlight(DispatchOutcome outcome) {
    for (Object value : factsOf(outcome, "PLAN_DRAFT")) {
      if (!(value instanceof Map<?, ?> m)) {
        continue;
      }
      StringBuilder sb = new StringBuilder(String.valueOf(
          m.get("destination") == null ? "目的地" : m.get("destination")));
      if (m.get("dailySchedules") instanceof List<?> days) {
        for (Object day : days) {
          if (!(day instanceof Map<?, ?> d)) {
            continue;
          }
          List<String> names = new ArrayList<>();
          if (d.get("items") instanceof List<?> items) {
            for (Object item : items) {
              if (item instanceof Map<?, ?> im && im.get("name") != null) {
                names.add(String.valueOf(im.get("name")));
              }
            }
          }
          sb.append("；D").append(d.get("dayIndex") == null ? "?" : d.get("dayIndex"))
              .append(names.isEmpty() ? " 暂无候选景点" : " " + String.join("、", names));
        }
      }
      if (m.get("candidates") instanceof List<?> candidates) {
        sb.append("；候选共 ").append(candidates.size()).append(" 个");
      }
      if (m.get("dayScope") instanceof Map<?, ?> scope
          && scope.get("trimmedDays") instanceof Number trimmed && trimmed.intValue() > 0) {
        sb.append("；另有 ").append(trimmed.intValue())
            .append(" 天没有可查景点，草案未硬凑空天，不要替用户补景点或天数");
      }
      return sb.toString();
    }
    return "无";
  }

  /**
   * 票务子 Agent：一句话原话直接交给 {@code builtin.ticket}。缺什么条件由 Provider 的
   * questions 说清，工具摘要原样转述，不替用户猜年龄与学生资格。
   *
   * <p>注意 {@code builtin.ticket} 在 questions 非空时按契约返回 DEGRADED（advice 为空），
   * 但 fact 与 notices 里的购票规则仍然有效——工具层有结构化计数就视为可用，
   * 并把规则提示带给模型，否则「旺季、学生优惠」这些真实信息就被状态码埋没了。
   */
  private Outcome ticket(JsonNode args) {
    String message = text(args, "message");
    if (message == null) {
      return new Outcome(false, "缺少 message 参数。把用户的购票原话传过来。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("message", message);
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("TICKET_ADVICE", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "TICKET");
    if (value == null) {
      return new Outcome(false, "购票建议没拿到：" + firstNotice(outcome) + "。如实告诉用户，不要编票价。", null);
    }
    Map<String, Object> guidance = firstFactOf(outcome,
        JudgeProviderConfiguration.TICKET_GUIDANCE_CATEGORY);
    StringBuilder sb = new StringBuilder("购票建议（provider=" + outcome.providerId() + "）：");
    if (value.get("matchCount") instanceof Number n) {
      sb.append("匹配到 ").append(n.intValue()).append(" 条票务规则；");
    }
    if (value.get("stockVerified") instanceof Boolean b) {
      sb.append("库存").append(b ? "已核验" : "未核验").append("；");
    }
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    List<String> rules = notices.stream()
        .filter(s -> s != null && !s.contains("票价明细与渠道")).limit(4).toList();
    if (!rules.isEmpty()) {
      sb.append("规则提示：").append(String.join("；", rules)).append("。");
    }
    if (value.get("questionCount") instanceof Number q && q.intValue() > 0) {
      sb.append("还有 ").append(q.intValue()).append(" 个条件要问用户（按上面缺的问题问），"
          + "不要猜年龄或资格；票价明细与渠道要用既有购票接口。");
    } else {
      sb.append("条件齐了。票价明细与渠道要用既有购票接口，本工具只给建议。");
    }
    // 票务子 Agent 只给官方入口与方法：把链接说清楚，同时明确不代购、余票未核验。
    if (guidance != null
        && guidance.get("channels") instanceof List<?> channels && !channels.isEmpty()) {
      sb.append("官方购票入口：");
      for (Object raw : channels.stream().limit(3).toList()) {
        if (raw instanceof Map<?, ?> channel) {
          sb.append(channel.get("name")).append(" ").append(channel.get("url")).append("；");
        }
      }
      sb.append("方法：").append(guidance.get("method"))
          .append("余票未核验，本系统不代下单，也不生成猜测链接。");
    } else {
      sb.append("没有经核验的官方购票入口，请让用户自行进入景区官方网站或官方公众号，"
          + "不要编造网址，也不要说「已经买好」。");
    }
    boolean ok = "OK".equals(outcome.status()) || "DEGRADED".equals(outcome.status());
    Map<String, Object> envelope = new LinkedHashMap<>(AgentEnvelope.view(outcome, orchestrator));
    if (guidance != null) {
      envelope.put("ticketGuidance", guidance);
    }
    return new Outcome(ok, cut(sb.toString()), new Card("envelope", "TICKET_ADVICE", envelope));
  }

  /** 灵感子 Agent：{@code builtin.inspiration} 按知识库评分挑主题候选。 */
  private Outcome inspiration(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    DispatchOutcome outcome = orchestrator.dispatch(
        new DispatchRequest("CONTENT_INSPIRATION", context, Map.of()));
    Map<String, Object> value = firstFactOf(outcome, "INSPIRATION");
    if (value == null) {
      return new Outcome(false, city + " 的灵感候选没拿到：" + firstNotice(outcome)
          + "。如实说明没查到即可，不要编造。", null);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    if (value.get("items") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", m.get("name"));
          row.put("level", m.get("level"));
          row.put("rating", m.get("rating"));
          row.put("ticket", m.get("ticket"));
          row.put("ticketStatus", m.get("ticketStatus"));
          row.put("openTime", m.get("openTime"));
          row.put("city", m.get("city"));
          row.put("reason", m.get("reason"));
          // 官方入口来自人工核验配置：有就给链接，没有就由卡片说明查询方法
          Object name = m.get("name");
          row.put("channels", name == null ? List.of() : channelsFor(String.valueOf(name)));
          // 实时检索候选才有这些字段（等级票价地图拿不到，是 null 不是 0）
          for (String field : new String[] {"type", "address", "district", "tel",
              "source", "commentCount", "reviewUrl", "reviewSource", "reviewViaSearch"}) {
            if (m.get(field) != null) {
              row.put(field, m.get(field));
            }
          }
          rows.add(row);
        }
      }
    }
    Object total = value.get("total");
    String summary;
    if ("LIVE_MAP".equals(value.get("source"))) {
      summary = city + " 的实时检索景点（按地图评分排序，无评分的排后）：\n" + placeLines(rows)
          + "\n候选未经核验：地图不提供等级、票价与开放时间，未核验的不补数字；"
          + "购票请到景区官方公众号/官网或现场窗口，本系统不代购。";
    } else {
      summary = city + " 的灵感候选（共 " + total + " 条，按评分挑了 " + rows.size()
          + " 条）：\n" + placeLines(rows)
          + "\n票价原文与开放时间可能有变化，以景区公告为准；没有票价的只说未核验。";
    }
    return new Outcome(true, cut(summary), new Card("places", null, rows));
  }

  /**
   * 投票编排：主 Agent 把行程输入交给 {@link VoteOrchestrator}，三位评委 Agent 两轮投票。
   * 这是「多 Agent 协作」的直接入口——模型不需要知道评委是谁，只看到谁胜出、为什么。
   */
  private Outcome votePlan(JsonNode args) {
    String city = text(args, "city");
    if (city == null) {
      return new Outcome(false, "缺少 city 参数。", null);
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("city", city);
    Integer days = number(args, "days", 0) > 0 ? number(args, "days", 0) : null;
    if (days != null) {
      context.put("days", days);
    }
    Integer travelers = number(args, "travelers", 0) > 0 ? number(args, "travelers", 0) : null;
    if (travelers != null) {
      context.put("travelers", travelers);
    }
    double budget = decimal(args, "budget", 0);
    if (budget > 0) {
      context.put("budget", budget);
      // 评委第一轮的公开事实用的是 budgetCny 字段（字符串）
      context.put("budgetCny", java.math.BigDecimal.valueOf(budget)
          .stripTrailingZeros().toPlainString() + " 元");
    }
    List<String> prefs = strings(args, "preferences");
    if (!prefs.isEmpty()) {
      context.put("preferences", prefs);
    }
    if (args.has(JudgeProviderConfiguration.COLLECTED_CARDS_KEY)) {
      context.put(JudgeProviderConfiguration.COLLECTED_CARDS_KEY,
          mapper.convertValue(args.get(JudgeProviderConfiguration.COLLECTED_CARDS_KEY),
              List.class));
    }
    VoteOrchestrator.VoteRun run = votes.vote(
        new VoteOrchestrator.VoteRequest("DECISION_ADVICE", context, Map.of(), List.of()));
    boolean ok = "OK".equals(run.status());
    StringBuilder sb = new StringBuilder("两轮投票完成（评委：" + String.join("、", run.judges())
        + "，候选来自 " + run.candidateSource() + "）：");
    PlanVoteTally.VoteOutcome tally = run.outcome();
    if (tally != null && tally.winner() != null) {
      sb.append("胜出方案 ").append(tally.winner());
      if (tally.ranking() != null && !tally.ranking().isEmpty()) {
        PlanVoteTally.Ranking top = tally.ranking().get(0);
        sb.append("（加权 ").append(top.score()).append("，").append(top.voterCount())
            .append(" 位评委有效票）");
      }
    } else {
      sb.append("没有产生可推荐方案——").append(String.join("；", run.notices()));
    }
    Map<String, Object> draft = run.draft() == null ? Map.of() : run.draft();
    if (!draft.isEmpty()) {
      sb.append("。主 Agent 汇总 ").append(run.collectedFactCount())
          .append(" 条子 Agent 事实整理出草案（").append(draft.get("destination"))
          .append(" ").append(draft.get("days")).append(" 天）：");
      appendDraftSummary(sb, draft);
    }
    // 投票没产生胜者时 winner 是 null：Map.of 不收 null 值，改用 LinkedHashMap
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("plans", run.plans() == null ? List.of() : run.plans());
    card.put("winner", tally == null ? null : tally.winner());
    card.put("ranking", tally == null ? List.of() : tally.ranking());
    card.put("notices", run.notices() == null ? List.of() : run.notices());
    card.put("draft", draft);
    card.put("collectedFactCount", run.collectedFactCount());
    return new Outcome(ok, cut(sb.toString()), new Card("vote", "DECISION_ADVICE", card));
  }

  /** 摘要里只带有内容的天与购票方式，收敛掉的天一句话交代，模型不必凭记忆补草案。 */
  private static void appendDraftSummary(StringBuilder sb, Map<String, Object> draft) {
    if (draft.get("dailySchedules") instanceof List<?> days) {
      for (Object raw : days) {
        if (!(raw instanceof Map<?, ?> day)
            || !(day.get("items") instanceof List<?> list) || list.isEmpty()) {
          continue;
        }
        String items = String.join("、", list.stream().limit(3).map(String::valueOf).toList());
        sb.append("D").append(day.get("day")).append(" ").append(items).append("；");
      }
    }
    if (draft.get("dayScope") instanceof Map<?, ?> scope
        && scope.get("trimmedDays") instanceof Number trimmed && trimmed.intValue() > 0) {
      sb.append("另有 ").append(trimmed.intValue())
          .append(" 天没有可查景点，未硬凑空天，不要补景点；");
    }
    if (draft.get("ticketGuidance") instanceof Map<?, ?> guidance
        && guidance.get("channels") instanceof List<?> channels && !channels.isEmpty()) {
      sb.append("购票方式：");
      for (Object raw : channels.stream().limit(2).toList()) {
        if (raw instanceof Map<?, ?> channel) {
          sb.append(channel.get("name")).append(" ").append(channel.get("url")).append("；");
        }
      }
      sb.append("只给官方入口与方法，不代下单，余票未核验。");
    }
  }

  private static String namesFromRows(List<Map<String, Object>> rows) {
    List<String> names = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object name = row.get("name");
      if (name != null && !String.valueOf(name).isBlank()) {
        names.add(String.valueOf(name));
      }
    }
    return names.isEmpty() ? "无" : String.join("、", names.stream().limit(MAX_FOOD).toList());
  }

  /** 取第一个声明了目标类别的 CONTENT fact value；没有返回 null。 */
  private Map<String, Object> firstFactOf(DispatchOutcome outcome, String category) {
    List<Object> facts = factsOf(outcome, category);
    return facts.isEmpty() ? null
        : (facts.get(0) instanceof Map<?, ?> map ? asMutable(map) : null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMutable(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  /** 天气卡片不需要内部类别标记，去掉再发给前端。 */
  private static Map<String, Object> withoutCategory(Map<String, Object> value) {
    Map<String, Object> out = new LinkedHashMap<>(value);
    out.remove("category");
    return out;
  }

  /**
   * 行程与美食都复用 L1 编排：同一个 Provider、同一套契约校验、同一种信封。
   * 这样对话 Agent 拿到的结论与直接调 {@code /api/agent/invoke} 完全一致，
   * 不会出现「对话里说 A、接口说 B」。
   */
  private Outcome envelopeOutcome(DispatchOutcome outcome, String intent, String highlight) {
    Map<String, Object> envelope = AgentEnvelope.view(outcome, orchestrator);
    boolean ok = "OK".equals(outcome.status()) || "DEGRADED".equals(outcome.status());
    String summary = ok
        ? intent + " 结果（provider=" + outcome.providerId() + "）：" + highlight
            + summaryTail(outcome)
        : intent + " 没有拿到结果：" + firstNotice(outcome);
    return new Outcome(ok, cut(summary), new Card("envelope", intent, envelope));
  }

  /** 从 fact 里捧出人可读的一小段：景点名、餐馆名或草案的每日安排。 */
  private String namesOf(DispatchOutcome outcome, String category, String field) {
    List<String> names = new ArrayList<>();
    for (Object value : factsOf(outcome, category)) {
      if (value instanceof Map<?, ?> map) {
        Object v = map.get(field);
        if (v != null && !String.valueOf(v).isBlank()) {
          names.add(String.valueOf(v));
        }
      }
    }
    return names.isEmpty() ? "无" : String.join("、", names.stream().limit(MAX_FOOD).toList());
  }

  private String summaryTail(DispatchOutcome outcome) {
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    return notices.isEmpty() ? "" : "；提示：" + String.join("；", notices.stream().limit(2).toList());
  }

  private String firstNotice(DispatchOutcome outcome) {
    List<String> notices = outcome.result() == null ? List.of() : outcome.result().notices();
    return notices.isEmpty() ? "编排层没有给出原因" : notices.get(0);
  }

  private List<Object> factsOf(DispatchOutcome outcome, String category) {
    List<Object> out = new ArrayList<>();
    if (outcome == null || outcome.result() == null || outcome.result().facts() == null) {
      return out;
    }
    outcome.result().facts().forEach(fact -> {
      Object value = fact.value();
      if (value instanceof Map<?, ?> map && category.equals(String.valueOf(map.get("category")))) {
        out.add(value);
      }
    });
    return out;
  }

  /** 取该城市第一个带坐标系的景点当锚点；没有就不查，绝不按城市中心兜底。 */
  private String anchorOf(String city) {
    for (Attraction a : store.findByCity(city)) {
      if (a.lat() != null && a.lon() != null && a.coordinateSystem() != null
          && !a.coordinateSystem().isBlank()) {
        return a.id();
      }
    }
    return null;
  }

  private JsonNode read(String raw) {
    if (raw == null || raw.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      JsonNode node = mapper.readTree(raw);
      return node == null || node.isNull() ? mapper.createObjectNode() : node;
    } catch (Exception e) {
      // 模型偶尔给一段不合法 JSON：当作没给参数，由各工具自己报缺参
      return mapper.createObjectNode();
    }
  }

  private static String text(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    String s = node.asText("").strip();
    return s.isEmpty() ? null : s;
  }

  private static int number(JsonNode args, String field, int fallback) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (node.isNumber()) {
      return node.asInt();
    }
    try {
      return Integer.parseInt(node.asText("").strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double decimal(JsonNode args, String field, double fallback) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (node.isNumber()) {
      return node.asDouble();
    }
    try {
      return Double.parseDouble(node.asText("").strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static List<String> strings(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(item -> {
        String s = item.asText("").strip();
        if (!s.isEmpty()) {
          out.add(s);
        }
      });
    } else {
      String s = node.asText("").strip();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }

  /** 卡片全量转给主 Agent，但单轮设上限，避免把整份对话塞进投票上下文。 */
  private static List<Map<String, Object>> compactCards(List<Card> cards) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Card card : cards) {
      if (out.size() >= 12) {
        break;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", card.type());
      row.put("intent", card.intent());
      row.put("data", compactCardData(card.data()));
      out.add(row);
    }
    return out;
  }

  private static Object compactCardData(Object data) {
    if (data instanceof List<?> list && list.size() > 12) {
      return new ArrayList<>(list.subList(0, 12));
    }
    if (data instanceof Map<?, ?> map && map.size() > 20) {
      Map<String, Object> cut = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (cut.size() >= 20) {
          break;
        }
        if (entry.getKey() instanceof String key) {
          cut.put(key, entry.getValue());
        }
      }
      return cut;
    }
    return data;
  }

  private static String cut(String text) {
    return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY) + "…" : text;
  }

  private String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 120 ? message.substring(0, 120) + "…" : message;
  }

  private static Map<String, Object> fn(String name, String description,
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("type", "object");
    parameters.put("properties", properties);
    parameters.put("required", required);
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", name);
    function.put("description", description);
    function.put("parameters", parameters);
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("type", "function");
    spec.put("function", function);
    return spec;
  }

  private static Map<String, Object> str(String description) {
    return Map.of("type", "string", "description", description);
  }
}
