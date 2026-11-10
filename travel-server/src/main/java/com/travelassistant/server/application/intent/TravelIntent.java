package com.travelassistant.server.application.intent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 一句话解析出来的旅行意图与槽位。
 *
 * <p>为什么单独建模而不再让前端正则解析：「下周六带爸妈去武当山玩四天，预算六千」
 * 这类说法用正则永远追不完（「六千」不是阿拉伯数字、「带爸妈」隐含人数、
 * 「下周六」要相对今天换算）。这里把理解交给模型，把**校验**留在 Java：
 * 模型只负责给值，范围、日期边界、偏好词表都由本类与 {@link IntentParser} 兜住。
 *
 * @param intent      意图：{@code PLAN_DRAFT} / {@code FOOD_RECOMMEND} / {@code WEATHER}
 *                    / {@code CHAT}
 * @param destination 用户原话里的目的地（未解析成城市），没提到则为 {@code null}
 * @param days        游玩天数，1~30，没提到为 {@code null}
 * @param travelers   同行总人数（含本人），1~30
 * @param budget      预算上限（单位由 {@code currency} 决定）
 * @param currency    币种，默认 CNY
 * @param preferences 偏好关键词，已收敛到固定词表
 * @param departDate  出发日期，缺失为 {@code null}
 * @param returnDate  返回日期，缺失为 {@code null}
 * @param confidence  模型自评置信度：high / medium / low
 * @param reason      一句话依据，便于排查「为什么认成这个」
 * @param source      来源：{@code llm} 模型解析 / {@code rules} 规则降级
 * @param reply       只有闲聊才有的自然语言回复；其它意图为 {@code null}
 * @param notes       解析过程中的修正说明，例如「天数 99 超过上限 30，已按 30 处理」
 */
public record TravelIntent(
    String intent,
    String destination,
    Integer days,
    Integer travelers,
    BigDecimal budget,
    String currency,
    List<String> preferences,
    LocalDate departDate,
    LocalDate returnDate,
    String confidence,
    String reason,
    String source,
    String reply,
    List<String> notes) {

  /** 与旅行无关的闲聊：由模型结合上文回一句，不查知识库也不调编排层。 */
  public static final String CHAT = "CHAT";
  /** 排行程（含「先看看景点」）。 */
  public static final String PLAN_DRAFT = "PLAN_DRAFT";
  /** 找吃的，交给内置美食 Provider。 */
  public static final String FOOD_RECOMMEND = "FOOD_RECOMMEND";
  /**
   * 问天气：直接走既有的 {@code GET /api/location/weather}（高德预报）。
   *
   * <p>为什么不在契约里叫 {@code WEATHER_ADVICE}：那个是 Agent 能力枚举，
   * 需要有一个 Provider 实现它才能被编排层路由；天气是既有 REST 能力的直接调用，
   * 不经过编排层，所以用独立的意图名，前端按名字调对应接口。
   */
  public static final String WEATHER = "WEATHER";

  /**
   * 只开放这四个意图。
   *
   * <p>契约里的 {@code TICKET_ADVICE} / {@code CONTENT_INSPIRATION} 也真实存在，
   * 但票务只回结构化计数、灵感没有内置 Provider（只会落到 {@code builtin.rules}
   * 的 REFUSED），接进来用户体验反而更差。{@code WEATHER_ADVICE} 对应的能力已经有
   * 现成 REST（{@code /api/location/weather}），所以用 {@link #WEATHER} 直连，不经过编排层。
   */
  public static final Set<String> INTENTS = Set.of(PLAN_DRAFT, FOOD_RECOMMEND, WEATHER, CHAT);

  public static final String CURRENCY_CNY = "CNY";

  public TravelIntent {
    preferences = preferences == null ? List.of() : List.copyOf(preferences);
    notes = notes == null ? List.of() : List.copyOf(notes);
    currency = currency == null || currency.isBlank() ? CURRENCY_CNY : currency;
  }
}
