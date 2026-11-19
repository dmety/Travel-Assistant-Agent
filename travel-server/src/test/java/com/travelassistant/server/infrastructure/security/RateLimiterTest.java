package com.travelassistant.server.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F7：滑动窗口限流逻辑，用可推进的假时钟做确定性断言。 */
@DisplayName("滑动窗口限流器")
class RateLimiterTest {

  /** 可手动推进的假时钟。 */
  private static final class MutableClock extends Clock {
    private long millis;

    MutableClock(long start) {
      this.millis = start;
    }

    void advance(long ms) {
      millis += ms;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }
  }

  @Test
  @DisplayName("窗口内达到上限即拒绝，未达上限放行")
  void blocksAfterLimitWithinWindow() {
    RateLimiter limiter = new RateLimiter(new MutableClock(0));
    for (int i = 0; i < 3; i++) {
      assertTrue(limiter.tryAcquire("k", 3, 60_000), "第" + (i + 1) + "次应放行");
    }
    assertFalse(limiter.tryAcquire("k", 3, 60_000), "第4次应拒绝");
  }

  @Test
  @DisplayName("窗口滑过后恢复配额")
  void recoversAfterWindowSlides() {
    MutableClock clock = new MutableClock(0);
    RateLimiter limiter = new RateLimiter(clock);
    for (int i = 0; i < 3; i++) {
      assertTrue(limiter.tryAcquire("k", 3, 60_000));
    }
    assertFalse(limiter.tryAcquire("k", 3, 60_000));
    clock.advance(60_000);
    assertTrue(limiter.tryAcquire("k", 3, 60_000), "旧命中全部过期后应恢复");
  }

  @Test
  @DisplayName("不同 key 互不影响")
  void keysAreIndependent() {
    RateLimiter limiter = new RateLimiter(new MutableClock(0));
    for (int i = 0; i < 3; i++) {
      assertTrue(limiter.tryAcquire("a", 3, 60_000));
    }
    assertFalse(limiter.tryAcquire("a", 3, 60_000));
    assertTrue(limiter.tryAcquire("b", 3, 60_000), "另一个 key 不受影响");
  }
}