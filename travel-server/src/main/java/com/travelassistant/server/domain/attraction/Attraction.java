package com.travelassistant.server.domain.attraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 景点只读数据模型，字段与离线库 {@code attractions_index.json} 一一对应。
 *
 * <p>本模型是**只读快照**：不做实时票价、库存或开放状态保证。
 * 库中缺失的字段一律为 {@code null}，展示层需明确呈现为"未知"，
 * 不能把 {@code null} 当作"免费"或"全天开放"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Attraction(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("city") String city,
    @JsonProperty("province") String province,
    @JsonProperty("type") String type,
    @JsonProperty("level") String level,
    @JsonProperty("rating") String rating,
    @JsonProperty("open_time") String openTime,
    @JsonProperty("ticket") String ticket,
    @JsonProperty("address") String address,
    @JsonProperty("tel") String tel,
    @JsonProperty("website") String website,
    @JsonProperty("image") String image,
    @JsonProperty("description") String description,
    @JsonProperty("lat") Double lat,
    @JsonProperty("lon") Double lon,
    @JsonProperty("source") String source,
    @JsonProperty("sources") List<String> sources,
    @JsonProperty("ticket_status") String ticketStatus,
    @JsonProperty("open_time_status") String openTimeStatus) {

  public Attraction {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /** 数据来源展示文本；多源时用" + "连接，全空时返回 null。 */
  public String sourceText() {
    if (sources != null && !sources.isEmpty()) {
      return String.join(" + ", sources);
    }
    return source;
  }
}
