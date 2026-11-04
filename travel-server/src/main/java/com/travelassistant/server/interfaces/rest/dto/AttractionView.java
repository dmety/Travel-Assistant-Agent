package com.travelassistant.server.interfaces.rest.dto;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.attraction.PlaceholderValues;
import java.util.List;

/**
 * 景点视图对象。
 *
 * <p>保留原始 {@code ticket}/{@code openTime}（可能为 null，表示数据缺失），
 * 同时提供 {@code ticketText}/{@code openTimeText}：缺失时明确为"未知"。
 * 调用方不得把 null 解释为"免费"或"全天开放"。
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
    String openTimeText) {

  public static AttractionView from(Attraction a) {
    return new AttractionView(
        a.id(), a.name(), a.city(), a.province(), a.type(), a.level(), a.rating(),
        a.openTime(), a.ticket(), a.address(), a.tel(), a.website(), a.image(),
        a.description(), a.lat(), a.lon(), a.sourceText(), a.sources(),
        PlaceholderValues.display(a.ticket()),
        PlaceholderValues.display(a.openTime()));
  }
}
