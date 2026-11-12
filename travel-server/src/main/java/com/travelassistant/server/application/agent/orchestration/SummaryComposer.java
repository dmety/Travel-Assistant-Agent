package com.travelassistant.server.application.agent.orchestration;

import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.ContractRules;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 Provider 产出的结构化 facts 归纳成一句人话，供 {@code advice} 使用。
 *
 * <p>这是「模型只做表达、不做事实」的落点，四条约束都在这里强制：
 * <ul>
 *   <li>输入只有 facts 的数值与标签，<b>不含店名</b>，从源头上不给模型复述和编造名称的机会；</li>
 *   <li>输出里出现的每个数字都必须能在 facts 中找到同一个数字，否则整条丢弃（S-05 口径）；</li>
 *   <li>未配置密钥、无 facts、调用失败、输出不合规，一律返回 {@code null}，由调用方保留
 *       Provider 原有的 advice，不阻塞主链路；</li>
 *   <li>不写库、不缓存、不进日志（只记失败原因分类，不记模型原文与密钥）。</li>
 * </ul>
 *
 * <p>边界：本类不参与候选生成、排序和过滤，也不改变 facts 条数与状态码。
 */
@Component
public class SummaryComposer {

  private static final Logger log = LoggerFactory.getLogger(SummaryComposer.class);

  /** 归纳句的上限。契约给 advice 的上限更宽，这里取更严的值，避免模型写成长段评论。 */
  private static final int MAX_CHARS = 120;

  private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

  private static final String SYSTEM = """
      你是旅行助手的结果归纳器。你只做归纳，不做推荐决策。
      规则：
      1. 只能依据用户消息里给出的候选特征归纳，不得新增任何事实、店名、品牌或评价。
      2. 不要出现任何具体商家名称。
      3. 不要出现数字，除非该数字在候选特征里原样出现；拿不准就用「多数」「少数」「步行几分钟」这类说法。
      4. 输出一到两句中文，不超过 80 字，不用列表、不用感叹号、不用营销词。
      5. 候选特征不足以下结论时，直说信息有限。但「只有一条路线」「只有一个预报」不算不足，
         那本身就是这次要归纳的对象，照实说清即可。
      6. 意图是 ROUTE_ADVICE 时，特征是两点之间的出行方式、距离米与耗时秒：
         说清「多远、大概多久、怎么去」就好，不要因为只有一条路线就说信息有限。
      7. 候选特征里标「未知」的项目，不得对它作任何评价、比较或推测（例如人均未知就不要说价格高低）。
      """;

  private final DeepSeekClient model;
  private final ContractRules rules;

  public SummaryComposer(DeepSeekClient model, ContractRules rules) {
    this.model = model;
    this.rules = rules;
  }

  /**
   * 生成归纳句。
   *
   * @return 通过数字校验的归纳句；任何不满足条件的情况返回 {@code null}
   */
  public String compose(String intent, AgentResult result) {
    if (!model.configured() || result == null) {
      return null;
    }
    List<Fact> facts = result.facts();
    if (facts == null || facts.isEmpty()) {
      return null;
    }
    String digest = digest(facts);
    if (digest.isBlank()) {
      return null;
    }
    String output;
    try {
      output = model.complete(SYSTEM, "意图：" + (intent == null ? "未标注" : intent)
          + "\n候选特征：\n" + digest);
    } catch (RuntimeException e) {
      // 模型不可用不影响主链路；只记分类，不记原文。
      log.debug("归纳句生成失败，已回退 Provider 原有 advice：{}", e.getClass().getSimpleName());
      return null;
    }
    return accept(output, facts);
  }

