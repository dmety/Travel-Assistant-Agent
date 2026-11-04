package com.travelassistant.server.domain.ticket;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * 一处景点的官方购票渠道（V2「票务 Agent：购票辅助」）。
 *
 * <p>本类只描述**人工核验过并写进配置**的官方入口。系统不抓取、不推断、不生成链接：
 * 找不到配置就明确告诉用户「没有经核验的入口，请自行进入景区官方渠道」。
 * 这是硬约束——猜一个网址给用户点，比不给更糟。
 *
 * @param attractionId  景点 id（对应 attractions_index.json），可为空，按名称匹配时不需要
 * @param attractionName 景点名称
 * @param city          所在城市
 * @param channelName   渠道名称，如「故宫博物院官网」
 * @param officialUrl   官方入口地址，必须是 http/https 绝对地址
 * @param ticketTypes   已查询到的票种，可为空（表示未查到票种信息）
 * @param rules         购票规则要点，如「需实名预约」「提前 7 天放票」
 * @param source        信息来源说明
 * @param verifiedAt    人工核验日期；为空表示尚未核验
 * @param note          补充说明
 */
public record TicketChannel(
    @JsonProperty("attractionId") String attractionId,
    @JsonProperty("attractionName") String attractionName,
    @JsonProperty("city") String city,
    @JsonProperty("channelName") String channelName,
    @JsonProperty("officialUrl") String officialUrl,
    @JsonProperty("ticketTypes") List<TicketType> ticketTypes,
    @JsonProperty("rules") List<String> rules,
    @JsonProperty("source") String source,
    @JsonProperty("verifiedAt") LocalDate verifiedAt,
    @JsonProperty("note") String note) {

  @JsonCreator
  public TicketChannel {
    ticketTypes = (ticketTypes == null) ? List.of() : List.copyOf(ticketTypes);
    rules = (rules == null) ? List.of() : List.copyOf(rules);
  }

  /** 入口是否已人工核验。未核验的入口照旧展示，但要在文案里标出来。 */
  public boolean verified() {
    return verifiedAt != null;
  }

  /** 配置是否足够支撑一个可点的购票入口。 */
  public boolean usable() {
    return officialUrl != null && !officialUrl.isBlank()
        && (officialUrl.startsWith("https://") || officialUrl.startsWith("http://"));
  }
}
