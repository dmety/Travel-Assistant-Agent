package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.application.itinerary.VersionConflictException;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 乐观锁原子性的<b>确定性</b>复现（缺陷 D-6）。
 *
 * <p>为什么不用并发压测：{@link OptimisticLockStressTest} 那种「打 12 个并发看结果」的写法
 * 依赖线程调度，实测每轮只有 10%~20% 概率暴露问题，冷启动时才会接近 100%。
 * 用它判定修复，会出现「缺陷还在但测试偶然变绿」的假象。
 *
 * <p>这里的做法是<b>把竞态变成必然</b>：包一层 {@link ItineraryStore}，在
 * {@code findById} 返回前用 {@link CyclicBarrier} 卡住，强制两个线程都读到
 * 版本号 1 的快照之后才继续，再让它们各自执行
 * {@code checkVersion(1) → save}。
 *
 * <ul>
 *   <li>当前实现：版本校验在锁外，两个线程都能通过 → 两个都返回成功 → 用例失败。</li>
 *   <li>修复后（比对版本与写入在同一把锁内完成）：后到者必然发现版本已是 2
 *       → 抛 {@link VersionConflictException} → 用例通过。</li>
 * </ul>
 *
 * <p>判定标准是「恰好一个成功、另一个冲突」，与线程快慢无关，跑多少次结果都一样。
 */
@Disabled("D-6 未修复：启用后必定失败（2026-11-05 连跑 3 次均为 2 个成功、0 个冲突）。修复后删掉本注解即应转绿")
@DisplayName("D-6 乐观锁原子性（确定性复现）")
class OptimisticLockAtomicityTest {

  private static final String OWNER = "alice";

  @Test
  @DisplayName("两个线程基于同一版本更新，必须恰好一个成功、另一个冲突")
  void exactlyOneOfTwoConcurrentUpdatesSucceeds() throws Exception {
    Path dataDir = Files.createTempDirectory("travel-atomicity-");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    CyclicBarrier readBarrier = new CyclicBarrier(2);
    ItineraryStore store = new ReadBarrierStore(mapper, dataDir.toString(), readBarrier);
    store.init();

    ItineraryService service = new ItineraryService(store);
    String id = service.create(OWNER, "并发测试", new Plan("杭州", "初始", "")).id();

    AtomicInteger success = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch startGate = new CountDownLatch(1);

    Runnable update = () -> {
      try {
        startGate.await(5, TimeUnit.SECONDS);
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
    first.start();
    second.start();
    startGate.countDown();
    first.join(15_000);
    second.join(15_000);

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
   * 在 {@code findById} 上加一道栅栏：两个线程都必须先读到旧快照，
   * 才能继续走后面的版本校验。这样无论调度多快，两个线程都会拿着同一个版本号去写。
   */
  private static final class ReadBarrierStore extends ItineraryStore {

    private final CyclicBarrier barrier;

    private ReadBarrierStore(ObjectMapper mapper, String dataDirectory, CyclicBarrier barrier) {
      super(mapper, dataDirectory);
      this.barrier = barrier;
    }

    @Override
    public Optional<Itinerary> findById(String id) {
      Optional<Itinerary> snapshot = super.findById(id);
      try {
        barrier.await(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        // 只有一个线程走到这里时（例如另一个先抛错），栅栏会超时；
        // 不吞掉这个信息，但也无法再用它控制顺序，交给断言判定。
        Thread.currentThread().interrupt();
      }
      return snapshot;
    }
  }
}
