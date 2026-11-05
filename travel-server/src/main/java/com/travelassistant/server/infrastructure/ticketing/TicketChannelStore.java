package com.travelassistant.server.infrastructure.ticketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.ticket.TicketChannel;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 官方购票入口配置（只读）。
 *
 * <p>这份配置是**人工维护的白名单**：只收录能确认是官方的入口。三种情况一律视为没有：
 * <ul>
 *   <li>文件不存在或为空 —— 项目默认状态。不内置任何链接，避免把「猜的」包装成「查到的」。</li>
 *   <li>文件解析失败 —— 记 WARN 并停用，**不覆盖原文件**，留下现场供人工修复。</li>
 *   <li>条目地址不是 http/https 绝对地址 —— 丢弃该条并记 WARN，不能让它变成可点击的链接。</li>
 * </ul>
 *
 * <p>全程只读，与景点库同一策略：配置是外部输入，程序不改它。
 */
@Component
public class TicketChannelStore {
  private static final Logger log = LoggerFactory.getLogger(TicketChannelStore.class);

  private final ObjectMapper mapper;
  private final Path file;
  private volatile List<TicketChannel> channels = new ArrayList<>();
  private volatile String unavailableReason;

  public TicketChannelStore(ObjectMapper mapper,
      @Value("${travel.ticket-channel-file:${travel.data-directory}/ticket_channels.json}")
      String path) {
    this.mapper = mapper;
    this.file = Paths.get(path).toAbsolutePath();
  }

  @PostConstruct
  public void init() {
    if (!Files.exists(file)) {
      unavailableReason = "未配置官方购票入口（" + file
          + " 不存在），所有景点都会提示自行前往官方渠道";
      log.info("购票入口配置不存在：{}，购票辅助将以「无经核验入口」状态运行", file);
      return;
    }
    try {
      List<TicketChannel> raw = readChannels(file);
      List<TicketChannel> usable = new ArrayList<>();
      for (TicketChannel channel : raw) {
        if (!channel.usable()) {
          log.warn("购票入口配置被忽略（地址不是 http/https 绝对地址）：{} / {}",
              channel.attractionName(), channel.officialUrl());
          continue;
        }
        usable.add(channel);
      }
      channels = usable;
      unavailableReason = null;
      log.info("已从 {} 加载 {} 条官方购票入口", file, usable.size());
    } catch (IOException | RuntimeException e) {
      channels = List.of();
      unavailableReason = "购票入口配置无法读取（" + e.getMessage() + "），已停用购票辅助";
      log.warn("购票入口配置损坏，已停用且保留原文件：{}", file, e);
    }
  }

  /**
   * 读取配置，两种形态都接受：纯数组，或 {@code {"schema":..., "channels":[...]}}。
   *
   * <p>后者更适合人工维护——可以在文件里写 schema 和说明，而不用挤在数组里。
   */
  private List<TicketChannel> readChannels(Path path) throws IOException {
    var root = mapper.readTree(path.toFile());
    if (root.isArray()) {
      return java.util.Arrays.asList(mapper.treeToValue(root, TicketChannel[].class));
    }
    var array = root.path("channels");
    if (!array.isArray()) {
      throw new IOException("配置既不是数组，也没有 channels 数组");
    }
    return java.util.Arrays.asList(mapper.treeToValue(array, TicketChannel[].class));
  }

  /** 配置不可用的原因；可用时为 {@code null}。 */
  public Optional<String> unavailableReason() {
    return Optional.ofNullable(unavailableReason);
  }

  public List<TicketChannel> findAll() {
    return List.copyOf(channels);
  }

  /** 按景点 id 精确匹配。 */
  public Optional<TicketChannel> findByAttractionId(String attractionId) {
    if (attractionId == null || attractionId.isBlank()) {
      return Optional.empty();
    }
    return channels.stream()
        .filter(c -> attractionId.equals(c.attractionId()))
        .findFirst();
  }

  /**
   * 按名称匹配：先精确、再包含；给出城市时只在该城市内匹配，避免「同名不同城」串到别的景区。
   *
   * <p>返回全部命中项而不是第一个：同名景区可能有多条官方渠道（官网、公众号、小程序），
   * 让用户自己选，比替他猜一个更好。
   */
  public List<TicketChannel> findByName(String name, String city) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    String target = name.strip();
    List<TicketChannel> inCity = channels.stream()
        .filter(c -> matchesCity(c, city))
        .toList();
    List<TicketChannel> exact = inCity.stream()
        .filter(c -> target.equals(c.attractionName()))
        .toList();
    if (!exact.isEmpty()) {
      return exact;
    }
    return inCity.stream()
        .filter(c -> c.attractionName() != null && c.attractionName().contains(target))
        .toList();
  }

  private boolean matchesCity(TicketChannel channel, String city) {
    if (city == null || city.isBlank()) {
      return true;
    }
    String target = city.strip();
    String actual = channel.city();
    return actual != null
        && (actual.equals(target) || actual.startsWith(target) || target.startsWith(actual));
  }
}
