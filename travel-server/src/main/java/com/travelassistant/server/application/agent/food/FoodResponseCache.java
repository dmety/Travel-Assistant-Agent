package com.travelassistant.server.application.agent.food;

import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.Fact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 按 {@code runId} 的 24 小时幂等缓存。
 *
 * <p>解决的是「同一次请求被重发」的问题：前端重试、网关超时重放、用户连点两下，
 * 都不该让地图接口被再打一遍——高德与百度的免费额度没有本地限流兜底，
 * 重复召回既浪费额度，也会让同一 {@code runId} 的两次响应给出不同的候选，
 * 而契约要求同一 {@code runId} 可重放。
 *
 * <p><b>不是数据集缓存</b>。这里只按 {@code runId} 存整份响应信封，作用域是单次运行：
 * 不参与其他 {@code runId} 的召回，不做跨请求聚合，不落盘，24 小时后失效。
 * 地图服务条款禁止的是把 POI 字段缓存成可复用的数据集，不是禁止同一次调用重放。
 *
 * <p>命中缓存会在 {@code notices} 里明说，不伪装成一次新的实时查询。
 */
@Component
public class FoodResponseCache {

  /** 设计文档口径：24 小时。 */
  static final long TTL_MS = 24L * 60L * 60L * 1000L;
  /** 条目上限：单机常驻内存不能无限涨。超出后先清过期项，仍超就整体丢弃重来。 */
  private static final int MAX_ENTRIES = 512;

  private record Entry(String fingerprint, AgentResult result, long storedAt) {
  }

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /**
   * 取缓存。{@code fingerprint} 不一致时视为未命中——同一个 {@code runId} 换了参数，
   * 说明不是重放而是新请求，不能拿旧结果糊弄。
   */
  public AgentResult get(String runId, String fingerprint) {
    if (runId == null || runId.isBlank() || fingerprint == null) {
      return null;
    }
    Entry entry = entries.get(runId);
    if (entry == null) {
      return null;
    }
    if (System.currentTimeMillis() - entry.storedAt() > TTL_MS) {
      entries.remove(runId);
      return null;
    }
    if (!fingerprint.equals(entry.fingerprint())) {
      return null;
    }
    return withNotice(entry.result());
  }

  /** 写入缓存。{@code runId} 为空或结果为空时不写，宁可少缓存也不缓存半成品。 */
  public void put(String runId, String fingerprint, AgentResult result) {
    if (runId == null || runId.isBlank() || fingerprint == null || result == null) {
      return;
    }
    if (entries.size() >= MAX_ENTRIES) {
      evictExpired();
      if (entries.size() >= MAX_ENTRIES) {
        entries.clear();
      }
    }
    entries.put(runId, new Entry(fingerprint, result, System.currentTimeMillis()));
  }

  /** 当前存活条目数，供探针与调试查看。 */
  public int size() {
    evictExpired();
    return entries.size();
  }

  private void evictExpired() {
    long now = System.currentTimeMillis();
    entries.entrySet().removeIf(e -> now - e.getValue().storedAt() > TTL_MS);
  }

  /** 命中的结果加一句说明：用户与调用方都要知道这次没有重新查地图。 */
  private static AgentResult withNotice(AgentResult cached) {
    List<String> notices = new ArrayList<>(cached.notices() == null ? List.of() : cached.notices());
    notices.add("同一 runId 的重复请求，返回 24 小时内缓存的结果，本次未重新请求地图接口");
    List<String> capped = notices.size() > 20 ? notices.subList(0, 20) : notices;
    List<Fact> facts = cached.facts() == null ? List.of() : cached.facts();
    return new AgentResult(cached.status(), facts, cached.advice(),
        cached.unknownFields() == null ? List.of() : cached.unknownFields(),
        List.copyOf(capped), cached.usage());
  }
}
