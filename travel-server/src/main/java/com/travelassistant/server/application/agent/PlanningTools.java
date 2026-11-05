package com.travelassistant.server.application.agent;

import com.travelassistant.server.application.attraction.AttractionService;
import com.travelassistant.server.application.location.AmapUnavailableException;
import com.travelassistant.server.application.location.LocationService;
import com.travelassistant.server.application.ticket.TicketService;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 规划可用的工具集（V2 第 2 节）。
 *
 * <p>这里是「Agent 能查到什么」的唯一入口。所有工具都是**确定性 Java 调用**，
 * 模型不直接读文件、不发外部请求，只能通过这里拿到已经校验过的结果——
 * 这保证了模型即使说错话，也编不出一个库里没有的景点 id。
 *
 * <p>每个工具都把失败转成 {@link ToolOutcome}，不向上抛异常：一次天气查询失败
 * 不该让整份草案生成失败，用户仍然需要看到候选景点。
 */
@Component
public class PlanningTools {
  private static final Logger log = LoggerFactory.getLogger(PlanningTools.class);

  /** 单次规划最多返回的候选数，防止把全库塞进结果。 */
  public static final int MAX_CANDIDATES = 10;

  private final AttractionService attractions;
  private final LocationService locations;
  private final TicketService tickets;

  public PlanningTools(AttractionService attractions, LocationService locations,
      TicketService tickets) {
    this.attractions = attractions;
    this.locations = locations;
    this.tickets = tickets;
  }

  /**
   * 按城市检索候选景点。
   *
   * <p>先取足量结果再排序，而不是直接用库里出现的前几条：知识库按名称排列，
   * 直接截断会得到「世界公园、世界花卉大观园」这类字母序靠前的普通景点，
   * 把故宫、颐和园挤掉。排序口径是「等级 → 评分 → 名称」，
   * 只是把更可能值得去的地方排前面，不代表推荐结论。
   */
  public ToolOutcome<List<Attraction>> searchByCity(String city, int limit) {
    try {
      List<Attraction> all = attractions.byCity(city, AttractionService.MAX_LIMIT).items();
      List<Attraction> ranked = rank(all).stream().limit(limit).toList();
      return ToolOutcome.ok(ranked);
    } catch (RuntimeException e) {
      return ToolOutcome.failed("景点检索不可用：" + e.getMessage());
    }
  }

  /** 按名称/关键词检索候选景点（同样排序后截断）。 */
  public ToolOutcome<List<Attraction>> searchByName(String keyword, int limit) {
    try {
      List<Attraction> all = attractions.byName(keyword, AttractionService.MAX_LIMIT).items();
      return ToolOutcome.ok(rank(all).stream().limit(limit).toList());
    } catch (RuntimeException e) {
      return ToolOutcome.failed("景点检索不可用：" + e.getMessage());
    }
  }

  /** 等级分：5A 3 分、4A 2 分、3A 1 分，其余 0 分。 */
  private int levelScore(Attraction attraction) {
    String level = attraction.level();
    if (level == null) {
      return 0;
    }
    if (level.contains("5A")) {
      return 3;
    }
    if (level.contains("4A")) {
      return 2;
    }
    if (level.contains("3A")) {
      return 1;
    }
    return 0;
  }

  /** 评分转数值，无法解析按 -1 处理（排在有效评分之后）。 */
  private double ratingValue(String rating) {
    if (rating == null || rating.isBlank()) {
      return -1;
    }
    try {
      return Double.parseDouble(rating.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private List<Attraction> rank(List<Attraction> items) {
    return items.stream()
        .sorted(java.util.Comparator
            .comparingInt(this::levelScore).reversed()
            .thenComparing(java.util.Comparator
                .comparingDouble((Attraction a) -> ratingValue(a.rating())).reversed())
            .thenComparing(a -> a.name() == null ? "" : a.name()))
        .toList();
  }

  /** 目的地天气预报。未配置高德密钥时返回失败原因，不抛异常。 */
  public ToolOutcome<WeatherResult> weather(String city) {
    try {
      return ToolOutcome.ok(locations.weather(city));
    } catch (AmapUnavailableException e) {
      return ToolOutcome.failed(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("天气查询异常：{}", e.getMessage());
      return ToolOutcome.failed("天气查询失败：" + e.getMessage());
    }
  }

  /** 景点购票辅助。 */
  public ToolOutcome<TicketAdvice> ticket(String name, String city) {
    try {
      return ToolOutcome.ok(tickets.lookup(null, name, city));
    } catch (RuntimeException e) {
      return ToolOutcome.failed("购票信息查询失败：" + e.getMessage());
    }
  }

  /** 高德是否已配置，供调用方提前决定要不要查天气。 */
  public boolean locationEnabled() {
    return Boolean.TRUE.equals(locations.status().get("configured"));
  }
}
