package com.travelassistant.server.application.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把用户的一句话解析成「意图 + 槽位 + 已核实的目的地」。
 *
 * <p>为什么要这一步：对话页原来用正则抠天数、人数、预算、偏好，再分三四次请求反查地名，
 * 稍微换个说法就掉链子（「玩四天」「预算六千」「下周六」「带爸妈」）。
 * 现在理解交给模型，<b>校验留在 Java</b>：
 * <ul>
 *   <li>模型只输出值，不决定可用性；天数、人数、预算、日期越界一律截断并写进 {@code notes}；</li>
 *   <li>偏好收敛到固定词表，避免把整句塞进 {@code preferences} 污染检索；</li>
 *   <li>目的地交给 {@link PlaceResolver} 核实（知识库 → 模型判断 → 相近城市），
 *       模型说的地方库里没有时不会当成确定事实；</li>
 *   <li>模型不可用、返回非 JSON、超时：一律降级到 {@link IntentRules}，接口永远 200。</li>
 * </ul>
 *
 * <p>结果按「日期 + 原话」缓存：相对日期当天有效，换天自动失效，不会把「明天」缓存成昨天。
 */
@Service
public class IntentParser {
  private static final Logger log = LoggerFactory.getLogger(IntentParser.class);

  /** 原话上限：再长就不是一句话了，截断也能防住恶意超长输入。 */
  private static final int MAX_TEXT = 300;
  private static final int MAX_DAYS = 30;
  private static final int MAX_TRAVELERS = 30;
  private static final BigDecimal MAX_BUDGET = new BigDecimal("1000000");
  private static final int MAX_PREFERENCES = 4;
  /** 目的地完全没线索时给几个城市按钮，避免让用户重打字。 */
  private static final int ALTERNATIVE_LIMIT = 6;
  private static final int CACHE_LIMIT = 256;
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  /** 模型对相对日期的容错窗口：只接受今天到一年内的日期。 */
  private static final int DATE_WINDOW_DAYS = 365;

  private static final List<String> PREFERENCE_VOCAB = List.of(
      "自然", "人文", "美食", "亲子", "摄影", "购物", "夜游", "温泉", "海岛", "慢节奏");

  /** 模型的多种说法收敛到词表，词表以外的偏好直接丢弃（宁缺勿滥）。 */
  private static final Map<String, String> PREFERENCE_ALIASES = aliases();

  private static final String SYSTEM_PROMPT = """
      你是旅行助手的语义解析器。把用户的一句话解析成结构化字段，只输出一行 JSON。
      规则：
      1. 只解析用户明确说过的信息，没提到就填 null 或 []，绝对不要补默认值（不要默认 3 天、2 人、5000 元）。
      2. destination 照抄用户原话里的地名，不要改写成省会或上级城市（「武当山」就填「武当山」）。
         提到多个地方时取最想去的那个；完全没提目的地就填 null。
      3. days 是游玩天数，「三天两晚」取 3；「玩几天」这种没给数字的填 null。
      4. travelers 是同行总人数（含本人）。「两个人」填 2，「一家四口」填 4，
         「带爸妈」这种不知道具体人数的填 null。
      5. budget 是预算上限，单位元：「六千」填 6000，「一万五」填 15000。
      6. departDate / returnDate 输出 YYYY-MM-DD，必须相对「今天」换算；
         只给了天数没给日期就填 null；日期必须落在今天到一年以内。
      7. preferences 只能从 [自然, 人文, 美食, 亲子, 摄影, 购物, 夜游, 温泉, 海岛, 慢节奏] 里选，最多 4 个；
         对不上就填 []。
      8. intent 只能是这三个之一：
         PLAN_DRAFT = 想去某地、要排行程、问某地有什么好玩的；
         FOOD_RECOMMEND = 主要问吃什么、找餐厅、本地小吃；
         CHAT = 打招呼、闲聊、感谢、与旅行无关。
      9. confidence 填 high / medium / low；reason 用一句话说明依据，不要客套。
      10. 不要输出 Markdown 代码块或解释段落，只输出 JSON，形如：
      {"intent":"PLAN_DRAFT","destination":"武当山","days":4,"travelers":null,"budget":6000,
       "departDate":"2026-11-21","returnDate":null,"preferences":["人文"],"confidence":"high",
       "reason":"下周六出发玩四天，预算六千"}
      """;

