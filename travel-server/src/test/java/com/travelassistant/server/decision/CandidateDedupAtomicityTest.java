package com.travelassistant.server.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.decision.CandidateService;
import com.travelassistant.server.application.decision.InvalidCandidateException;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.CandidateKind;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import com.travelassistant.server.infrastructure.persistence.DecisionStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 候选去重的<b>确定性</b>原子性复现（缺陷 F3）。
 *
 * <p>{@code CandidateService.add} 的「查重 → 添加」此前不在同一把锁内：两个线程可同时读到
 * 空候选列表、同时通过查重、各自写入，于是同名同类型候选出现两条（TOCTOU）。
 *
 * <p>与 {@code OptimisticLockAtomicityTest} 同一手法——把竞态变成必然：包一层
 * {@link DecisionStore}，在 {@code findCandidates} 返回前等两个写线程都读完快照；若另一个线程
 * 被当前线程持有的 Service 锁挡住，则放行当前读取（兼容锁内查重的正确实现）。等待超时直接失败，
 * 不冒充线程中断。
 *
 * <ul>
 *   <li>未修复：查重在锁外，两线程都读到空 → 都添加 → 成功 2、候选 2 条 → 用例失败。</li>
 *   <li>修复后（查重与添加在同一把行程锁内）：后到者必然读到已有候选 → 抛
 *       {@link InvalidCandidateException} → 恰好一个成功、候选 1 条 → 用例通过。</li>
 * </ul>
 *
 * <p>判定与线程快慢无关，跑多少次结果都一样。
 */
@DisplayName("F3 候选去重原子性（确定性复现）")
class CandidateDedupAtomicityTest {

  private static final String OWNER = "alice";

  @Test
  @DisplayName("两个线程添加同名候选，必须恰好一个成功、另一个被去重拒绝")
  void exactlyOneOfTwoConcurrentAddsSucceeds() throws Exception {
    Path dataDir = com.travelassistant.server.TestDataDirs.sub("candidate-dedup");
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ReadBarrierDecisionStore decisions = new ReadBarrierDecisionStore(mapper, dataDir.toString());
    decisions.init();
    ItineraryStore itineraries = new ItineraryStore(mapper, dataDir.toString());
    itineraries.init();
    AccountStore accounts = new AccountStore(mapper, dataDir.toString());
    accounts.init();
    ItineraryService itineraryService = new ItineraryService(itineraries, accounts,
        org.mockito.Mockito.mock(
            com.travelassistant.server.application.collaboration.ChangeLogRecorder.class),
        org.mockito.Mockito.mock(DecisionStore.class));
    CandidateService service = new CandidateService(itineraryService, decisions);
    String id = itineraryService.create(OWNER, "候选并发", new Plan("杭州", "初始", "")).id();

    AtomicInteger success = new AtomicInteger();
    AtomicInteger duplicate = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch startGate = new CountDownLatch(1);

    Runnable add = () -> {
      try {
        if (!startGate.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("写线程启动超时");
        }
        service.add(id, OWNER, CandidateKind.ATTRACTION, "西湖", "杭州", null, "");
        success.incrementAndGet();
      } catch (InvalidCandidateException e) {
        duplicate.incrementAndGet();
      } catch (Exception e) {
        unexpected.incrementAndGet();
      }
    };

    Thread first = new Thread(add, "candidate-writer-1");
    Thread second = new Thread(add, "candidate-writer-2");
    decisions.writers = new Thread[] {first, second};
    first.start();
    second.start();
    startGate.countDown();
    first.join(15_000);
    second.join(15_000);

    assertThat(first.isAlive()).as("第一个写线程应已结束").isFalse();
    assertThat(second.isAlive()).as("第二个写线程应已结束").isFalse();
    assertThat(unexpected.get()).as("不应出现去重以外的异常").isZero();
    assertThat(success.get())
        .as("同名候选被并发添加时只能有一个成功，否则就是重复候选（TOCTOU）")
        .isEqualTo(1);
    assertThat(duplicate.get()).as("另一个线程必须被去重拒绝").isEqualTo(1);
    assertThat(decisions.findCandidates(id))
        .as("最终只应落一条候选")
        .hasSize(1);
  }

  /**
   * 只协调两个指定写线程的首次查重读取；主线程最终校验不参与。
   * 通过 JVM 报告的锁拥有者识别串行读取，而不是把等待超时当作修复证据。
   */
  private static final class ReadBarrierDecisionStore extends DecisionStore {

    private final CountDownLatch reads = new CountDownLatch(2);
    private final java.util.Set<Thread> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Thread[] writers = new Thread[0];

    private ReadBarrierDecisionStore(ObjectMapper mapper, String dataDirectory) {
      super(mapper, dataDirectory);
    }

    @Override
    public List<Candidate> findCandidates(String itineraryId) {
      List<Candidate> snapshot = super.findCandidates(itineraryId);
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