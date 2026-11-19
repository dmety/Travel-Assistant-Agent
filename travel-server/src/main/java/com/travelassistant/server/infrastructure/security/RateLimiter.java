package com.travelassistant.server.infrastructure.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滑动窗口限流器（F7）：按 key 统计最近 {@code windowMs} 内的尝试次数，达到 {@code limit} 即拒绝。
 *
 * <p>内存计数、无需引依赖，重启归零；key 形如 {@code login|ip|username}，按账号 + IP 双维度收敛，
 * 足以挡住对单个账号的在线暴力破解。时钟可注入，便于确定性单测。
 *
 * <p>这是单实例内存方案：多实例部署需换成共享存储（Redis 等），分布式/轮换 IP 的攻击不在本层职责内。
 */
public class RateLimiter {

  /** 机会式清理阈值：key 数超过它就回收过期窗口，避免长期运行内存无界增长。 */
  private static final int SWEEP_THRESHOLD = 1024;
  /** 清理时使用的最大窗口：超过此时长无命中的 key 视为过期。 */
  private static final long MAX_WINDOW_MS = 60_000;

  private final Clock clock;
  private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

  public RateLimiter() {
    this(Clock.systemUTC());
  }

  public RateLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * 尝试为 key 取得一次配额。
   *
   * @return true 表示放行（已计入一次）；false 表示窗口内已达上限，应拒绝。
   */
  public boolean tryAcquire(String key, int limit, long windowMs) {
    long now = clock.millis();
    if (hits.size() > SWEEP_THRESHOLD) {
      sweep(now);
    }
    Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && now - window.peekFirst() >= windowMs) {
        window.pollFirst();
      }
      if (window.size() >= limit) {
        return false;
      }
      window.addLast(now);
      return true;
    }
  }

  /** 回收已过期（超过最大窗口无命中）的 key，限制内存占用。 */
  private void sweep(long now) {
    Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
    while (it.hasNext()) {
      Deque<Long> window = it.next().getValue();
      synchronized (window) {
        while (!window.isEmpty() && now - window.peekFirst() >= MAX_WINDOW_MS) {
          window.pollFirst();
        }
        if (window.isEmpty()) {
          it.remove();
        }
      }
    }
  }
}