package com.travelassistant.server.application.place;

import java.util.List;

/**
 * 一次地名识别的结果。
 *
 * <p>为什么单独建模：前端在「静态别名表 → 知识库反查」都落空后需要知道
 * **是谁**做出的判断（知识库还是模型）、**依据**是什么、还能尝试哪些相近城市。
 * 只返回一个布尔值的话，界面只能回一句「识别不出」，等于把问题丢回给用户。
 *
 * @param query        用户原话里被识别的那一段
 * @param resolved     是否给出了可用目的地
 * @param city         归属城市（可直接作为 planning 的 destination）
 * @param spot         若识别到的是具体景点，景点名；否则为空
 * @param province     省份（知识库里有才给）
 * @param level        识别粒度：{@code city} / {@code county} / {@code spot} / {@code unknown}
 * @param source       依据来源：{@code kb}（知识库直查）/ {@code llm}（模型判断）/ {@code none}
 * @param note         给人看的说明，例如「黄梅县属黄冈市，按黄冈检索」
 * @param alternatives 未识别时给出的相近城市（可点选），最多 3 个
 */
public record PlaceResolution(
    String query,
    boolean resolved,
    String city,
    String spot,
    String province,
    String level,
    String source,
    String note,
    List<String> alternatives) {

  public PlaceResolution {
    alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
  }

  /** 知识库或模型给出的正向结果。 */
  public static PlaceResolution hit(String query, String city, String spot, String province,
      String level, String source, String note) {
    return new PlaceResolution(query, true, city, spot, province, level, source, note, List.of());
  }

  /** 没认出来：带一句可读原因与可选城市，方便前端直接渲染成按钮。 */
  public static PlaceResolution miss(String query, String note, List<String> alternatives) {
    return new PlaceResolution(query, false, null, null, null, "unknown", "none", note,
        alternatives);
  }
}
