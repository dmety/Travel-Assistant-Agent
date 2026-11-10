package com.travelassistant.server.application.intent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一次自然语言解析的完整结果，直接作为 {@code POST /api/intent/parse} 的响应体。
 *
 * <p>为什么把意图、槽位、地名结论拍平成一个对象：前端一次请求就要拿到
 * 「干什么、去哪、几天、几个人、什么时候、要不要弹选项卡」全部信息，
 * 否则又回到「先解析地名再解析天数」的多轮往返。
 *
 * @param text         用户原话（已截断到上限）
 * @param intent       意图，见 {@link TravelIntent#INTENTS}
 * @param destination  原话里的目的地写法，没提到为 {@code null}
 * @param resolved     目的地是否已落到知识库里的城市
 * @param city         可交给规划的 {@code destination}，未命中为 {@code null}
 * @param spot         命中的具体景点名，没有为 {@code null}
 * @param note         给人看的说明，例如「黄梅县属黄冈市，按 黄冈 检索」
 * @param alternatives 未命中时可直接点选的城市，最多 6 个
 * @param days         游玩天数
 * @param travelers    同行人数
 * @param budget       预算上限
 * @param currency     币种
 * @param preferences  偏好关键词
 * @param departDate   出发日期
 * @param returnDate   返回日期
 * @param confidence   解析置信度
 * @param reason       一句话依据
 * @param source       来源：{@code llm} / {@code rules}
 * @param carried      这一句没有明说、从上文沿用过来的字段名（如 {@code city} / {@code days}）
 * @param reply        闲聊时模型给出的自然语言回复；其它意图为 {@code null}
 * @param notes        解析过程中的修正说明
 */
public record IntentResolution(
    String text,
    String intent,
    String destination,
    boolean resolved,
    String city,
    String spot,
    String note,
    List<String> alternatives,
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
    List<String> carried,
    String reply,
    List<String> notes) {

  public IntentResolution {
    alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    preferences = preferences == null ? List.of() : List.copyOf(preferences);
    carried = carried == null ? List.of() : List.copyOf(carried);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }
}