  private final DeepSeekClient model;
  private final PlaceResolver places;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Map<String, IntentResolution> cache = new ConcurrentHashMap<>();

  /**
   * 生产用构造器。两个构造器并存时必须显式标注 {@code @Autowired}，
   * 否则 Spring 会以为需要无参构造并启动失败（这是本项目踩过的坑）。
   */
  @org.springframework.beans.factory.annotation.Autowired
  public IntentParser(DeepSeekClient model, PlaceResolver places, ObjectMapper mapper) {
    this(model, places, mapper, Clock.system(ZONE));
  }

  /** 测试用构造器：传入 {@link Clock#fixed} 之类的受控时钟，避免「明天」随真实日期漂移。 */
  public IntentParser(DeepSeekClient model, PlaceResolver places, ObjectMapper mapper,
      Clock clock) {
    this.model = model;
    this.places = places;
    this.mapper = mapper;
    this.clock = clock;
  }

  /**
   * 解析一句话。
   *
   * @param text 用户原话
   * @return 永远非 {@code null}；解析不出目的地时 {@code resolved=false} 并带可点城市
   */
  public IntentResolution parse(String text) {
    String t = normalize(text);
    LocalDate today = LocalDate.now(clock);
    if (t.isEmpty()) {
      return empty();
    }
    // 相对日期按天失效，所以缓存键带上当天日期
    String key = today + "|" + t;
    IntentResolution cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    TravelIntent intent = fromModel(t, today);
    if (intent == null) {
      intent = IntentRules.parse(t, today);
    }
    IntentResolution resolution = complete(t, intent);
    remember(key, resolution);
    return resolution;
  }

  private IntentResolution empty() {
    return new IntentResolution("", TravelIntent.CHAT, null, false, null, null,
        "说说你想去哪儿、玩几天，我来排行程", List.of(), null, null, null,
        TravelIntent.CURRENCY_CNY, List.of(), null, null, "low", "空输入", "rules", List.of());
  }

