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
    @JsonProperty("open_time_status") String openTimeStatus,
    // 坐标系标签来自离线索引：WGS-84 / GCJ-02。缺失时调用方不得自行假设。
    @JsonProperty("coordinateSystem") String coordinateSystem,
    @JsonProperty("retrievedAt") String retrievedAt,
    @JsonProperty("informationUpdatedAt") String informationUpdatedAt,
    @JsonProperty("fieldEvidence") java.util.Map<String, List<FieldEvidence>> fieldEvidence) {

  public record FieldEvidence(String value, String source, String sourceId, String retrievedAt,
      String informationUpdatedAt, String status) {}

  public Attraction(String id, String name, String city, String province, String type,
      String level, String rating, String openTime, String ticket, String address, String tel,
      String website, String image, String description, Double lat, Double lon, String source,
      List<String> sources, String ticketStatus, String openTimeStatus, String coordinateSystem,
      String retrievedAt, String informationUpdatedAt) {
    this(id,name,city,province,type,level,rating,openTime,ticket,address,tel,website,image,description,
        lat,lon,source,sources,ticketStatus,openTimeStatus,coordinateSystem,retrievedAt,informationUpdatedAt,java.util.Map.of());
  }

  public List<FieldEvidence> evidenceFor(String field) {
    if (fieldEvidence.containsKey(field)) return fieldEvidence.get(field);
    String value = switch(field) {
      case "type" -> type;
      case "province" -> province;
      case "description" -> description;
      case "tel" -> tel;
      case "website" -> website;
      case "image" -> image;
      case "ticket" -> ticket;
      case "openTime" -> openTime;
      case "level" -> level;
      case "rating" -> rating;
      case "address" -> address;
      case "coordinates" -> lat == null || lon == null ? null : lon + "," + lat + "," + coordinateSystem;
      default -> null;
    };
    if (value == null) return List.of();
    return List.of(new FieldEvidence(value, sourceText(), id, retrievedAt, informationUpdatedAt,
        field.equals("ticket") ? ticketStatus : field.equals("openTime") ? openTimeStatus : "unverified"));
  }

  public String fieldSource(String field) {
    return evidenceFor(field).stream().map(e -> (e.source() == null ? "来源未标注" : e.source())
        + " [值=" + e.value() + "; 信息日期=" + (e.informationUpdatedAt() == null ? "未知" : e.informationUpdatedAt()) + "]")
        .collect(java.util.stream.Collectors.joining("；"));
  }

  /** 兼容本地索引、地图结果和现有调用方的旧构造参数。 */
  public Attraction(String id, String name, String city, String province, String type,
      String level, String rating, String openTime, String ticket, String address,
      String tel, String website, String image, String description, Double lat, Double lon,
      String source, List<String> sources, String ticketStatus, String openTimeStatus,
      String coordinateSystem) {
    this(id, name, city, province, type, level, rating, openTime, ticket, address, tel,
        website, image, description, lat, lon, source, sources, ticketStatus, openTimeStatus,
        coordinateSystem, null, null);
  }

  public Attraction {
    fieldEvidence = fieldEvidence == null ? java.util.Map.of() : fieldEvidence.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(java.util.Map.Entry::getKey, e -> List.copyOf(e.getValue())));
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
