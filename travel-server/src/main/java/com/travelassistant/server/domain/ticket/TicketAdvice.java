package com.travelassistant.server.domain.ticket;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 购票辅助结果：要么给出经核验的官方入口，要么明确说「没有」。
 *
 * <p>{@code stockVerified} 恒为 {@code false}。本项目没有接入任何景区的库存接口，
 * 所以「余票未核验」不是暂时缺失，而是这一阶段的事实。把它写成字段而不是写死一句文案，
 * 是为了让前端和 Agent 都按「未核验」处理，而不是把有票当成默认。
 *
 * <p>入口用列表返回：同一景区可能同时有官网、公众号、小程序三个官方渠道，
 * 让用户自己挑，比系统替他选一个更可靠。
 *
 * @param status        查询结果状态
 * @param stockVerified 余票是否已核验（本阶段恒为 false）
 * @param channels      命中的官方渠道，未命中时为空列表
 * @param notice        面向用户的中文说明
 * @param source        本次结果的来源
 * @param queriedAt     查询时间，供用户判断信息新鲜度
 */
public record TicketAdvice(
    @JsonProperty("attractionId") String attractionId,
    @JsonProperty("attractionName") String attractionName,
    @JsonProperty("city") String city,
    @JsonProperty("status") TicketLookupStatus status,
    @JsonProperty("stockVerified") boolean stockVerified,
    @JsonProperty("channels") List<TicketChannel> channels,
    @JsonProperty("notice") String notice,
    @JsonProperty("source") String source,
    @JsonProperty("queriedAt") Instant queriedAt) {

  /** 未找到入口时的固定说明：不猜测链接，也不假装「暂无票」。 */
  public static final String NO_CHANNEL_NOTICE =
      "未找到经核验的官方购票入口。请自行进入景区官方网站或官方公众号购买，本系统不生成猜测链接。";

  /** 找到入口时的余票说明。 */
  public static final String STOCK_UNVERIFIED_NOTICE =
      "余票未核验：本系统未接入景区库存接口，购票前请以官方页面显示为准。";

  @JsonCreator
  public TicketAdvice {
    channels = (channels == null) ? List.of() : List.copyOf(channels);
  }

  public static TicketAdvice found(String attractionId, String attractionName, String city,
      List<TicketChannel> channels, Instant queriedAt) {
    boolean allVerified = channels.stream().allMatch(TicketChannel::verified);
    String notice = allVerified
        ? STOCK_UNVERIFIED_NOTICE
        : STOCK_UNVERIFIED_NOTICE + "部分入口未记录核验日期，使用前请再次确认。";
    String source = channels.stream()
        .map(TicketChannel::source)
        .filter(s -> s != null && !s.isBlank())
        .findFirst()
        .orElse("本系统官方入口配置");
    return new TicketAdvice(attractionId, attractionName, city, TicketLookupStatus.CHANNEL_FOUND,
        false, channels, notice, source, queriedAt);
  }

  public static TicketAdvice notFound(String attractionId, String attractionName, String city,
      Instant queriedAt) {
    return new TicketAdvice(attractionId, attractionName, city,
        TicketLookupStatus.NO_VERIFIED_CHANNEL, false, List.of(), NO_CHANNEL_NOTICE,
        "本系统官方入口配置", queriedAt);
  }
}