  /** 模型解析；未配置、失败、返回非 JSON 都返回 {@code null}，由调用方降级。 */
  private TravelIntent fromModel(String text, LocalDate today) {
    if (model == null || !model.configured()) {
      return null;
    }
    String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    String user = "今天是 " + today + "（" + weekday + "）\n用户原话：" + text;
    String answer;
    try {
      answer = model.complete(SYSTEM_PROMPT, user);
    } catch (AgentUnavailableException e) {
      log.warn("自然语言解析降级为规则解析：{}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      log.warn("自然语言解析调用异常，降级为规则解析：{}", e.getMessage());
      return null;
    }
    JsonNode node = extractJson(answer);
    if (node == null) {
      log.warn("自然语言解析返回的不是 JSON，已降级为规则解析");
      return null;
    }
    return sanitize(node, today);
  }

  /** 校验并收敛模型输出：越界截断、非法丢弃，所有修正写进 notes。 */
  private TravelIntent sanitize(JsonNode node, LocalDate today) {
    List<String> notes = new ArrayList<>();
    String intent = upper(text(node, "intent"));
    if (intent == null || !TravelIntent.INTENTS.contains(intent)) {
      if (intent != null) {
        log.info("模型给出未开放的意图 {}，按 PLAN_DRAFT 处理", intent);
      }
      intent = TravelIntent.PLAN_DRAFT;
    }
    Integer days = boundedInt(node.get("days"), 1, MAX_DAYS, "游玩天数", notes);
    Integer travelers = boundedInt(node.get("travelers"), 1, MAX_TRAVELERS, "同行人数", notes);
    BigDecimal budget = budget(node.get("budget"), notes);

    LocalDate depart = date(node.get("departDate"), today, "出发日期", notes);
    LocalDate ret = date(node.get("returnDate"), today, "返回日期", notes);
    if (depart != null && ret != null && ret.isBefore(depart)) {
      notes.add("返回日期早于出发日期，已忽略");
      ret = null;
    }
    if (days != null && depart != null && ret == null) {
      ret = depart.plusDays(days - 1L);
      notes.add("返回日期按 " + days + " 天行程推算为 " + ret);
    }

    return new TravelIntent(intent, cut(text(node, "destination"), 30), days, travelers, budget,
        text(node, "currency"), preferences(node.get("preferences")),
        depart, ret, text(node, "confidence"), text(node, "reason"), "llm", notes);
  }

  /** 把意图与地名结论合成对外结果。 */
  private IntentResolution complete(String text, TravelIntent intent) {
    boolean chat = TravelIntent.CHAT.equals(intent.intent());
    if (chat) {
      // 闲聊不查知识库、也不再问模型：地名结论留空，前端本地回一句引导语
      return new IntentResolution(text, intent.intent(), intent.destination(), false, null, null,
          null, List.of(), intent.days(), intent.travelers(), intent.budget(), intent.currency(),
          intent.preferences(), intent.departDate(), intent.returnDate(), intent.confidence(),
          intent.reason(), intent.source(), intent.notes());
    }
    // 模型读完整句都没找到地名时不再反查：既省一次模型调用，也避免把规则抠出的
    // 半截短语（「找个安静的海边待」）当成「认不出的地名」抛给用户
    boolean modelSaysNoPlace = "llm".equals(intent.source()) && intent.destination() == null;
    PlaceResolution place = modelSaysNoPlace
        ? PlaceResolution.miss("", "", List.of())
        : places.resolve(text, hints(text, intent.destination()));
    List<String> alternatives = place.alternatives();
    String note = place.note();
    if (!place.resolved()) {
      if (intent.destination() == null) {
        // 模型看完整句也没找到地名（「想找个安静的地方待几天」）：不要再把规则抠出的
        // 半截短语当成「认不出的地名」抛给用户，直接说缺目的地，并给几个真实城市
        alternatives = places.popularCities(ALTERNATIVE_LIMIT);
        note = "还差一个目的地：想去哪座城市？直接说城市名就行";
      } else if (alternatives.isEmpty()) {
        alternatives = places.popularCities(ALTERNATIVE_LIMIT);
        if (note == null || note.isBlank()) {
          note = "没认出目的地，可以从下面选一个城市，或者换个说法告诉我";
        }
      }
    }
    return new IntentResolution(text, intent.intent(), intent.destination(), place.resolved(),
        place.city(), place.spot(), note, alternatives, intent.days(), intent.travelers(),
        intent.budget(), intent.currency(), intent.preferences(), intent.departDate(),
        intent.returnDate(), intent.confidence(), intent.reason(), intent.source(),
        intent.notes());
  }

  /** 模型给了地名就用它，没给就从原话里抠；都不行就交整句让模型判断归属。 */
  private List<String> hints(String text, String destination) {
    if (destination != null && !destination.isBlank()) {
      return List.of(destination);
    }
    List<String> guessed = IntentRules.placeHints(text);
    return guessed.isEmpty() ? List.of() : guessed;
  }

  private Integer boundedInt(JsonNode node, int min, int max, String label, List<String> notes) {
    if (node == null || node.isNull()) {
      return null;
    }
    int value;
    if (node.isNumber()) {
      value = node.asInt();
    } else if (node.isTextual() && node.asText().strip().matches("-?\\d+")) {
      value = Integer.parseInt(node.asText().strip());
    } else {
      return null;
    }
    if (value < min) {
      notes.add(label + " " + value + " 小于 " + min + "，已忽略");
      return null;
    }
    if (value > max) {
      notes.add(label + " " + value + " 超过上限 " + max + "，已按 " + max + " 处理");
      return max;
    }
    return value;
  }

  private BigDecimal budget(JsonNode node, List<String> notes) {
    if (node == null || node.isNull()) {
      return null;
    }
    BigDecimal value;
    try {
      if (node.isNumber()) {
        value = node.decimalValue();
      } else if (node.isTextual()) {
        String raw = node.asText().replaceAll("[^0-9.]", "");
        if (raw.isEmpty()) {
          return null;
        }
        value = new BigDecimal(raw);
      } else {
        return null;
      }
    } catch (NumberFormatException e) {
      return null;
    }
    if (value.signum() <= 0) {
      notes.add("预算必须大于 0，已忽略");
      return null;
    }
    if (value.compareTo(MAX_BUDGET) > 0) {
      notes.add("预算超过 " + MAX_BUDGET.toPlainString() + "，已忽略");
      return null;
    }
    return value;
  }

  private LocalDate date(JsonNode node, LocalDate today, String label, List<String> notes) {
    String raw = text(node, null);
    if (raw == null) {
      return null;
    }
    LocalDate value;
    try {
      value = LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
    } catch (RuntimeException e) {
      notes.add(label + "「" + raw + "」不是有效日期，已忽略");
      return null;
    }
    if (value.isBefore(today)) {
      notes.add(label + " " + value + " 早于今天，已忽略");
      return null;
    }
    if (value.isAfter(today.plusDays(DATE_WINDOW_DAYS))) {
      notes.add(label + " " + value + " 超出一年范围，已忽略");
      return null;
    }
    return value;
  }

  /** 偏好：别名映射到词表、去重、最多 4 个；也容忍模型返回用顿号连接的字符串。 */
  private List<String> preferences(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    List<String> raw = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(item -> raw.add(item.asText("")));
    } else if (node.isTextual()) {
      raw.addAll(List.of(node.asText("").split("[、,，;；/\\s]+")));
    }
    Set<String> out = new LinkedHashSet<>();
    for (String item : raw) {
      String key = item == null ? "" : item.strip();
      if (key.isEmpty()) {
        continue;
      }
      String canonical = PREFERENCE_ALIASES.get(key);
      if (canonical == null && PREFERENCE_VOCAB.contains(key)) {
        canonical = key;
      }
      if (canonical == null) {
        // 词表外的说法（「想躺平」「不带娃」）不塞进 preferences：
        // 它们会原样进检索与提示词，反而稀释真正的偏好
        continue;
      }
      out.add(canonical);
      if (out.size() >= MAX_PREFERENCES) {
        break;
      }
    }
    return List.copyOf(out);
  }

