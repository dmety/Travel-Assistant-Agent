package com.travelassistant.server.interfaces.rest.dto;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.attraction.PlaceholderValues;
import java.util.List;
import java.util.Locale;

/**
 * 景点视图对象。
 *
 * <p>保留原始 {@code ticket}/{@code openTime}（可能为 null，表示数据缺失），
 * 同时提供 {@code ticketText}/{@code openTimeText}：缺失时明确为"未知"。
 * 调用方不得把 null 解释为"免费"或"全天开放"。
 *
 * <p><b>核验状态</b>：{@code ticketStatus}/{@code openTimeStatus} 描述字段值的核验程度，
 * 统一取值 {@code verified} / {@code unverified} / {@code pending}：
 * <ul>
 *   <li>{@code verified}：有逐行来源与核验日期，可按官方信息展示；</li>
 *   <li>{@code unverified}：有值但缺逐行证据，只能作参考，不得当作官方定价/开放时间；</li>
 *   <li>{@code pending}：无值或尚未核验。</li>
 * </ul>
 * 「字段有值」不等于「已核验」。展示层必须据此标注，未核验值不得以免责声明缺失
 * 的方式直接呈现。
 */
public record AttractionView(
    String id,
    String name,
    String city,
    String province,
    String type,
    String level,
    String rating,
    String openTime,
    String ticket,
    String address,
    String tel,
    String website,
    String image,
    String description,
    Double lat,
    Double lon,
    String source,
    List<String> sources,
    String ticketText,
    String openTimeText,
    String ticketStatus,
    String openTimeStatus) {

  public static AttractionView from(Attraction a) {
    return new AttractionView(
        a.id(), a.name(), a.city(), a.province(), a.type(), a.level(), a.rating(),
        a.openTime(), a.ticket(), a.address(), a.tel(), a.website(), a.image(),
        a.description(), a.lat(), a.lon(), a.sourceText(), a.sources(),
        PlaceholderValues.display(a.ticket()),
        PlaceholderValues.display(a.openTime()),
        status(a.ticketStatus()),
        status(a.openTimeStatus()));
  }

  /**
   * 归一历史词表：早期测试库写 {@code confirmed}，正式库写 {@code verified}；
   * 空值按 {@code pending}，其它未知值保守按 {@code unverified} 处理。
   */
  private static String status(String raw) {
    if (raw == null || raw.isBlank()) {
      return "pending";
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "verified", "confirmed" -> "verified";
      case "unverified" -> "unverified";
      case "pending" -> "pending";
      default -> "unverified";
    };
  }
}
