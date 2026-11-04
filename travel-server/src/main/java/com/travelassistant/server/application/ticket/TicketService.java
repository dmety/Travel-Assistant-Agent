package com.travelassistant.server.application.ticket;

import com.travelassistant.server.application.attraction.AttractionService;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.domain.ticket.TicketChannel;
import com.travelassistant.server.infrastructure.ticketing.TicketChannelStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 购票辅助（V2「票务 Agent」）。
 *
 * <p>只做一件事：把**人工核验过的官方入口**按景点找出来，连同票种、规则、来源和查询时间
 * 一起返回；找不到就说找不到。这一阶段不创建订单、不处理支付、不查实时库存——
 * 没有库存接口时，「余票未核验」是唯一诚实的答案。
 *
 * <p>景点库不可用不影响本服务：购票入口来自独立配置，两者互不牵连。
 */
@Service
public class TicketService {

  private final TicketChannelStore channels;
  private final AttractionService attractions;

  public TicketService(TicketChannelStore channels, AttractionService attractions) {
    this.channels = channels;
    this.attractions = attractions;
  }

  /**
   * 查询某景点的购票辅助信息。
   *
   * @param attractionId 景点 id，可为空
   * @param name         景点名称，可为空；与 id 至少提供其一
   * @param city         城市，可为空；给了就只在该城市内匹配，避免同名串号
   */
  public TicketAdvice lookup(String attractionId, String name, String city) {
    String id = blankToNull(attractionId);
    String keyword = blankToNull(name);
    String cityKey = blankToNull(city);

    if (id == null && keyword == null) {
      throw new InvalidTicketQueryException("请提供景点 id 或景点名称");
    }

    // 只有 id 时，尽量从景点库补齐名称与城市，让结果可读
    if (id != null) {
      Optional<Attraction> attraction = findAttraction(id);
      if (attraction.isPresent()) {
        if (keyword == null) {
          keyword = attraction.get().name();
        }
        if (cityKey == null) {
          cityKey = attraction.get().city();
        }
      }
    }

    Instant now = Instant.now();

    if (id != null) {
      Optional<TicketChannel> byId = channels.findByAttractionId(id);
      if (byId.isPresent()) {
        return TicketAdvice.found(id, keyword, cityKey, List.of(byId.get()), now);
      }
    }

    if (keyword != null) {
      List<TicketChannel> byName = channels.findByName(keyword, cityKey);
      if (!byName.isEmpty()) {
        return TicketAdvice.found(id, keyword, cityKey, byName, now);
      }
    }

    return TicketAdvice.notFound(id, keyword, cityKey, now);
  }

  /** 已配置的全部官方入口，供客户端查看「目前收录了哪些」。 */
  public List<TicketChannel> configuredChannels() {
    return channels.findAll();
  }

  /** 配置不可用的原因（例如文件损坏），可用时为空。 */
  public Optional<String> configurationProblem() {
    return channels.unavailableReason();
  }

  /**
   * 从景点库取详情。库不可用或 id 不在库里都不是错误——购票入口配置本身可以独立存在，
   * 这里失败只意味着结果里少了名称和城市，不该让整个查询变成 503。
   */
  private Optional<Attraction> findAttraction(String id) {
    try {
      return attractions.byId(id);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
  }
}