  /** 输出闸门：长度、数字可核对、非空。任一不过就整条丢弃。 */
  String accept(String output, List<Fact> facts) {
    if (output == null || output.isBlank()) {
      return null;
    }
    String text = output.strip().replace("\n", " ");
    int limit = rules.limit("maxAdviceChars") > 0
        ? Math.min(MAX_CHARS, rules.limit("maxAdviceChars")) : MAX_CHARS;
    if (text.length() > limit) {
      return null;
    }
    Set<String> allowed = allowedNumbers(facts);
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      if (!allowed.contains(normalizeNumber(matcher.group()))) {
        return null;
      }
    }
    return text;
  }

  /** facts 里出现过的全部数字，外加候选条数本身。 */
  private static Set<String> allowedNumbers(List<Fact> facts) {
    Set<String> allowed = new LinkedHashSet<>();
    allowed.add(String.valueOf(facts.size()));
    for (Fact fact : facts) {
      collect(fact.value(), allowed);
    }
    return allowed;
  }

  private static void collect(Object value, Set<String> allowed) {
    if (value instanceof Number n) {
      allowed.add(normalizeNumber(String.valueOf(n.doubleValue())));
      allowed.add(normalizeNumber(String.valueOf(n.longValue())));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      map.values().forEach(item -> collect(item, allowed));
      return;
    }
    if (value instanceof Iterable<?> items) {
      items.forEach(item -> collect(item, allowed));
    }
  }

  /** 42.0 与 42 视为同一个数字，避免模型把整数写成小数被误判。 */
  private static String normalizeNumber(String raw) {
    if (raw.contains(".")) {
      String trimmed = raw.replaceAll("0+$", "");
      if (trimmed.endsWith(".")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      return trimmed;
    }
    return raw;
  }

  /**
   * 只喂数值与标签，不喂店名。模型看不到名字，就不存在「复述错名字」这条失败路径。
   *
   * <p>字段按事实类别挑。原先只按餐饮字段取，路线、天气、灵感、草案的事实到了模型那里
   * 全是「未知」——七个字段里六个未知，模型只能按规则回一句「信息有限」，
   * 于是「问什么都回到信息有限」这个现象跟超时无关，是这段选字段的逻辑造成的。
   */
  private static String digest(List<Fact> facts) {
    List<String> lines = new ArrayList<>();
    int index = 0;
    for (Fact fact : facts) {
      index++;
      Map<?, ?> value = fact.value() instanceof Map<?, ?> map ? map : Map.of();
      String category = value.get("category") instanceof String s ? s : "";
      lines.add(index + ". " + String.join("；", partsOf(category, value)));
    }
    return String.join("\n", lines);
  }

  private static List<String> partsOf(String category, Map<?, ?> value) {
    if ("ROUTE".equals(category)) {
      return List.of(
          "出行方式 " + describe(value.get("modeLabel")),
          "距离米 " + describe(value.get("distanceMeters")),
          "耗时秒 " + describe(value.get("durationSeconds")),
          "途经路段数 " + countOf(value.get("steps")));
    }
    if ("WEATHER".equals(category)) {
      return List.of(
          "预报天数 " + countOf(value.get("days")),
          "白天气温 " + joinField(value.get("days"), "dayTemp"),
          "夜间气温 " + joinField(value.get("days"), "nightTemp"));
    }
    if ("INSPIRATION".equals(category)) {
      return List.of(
          "候选条数 " + countOf(value.get("items")),
          "该城市总数 " + describe(value.get("total")),
          "有票价的条数 " + countHasField(value.get("items"), "ticket"));
    }
    if ("PLAN_DRAFT".equals(category)) {
      return List.of(
          "逐日安排天数 " + countOf(value.get("dailySchedules")),
          "候选景点数 " + countOf(value.get("candidates")),
          "待补问题数 " + countOf(value.get("questions")),
          "提示条数 " + countOf(value.get("notices")));
    }
    // 餐饮与未知类别按原有口径
    return List.of(
        "距离 " + describe(value.get("distanceMeters")),
        "人均 " + describe(value.get("pricePerPersonCny")),
        "评分 " + describe(value.get("rating")),
        "标签 " + describe(value.get("tags")),
        "餐次 " + describe(value.get("mealSlot")),
        "限制 " + describe(value.get("constraintNotes")),
        "本地特色 " + describe(value.get("locality")));
  }

  private static String countOf(Object value) {
    if (value instanceof Iterable<?> items) {
      int n = 0;
      for (Object ignored : items) {
        n++;
      }
      return String.valueOf(n);
    }
    return "未知";
  }

  /** 取列表里某个字段的全部取值，逗号连接；只用于气温这类纯数值字段。 */
  private static String joinField(Object list, String field) {
    if (!(list instanceof Iterable<?> items)) {
      return "未知";
    }
    List<String> texts = new ArrayList<>();
    for (Object item : items) {
      if (item instanceof Map<?, ?> map) {
        Object raw = map.get(field);
        if (raw != null && !String.valueOf(raw).isBlank()) {
          texts.add(String.valueOf(raw));
        }
      }
    }
    return texts.isEmpty() ? "未知" : String.join("/", texts);
  }

  /** 列表里有多少条某一字段非空。灵感事实用它说明「有票价的占几条」。 */
  private static String countHasField(Object list, String field) {
    if (!(list instanceof Iterable<?> items)) {
      return "未知";
    }
    int n = 0;
    for (Object item : items) {
      if (item instanceof Map<?, ?> map) {
        Object raw = map.get(field);
        if (raw != null && !String.valueOf(raw).isBlank()) {
          n++;
        }
      }
    }
    return String.valueOf(n);
  }

  private static String describe(Object value) {
    if (value == null) {
      return "未知";
    }
    if (value instanceof Iterable<?> items) {
      List<String> texts = new ArrayList<>();
      items.forEach(item -> texts.add(String.valueOf(item)));
      return texts.isEmpty() ? "无" : String.join("/", texts);
    }
    if (value instanceof Number n) {
      return String.valueOf(n);
    }
    String text = String.valueOf(value).strip();
    return text.isEmpty() ? "未知" : text;
  }
}
