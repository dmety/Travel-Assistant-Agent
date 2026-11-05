package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 乐观锁原子性的<b>确定性</b>复现（缺陷 D-6）。
 *
 * <p>为什么不用并发压测：{@link OptimisticLockConcurrencyTest} 那种「打 12 个并发看结果」的写法
 * 依赖线程调度，实测每轮只有 10%~20% 概率暴露问题，冷启动时才会接近 100%。
 * 用它判定修复，会出现「缺陷还在但测试偶然变绿」的假象。
 *
 * <p>这里的做法是<b>把竞态变成必然</b>：包一层 {@link ItineraryStore}，在
 * {@code findById} 返回前等待两个写线程读完快照；如果另一个线程被当前线程
 * 持有的 Service 锁挡住，则允许当前读取继续，兼容锁内读取的正确实现。
 * 等待超时直接使测试失败，不会冒充线程中断，也不会继续使用失效的同步条件。
 *
 * <ul>
 *   <li>未修复实现：版本校验在锁外，两个线程都能通过 → 两个都返回成功 → 用例失败。</li>
 *   <li>修复后（比对版本与写入在同一把锁内完成）：后到者必然发现版本已是 2
 *       → 抛 {@link VersionConflictException} → 用例通过。</li>
 * </ul>
 *
 * <p>判定标准是「恰好一个成功、另一个冲突」，与线程快慢无关，跑多少次结果都一样。
 */
@DisplayName("D-6 乐观锁原子性（确定性复现）")
class OptimisticLockAtomicityTest {

  private static final String OWNER = "alice";

  @Test
  @DisplayName("两个线程基于同一版本更新，必须恰好一个成功、另一个冲突")
  void exactlyOneOfTwoConcurrentUpdatesSucceeds() throws Exception {
    Path dataDir = Files.createTempDirectory("travel-atomicity-");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ReadBarrierStore store = new ReadBarrierStore(mapper, dataDir.toString());
    store.init();

    AccountStore accounts = new AccountStore(mapper, dataDir.toString());
    accounts.init();
    ItineraryService service = new ItineraryService(store, accounts,
        org.mockito.Mockito.mock(com.travelassistant.server.application.collaboration.ChangeLogRecorder.class),
        org.mockito.Mockito.mock(com.travelassistant.server.infrastructure.persistence.DecisionStore.class));
    String id = service.create(OWNER, "并发测试", new Plan("杭州", "初始", "")).id();

    AtomicInteger success = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch startGate = new CountDownLatch(1);

    Runnable update = () -> {
      try {
        if (!startGate.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("写线程启动超时");
        }
        service.updatePlan(id, OWNER, new Plan("杭州", "改了", ""), 1L);
        success.incrementAndGet();
      } catch (VersionConflictException e) {
        conflict.incrementAndGet();
      } catch (Exception e) {
        unexpected.incrementAndGet();
      }
    };

    Thread first = new Thread(update, "writer-1");
    Thread second = new Thread(update, "writer-2");
    store.writers = new Thread[] {first, second};
    first.start();
    second.start();
    startGate.countDown();
    first.join(15_000);
    second.join(15_000);

    assertThat(first.isAlive()).as("第一个写线程应已结束").isFalse();
    assertThat(second.isAlive()).as("第二个写线程应已结束").isFalse();
    assertThat(unexpected.get()).as("不应出现版本冲突以外的异常").isZero();
    assertThat(success.get())
        .as("同一版本被并发更新时只能有一个成功，否则就是静默覆盖（丢失更新）")
        .isEqualTo(1);
    assertThat(conflict.get())
        .as("另一个线程必须收到版本冲突")
        .isEqualTo(1);

    Itinerary finalState = store.findById(id).orElseThrow();
    assertThat(finalState.version()).as("只应递增一次").isEqualTo(2);
  }

  /**
   * 只协调两个指定写线程的首次读取；主线程最终校验不参与。
   * 通过 JVM 报告的锁拥有者识别串行读取，而不是把等待超时当作修复证据。
   */
  private static final class ReadBarrierStore extends ItineraryStore {

    private final CountDownLatch reads = new CountDownLatch(2);
    private final java.util.Set<Thread> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Thread[] writers = new Thread[0];

    private ReadBarrierStore(ObjectMapper mapper, String dataDirectory) {
      super(mapper, dataDirectory);
    }

    @Override
    public Optional<Itinerary> findById(String id) {
      Optional<Itinerary> snapshot = super.findById(id);
      Thread current = Thread.currentThread();
      if (writers.length != 2 || (current != writers[0] && current != writers[1])
          || !seen.add(current)) {
        return snapshot;
      }
      reads.countDown();
      Thread other = current == writers[0] ? writers[1] : writers[0];
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (reads.getCount() != 0) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(other.threadId());
        if (info != null && info.getLockOwnerId() == current.threadId()) {
          return snapshot;
        }
        if (current.isInterrupted() || System.nanoTime() >= deadline) {
          throw new IllegalStateException("未观察到第二次读取或 Service 锁竞争");
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      return snapshot;
    }
  }
}
