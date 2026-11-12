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
import java.util.Objects;
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
 * <p><b>上下文记忆</b>：调用方把最近几轮对话与已确认的槽位一起传进来。模型负责「读懂指代」
 * （「有没有那种很小众的馆子」接着上一轮的美食聊），Java 负责「补得克制的缺」——
 * 这一句没提到的城市才从上文补，且只在排行程 / 找吃的 / 问天气时补，闲聊永不继承；
 * 补了哪些字段会写进 {@link IntentResolution#carried()}，前端如实告诉用户「按上文记住的武汉」。
 *
 * <p>结果按「日期 + 原话 + 上下文指纹」缓存：相对日期当天有效，换天自动失效，
 * 不会把「明天」缓存成昨天；同一句话在不同上下文里含义不同（「那呢」单独看没有地名），
 * 所以历史与已确认槽位要一起进缓存键。
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
  /** 只带最近 8 轮对话：再多既稀释重点，也把提示词撑长（模型对开头的信息最不敏感）。 */
  private static final int MAX_HISTORY = 8;
  /** 闲聊回复上限：一句话能说完的事情，长回答反而像在念稿。 */
  private static final int MAX_REPLY = 200;
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
      8. intent 四选一：
         PLAN_DRAFT = 想去某地、要排行程、问某地有什么好玩的；
         FOOD_RECOMMEND = 主要问吃什么、找餐厅、找馆子、本地小吃；
         WEATHER = 问天气、气温、下不下雨、要不要带伞、穿什么衣服；
         CHAT = 打招呼、闲聊、问你是谁、感谢、与旅行无关的话题。
      9. confidence 填 high / medium / low；reason 用一句话说明依据，不要客套。
      10. 不要输出 Markdown 代码块或解释段落，只输出 JSON。
      11. 结合「最近对话」理解省略和指代：用户说「那呢」「还有别的吗」「换一批」「再玩两天」
          「有没有小馆子」这类话，先看上一轮在聊什么，再决定 intent 与 destination。
      12. 接着上文聊同一座城市时，可以把上文出现过的城市填进 destination；用户提到新地方时一律用新的；
         整句看不出任何地方才填 null。不要把上文的天数、人数、预算当成这一句说过的值。
      13. 只有 intent=CHAT 时才填 reply：用一到两句话自然回答（30~60 字），称呼「你」，
         不客套、不用 emoji、不写 Markdown、不给网址。被问到身份、能力、数据来源时按第 14 条回答；
         不知道就直说不知道，绝不能编造链接、价格、营业时间或房源余票。其它 intent 的 reply 填 null。
      14. 你掌握的事实只有这些：景区知识库里的景点条目（含票价与开放时间原文，可能已过期）；
         高德天气预报（预报不是实况）；城市周边餐馆（按坐标查到的真实 POI）。
         你没有实时余票、没有官方购票链接、没有「价格表」这样的页面可以给链接，也不能代订或退款。
      15. 只输出 JSON，形如：
      {"intent":"PLAN_DRAFT","destination":"武当山","days":4,"travelers":null,"budget":6000,
       "departDate":"2026-11-21","returnDate":null,"preferences":["人文"],"confidence":"high",
       "reason":"下周六出发玩四天，预算六千","reply":null}
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
   * 解析一句话（无上下文）。
   *
   * @param text 用户原话
   * @return 永远非 {@code null}；解析不出目的地时 {@code resolved=false} 并带可点城市
   */
  public IntentResolution parse(String text) {
    return parse(text, List.of(), ConversationState.empty());
  }

  /**
   * 带上下文解析一句话。
   *
   * @param text    用户原话
   * @param history 最近几轮对话，越靠后越近；可以为 {@code null} 或空
   * @param state   本轮会话里已经确认过的槽位；可以为 {@code null}
   * @return 永远非 {@code null}
   */
  public IntentResolution parse(String text, List<ConversationTurn> history,
      ConversationState state) {
    String t = normalize(text);
    LocalDate today = LocalDate.now(clock);
    if (t.isEmpty()) {
      return empty();
    }
    List<ConversationTurn> turns = trim(history);
    ConversationState known = state == null ? ConversationState.empty() : state;
    // 相对日期按天失效，所以缓存键带上当天日期；同一句话在不同上下文里意思不同，历史也要进键
    String key = today + "|" + fingerprint(turns, known) + "|" + t;
    IntentResolution cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    TravelIntent intent = fromModel(t, today, turns, known);
    if (intent == null) {
      intent = IntentRules.parse(t, today);
    }
    List<String> carried = new ArrayList<>();
    intent = inherit(intent, known, carried);
    IntentResolution resolution = complete(t, intent, carried);
    remember(key, resolution);
    return resolution;
  }

  private IntentResolution empty() {
    return new IntentResolution("", TravelIntent.CHAT, null, false, null, null,
        "说说你想去哪儿、玩几天，我来排行程", List.of(), null, null, null,
        TravelIntent.CURRENCY_CNY, List.of(), null, null, "low", "空输入", "rules",
        List.of(), null, List.of());
  }

  /** 模型解析；未配置、失败、返回非 JSON 都返回 {@code null}，由调用方降级。 */
  private TravelIntent fromModel(String text, LocalDate today, List<ConversationTurn> turns,
      ConversationState known) {
    if (model == null || !model.configured()) {
      return null;
    }
    String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    String user = userPrompt(text, today, weekday, turns, known);
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

  /**
   * 拼模型输入：今天、最近对话、已确认槽位、原话。
   *
   * <p>顺序有意如此——把「今天」放最前（相对日期要靠它换算），原话放最后
   * （紧挨着输出，模型最不容易漏读），历史夹在中间当背景。
   */
  private String userPrompt(String text, LocalDate today, String weekday,
      List<ConversationTurn> turns, ConversationState known) {
    StringBuilder sb = new StringBuilder();
    sb.append("今天是 ").append(today).append("（").append(weekday).append("）\n");
    if (!turns.isEmpty()) {
      sb.append("最近对话（从早到晚）：\n");
      for (ConversationTurn turn : turns) {
        sb.append(turn.fromUser() ? "用户：" : "助手：").append(turn.text()).append('\n');
      }
    }
    if (!known.isEmpty()) {
      sb.append("本轮会话已确认（只在上文明显被指代时才沿用）：").append(describe(known))
          .append('\n');
    }
    sb.append("用户原话：").append(text);
    return sb.toString();
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

    // 回复只跟闲聊走：排行程/找吃的/问天气的结果由各自的卡片呈现，
    // 模型顺口多答一句反而和真实数据打架（数字与结论必须来自 Provider）
    String reply = TravelIntent.CHAT.equals(intent) ? cleanReply(text(node, "reply")) : null;

    return new TravelIntent(intent, cut(text(node, "destination"), 30), days, travelers, budget,
        text(node, "currency"), preferences(node.get("preferences")),
        depart, ret, text(node, "confidence"), text(node, "reason"), "llm", reply, notes);
  }

  /** 把意图与地名结论合成对外结果。 */
  private IntentResolution complete(String text, TravelIntent intent, List<String> carried) {
    if (TravelIntent.CHAT.equals(intent.intent())) {
      // 闲聊不查知识库：地名结论留空，回复由模型结合上文给出（没有模型时前端有兜底文案）
      return new IntentResolution(text, intent.intent(), intent.destination(), false, null, null,
          null, List.of(), intent.days(), intent.travelers(), intent.budget(), intent.currency(),
          intent.preferences(), intent.departDate(), intent.returnDate(), intent.confidence(),
          intent.reason(), intent.source(), List.of(), intent.reply(), intent.notes());
    }
    if (TravelIntent.WEATHER.equals(intent.intent())) {
      return weather(text, intent, carried);
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
        carried, null, intent.notes());
  }

  /**
   * 天气不查景点知识库。
   *
   * <p>知识库只覆盖部分城市，且景区名不是行政区划名（「武当山」的天气要问十堰）；
   * 高德天气接口本身能按城市名换 adcode，所以这里直接把用户说的地名交给它，
   * 顺便省掉一次地名核实——查不到时高德会报错，前端如实显示原因，不会编温度。
   */
  private IntentResolution weather(String text, TravelIntent intent, List<String> carried) {
    String city = intent.destination();
    if (city == null) {
      return new IntentResolution(text, intent.intent(), null, false, null, null,
          "想查哪座城市的天气？直接说城市名，比如「天津天气」", places.popularCities(ALTERNATIVE_LIMIT),
          intent.days(), intent.travelers(), intent.budget(), intent.currency(),
          intent.preferences(), intent.departDate(), intent.returnDate(), intent.confidence(),
          intent.reason(), intent.source(), carried, null, intent.notes());
    }
    return new IntentResolution(text, intent.intent(), city, true, city, null, null, List.of(),
        intent.days(), intent.travelers(), intent.budget(), intent.currency(),
        intent.preferences(), intent.departDate(), intent.returnDate(), intent.confidence(),
        intent.reason(), intent.source(), carried, null, intent.notes());
  }

  /** 模型给了地名就用它，没给就从原话里抠；都不行就交整句让模型判断归属。 */
  private List<String> hints(String text, String destination) {
    if (destination != null && !destination.isBlank()) {
      return List.of(destination);
    }
    List<String> guessed = IntentRules.placeHints(text);
    return guessed.isEmpty() ? List.of() : guessed;
  }

  /**
   * 把上文记住的槽位补进这一句。
   *
   * <p>三条克制规则，都是为了「补得少但补得准」：
   * <ol>
   *   <li>闲聊不继承——「你叫什么名字」不该被上文的「武汉」污染成查景点；</li>
   *   <li>只补这一句真的没有的字段，模型自己给了值就以模型为准（它看得到原话）；</li>
   *   <li>找吃的与问天气只补城市——沿用上一次的天数和预算在这两类里没有任何意义。</li>
   * </ol>
   * 补了哪些字段记进 {@code carried}，前端据此显示「沿用上文」。
   */
  private TravelIntent inherit(TravelIntent intent, ConversationState known,
      List<String> carried) {
    String kind = intent.intent();
    if (known.isEmpty() || TravelIntent.CHAT.equals(kind)) {
      return intent;
    }
    String destination = intent.destination();
    if (destination == null && known.city() != null) {
      destination = known.city();
      carried.add("city");
    }
    Integer days = intent.days();
    Integer travelers = intent.travelers();
    BigDecimal budget = intent.budget();
    List<String> preferences = intent.preferences();
    if (TravelIntent.PLAN_DRAFT.equals(kind)) {
      if (days == null && known.days() != null) {
        days = known.days();
        carried.add("days");
      }
      if (travelers == null && known.travelers() != null) {
        travelers = known.travelers();
        carried.add("travelers");
      }
      if (budget == null && known.budget() != null) {
        budget = known.budget();
        carried.add("budget");
      }
      if (preferences.isEmpty() && !known.preferences().isEmpty()) {
        preferences = known.preferences();
        carried.add("preferences");
      }
    }
    boolean unchanged = Objects.equals(destination, intent.destination())
        && Objects.equals(days, intent.days())
        && Objects.equals(travelers, intent.travelers())
        && Objects.equals(budget, intent.budget())
        && preferences.equals(intent.preferences());
    if (unchanged) {
      return intent;
    }
    return new TravelIntent(kind, destination, days, travelers, budget, intent.currency(),
        preferences, intent.departDate(), intent.returnDate(), intent.confidence(),
        intent.reason(), intent.source(), intent.reply(), intent.notes());
  }

  /** 只留最近几轮、丢掉空消息：空消息多半是打字机占位或清空的草稿。 */
  private List<ConversationTurn> trim(List<ConversationTurn> history) {
    if (history == null || history.isEmpty()) {
      return List.of();
    }
    List<ConversationTurn> usable = new ArrayList<>();
    for (ConversationTurn turn : history) {
      if (turn != null && turn.usable()) {
        usable.add(turn);
      }
    }
    if (usable.size() <= MAX_HISTORY) {
      return List.copyOf(usable);
    }
    return List.copyOf(usable.subList(usable.size() - MAX_HISTORY, usable.size()));
  }

  /** 上下文指纹：同一句话在不同上文里意思不同，缓存必须区分开。 */
  private String fingerprint(List<ConversationTurn> turns, ConversationState known) {
    StringBuilder sb = new StringBuilder();
    for (ConversationTurn turn : turns) {
      sb.append(turn.role()).append(':').append(turn.text()).append('\u0001');
    }
    sb.append(known.city()).append('|').append(known.days()).append('|')
        .append(known.travelers()).append('|').append(known.budget()).append('|')
        .append(known.preferences());
    return Integer.toHexString(sb.toString().hashCode());
  }

  /** 给模型看的槽位摘要，纯文本、不带 JSON 括号，避免它误以为是本轮要输出的东西。 */
  private String describe(ConversationState state) {
    List<String> parts = new ArrayList<>();
    if (state.city() != null) {
      parts.add("城市=" + state.city());
    }
    if (state.days() != null) {
      parts.add("天数=" + state.days());
    }
    if (state.travelers() != null) {
      parts.add("人数=" + state.travelers());
    }
    if (state.budget() != null) {
      parts.add("预算=" + state.budget().stripTrailingZeros().toPlainString());
    }
    if (!state.preferences().isEmpty()) {
      parts.add("偏好=" + String.join("、", state.preferences()));
    }
    return String.join("；", parts);
  }

  /**
   * 清理模型给的闲聊回复。
   *
   * <p>最要紧的一条：**去掉网址**。模型很爱在「给我官方链接」这类问题后面补一个看起来很真的
   * URL，而本项目根本没有这种页面，放出去就是把幻觉当事实。剩下的话还有内容就留着，
   * 剥完什么都不剩就返回 {@code null}，由前端用兜底文案。
   */
  private String cleanReply(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.replace("**", "").replace("`", "").replace("\n", " ").strip();
    s = s.replaceAll("https?://\\S+", "").replaceAll("www\\.\\S+", "").strip();
    if (s.length() < 4) {
      return null;
    }
    return s.length() > MAX_REPLY ? s.substring(0, MAX_REPLY) : s;
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