  /** 从模型输出里抠第一段 JSON；不假设它遵守「不要代码块」的约束。 */
  private JsonNode extractJson(String answer) {
    if (answer == null || answer.isBlank()) {
      return null;
    }
    int start = answer.indexOf('{');
    int end = answer.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      return mapper.readTree(answer.substring(start, end + 1));
    } catch (Exception e) {
      return null;
    }
  }

  private String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = field == null ? node : node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String s = value.asText("").strip();
    return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
  }

  private String upper(String value) {
    return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
  }

  private String cut(String value, int max) {
    if (value == null) {
      return null;
    }
    String s = value.strip();
    if (s.isEmpty()) {
      return null;
    }
    return s.length() > max ? s.substring(0, max) : s;
  }

  private String normalize(String text) {
    if (text == null) {
      return "";
    }
    String t = text.strip();
    return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
  }

  private void remember(String key, IntentResolution value) {
    if (cache.size() >= CACHE_LIMIT) {
      cache.clear();
    }
    cache.put(key, value);
  }

  private static Map<String, String> aliases() {
    Map<String, String> map = new LinkedHashMap<>();
    for (String canonical : PREFERENCE_VOCAB) {
      map.put(canonical, canonical);
    }
    map.put("自然风光", "自然");
    map.put("山水", "自然");
    map.put("风景", "自然");
    map.put("户外", "自然");
    map.put("历史", "人文");
    map.put("古迹", "人文");
    map.put("博物馆", "人文");
    map.put("古镇", "人文");
    map.put("文化", "人文");
    map.put("寺庙", "人文");
    map.put("小吃", "美食");
    map.put("餐厅", "美食");
    map.put("火锅", "美食");
    map.put("夜市", "美食");
    map.put("孩子", "亲子");
    map.put("小孩", "亲子");
    map.put("带娃", "亲子");
    map.put("拍照", "摄影");
    map.put("出片", "摄影");
    map.put("逛街", "购物");
    map.put("免税", "购物");
    map.put("夜景", "夜游");
    map.put("泡汤", "温泉");
    map.put("海边", "海岛");
    map.put("沙滩", "海岛");
    map.put("轻松", "慢节奏");
    map.put("悠闲", "慢节奏");
    map.put("不赶", "慢节奏");
    map.put("躺平", "慢节奏");
    return Map.copyOf(map);
  }
}
