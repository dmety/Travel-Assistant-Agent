package com.travelassistant.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.travelassistant.server.TestDataDirs;
import com.travelassistant.server.application.collaboration.PersistenceChangeLogRecorder;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.infrastructure.persistence.CollaborationStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * 修改记录的排序规则：时间倒序，时间相同时按行程版本号倒序。
 *
 * <p>此前这条规则的唯一证据是 {@code CollaborationApiTest} 里那条走 HTTP 的断言，而它
 * 依赖「两次 {@code Instant.now()} 是否刚好落在同一毫秒」——偶发失败时无法判断是排序写错
 * 还是时钟走快了一格，单跑又几乎总是通过。这里把时钟换成 {@link Clock#fixed}，
 * 「同一时刻连续写两次」变成必然发生且可复现的场景，不需要容器、不需要 HTTP。
 *
 * <p>为什么版本号能当第二排序键：行程每次写入都会 +1，同一次请求内产生的记录里，
 * 版本大的必然发生在后。这比再取一次时间戳更可靠——那只是把撞车的概率变小，不是消除。
 */
@DisplayName("修改记录排序（固定时钟）")
class ChangeLogOrderTest {

  private static final String ID = "it-fixed-clock";

  /** 固定时刻：两条记录「同一毫秒」在这里是构造出来的，不是碰运气。 */
  private static final Instant T0 = Instant.parse("2026-11-05T06:00:00Z");

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private Path dir;
  private CollaborationStore store;
  private PersistenceChangeLogRecorder recorder;

  @BeforeEach
  void setUp(TestInfo info) throws Exception {
    // 每个用例一个独立目录：store 会把文件读回内存，共用一个目录就成了「上一个用例的残留」问题
    dir = TestDataDirs.sub("changelog-" + info.getTestMethod().orElseThrow().getName());
    store = new CollaborationStore(mapper, dir.toString());
    store.init();
    recorder = new PersistenceChangeLogRecorder(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("同一时刻的两条记录按版本号倒序：先创建后更新，读出来仍是更新在前")
  void sameInstantRecordsAreOrderedByVersion() {
    // 复现真实顺序：创建行程（v1）先写，随后更新方案（v2）写，两条时间戳完全相同
    recorder.record(ID, "alice", "ITINERARY", "创建行程", 1);
    recorder.record(ID, "alice", "PLAN", "更新时间范围", 2);

    assertThat(store.findChanges(ID, 10))
        .extracting(ChangeEntry::section)
        .containsExactly("PLAN", "ITINERARY");
  }

  @Test
  @DisplayName("顺序由版本号决定，与写入先后无关")
  void sameInstantOrderIgnoresInsertionOrder() {
    recorder.record(ID, "alice", "PLAN", "更新方案", 5);
    recorder.record(ID, "alice", "DAILY", "更新每日安排", 2);

    assertThat(store.findChanges(ID, 10))
        .extracting(ChangeEntry::version)
        .containsExactly(5L, 2L);
  }

  @Test
  @DisplayName("时间不同时按时间倒序，版本号不参与")
  void newestFirstAcrossDifferentInstants() {
    PersistenceChangeLogRecorder later =
        new PersistenceChangeLogRecorder(store, Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC));

    recorder.record(ID, "alice", "ITINERARY", "创建行程", 1);
    later.record(ID, "alice", "PLAN", "更新方案", 2);

    assertThat(store.findChanges(ID, 10))
        .extracting(ChangeEntry::section)
        .containsExactly("PLAN", "ITINERARY");
  }

  @Test
  @DisplayName("limit 取的是最新的若干条，不是最早的")
  void limitKeepsNewest() {
    recorder.record(ID, "alice", "ITINERARY", "创建行程", 1);
    recorder.record(ID, "alice", "PLAN", "更新方案", 2);
    recorder.record(ID, "alice", "DAILY", "更新每日安排", 3);

    assertThat(store.findChanges(ID, 2))
        .extracting(ChangeEntry::section)
        .containsExactly("DAILY", "PLAN");
  }

  @Test
  @DisplayName("超过上限时丢弃最旧的一条，最新的仍在最前")
  void addChangeDropsOldestBeyondLimit() throws Exception {
    int max = CollaborationStore.MAX_CHANGES_PER_ITINERARY;

    // 直接铺满上限后再写一条：用文件预置而不是循环调用 record()，
    // 否则每次 addChange 都要整份回写，触发一次裁剪要写 500 次文件（实测 6 秒）
    List<ChangeEntry> seeded = new ArrayList<>();
    for (int i = 1; i <= max; i++) {
      seeded.add(ChangeEntry.of(ID, "alice", "EXPENSE", "第 " + i + " 次写入", i,
          T0.plusSeconds(i)));
    }
    mapper.writeValue(dir.resolve("changelog.json").toFile(), seeded);

    CollaborationStore full = new CollaborationStore(mapper, dir.toString());
    full.init();
    new PersistenceChangeLogRecorder(full, Clock.fixed(T0.plusSeconds(max + 1), ZoneOffset.UTC))
        .record(ID, "alice", "PLAN", "第 " + (max + 1) + " 次写入", max + 1);

    var changes = full.findChanges(ID, max + 10);
    assertThat(changes).hasSize(max);
    assertThat(changes.get(0).version()).isEqualTo(max + 1);
    // 被丢的是最旧的 v1，v2 顶到了队尾
    assertThat(changes.get(changes.size() - 1).version()).isEqualTo(2);
    assertThat(changes).extracting(ChangeEntry::version).doesNotContain(1L);
  }

  @Test
  @DisplayName("删除行程时只清掉它自己的记录")
  void purgeRemovesOnlyGivenItinerary() {
    recorder.record(ID, "alice", "ITINERARY", "创建行程", 1);
    recorder.record("it-other", "alice", "ITINERARY", "创建另一份行程", 1);

    store.purge(ID);

    assertThat(store.findChanges(ID, 10)).isEmpty();
    assertThat(store.findChanges("it-other", 10)).hasSize(1);
  }
}
