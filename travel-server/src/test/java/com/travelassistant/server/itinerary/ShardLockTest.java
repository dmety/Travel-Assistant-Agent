package com.travelassistant.server.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelassistant.server.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/**
 * 写锁按行程 id 分片（T-11）。
 *
 * <p>这里**不做时间断言**。分片锁的收益是「不同行程不互相排队」，但单次写入只有几毫秒，
 * 在共享机器上「并行比串行快」的阈值随时会被 GC、磁盘抖动吃掉，写成时间比较必然是 flaky。
 * 时间断言换来的是误报，不是保护。
 *
 * <p>能稳定验证的是另一件事：**分片之后数据不能串**。八个行程并发写各自的方案，
 * 每一条都要落在自己的行程上、版本各自前进一步。如果锁的粒度或键算错了
 * （比如所有 id 哈希到了同一把锁之外还共享了某个可变状态），这里会立刻看出串写。
 */
@DisplayName("分片写锁：不同行程互不干扰")
class ShardLockTest extends ApiTestBase {

  private static final int ITINERARIES = 8;
  private static final int ROUNDS = 3;

  @Test
  @DisplayName("八个行程并发写各自方案：版本各自前进，内容不串")
  void differentItinerariesDoNotMix() throws Exception {
    MockHttpSession owner = registerAndLogin();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < ITINERARIES; i++) {
      ids.add(createItinerary(owner, "并发行程 " + i));
    }

    ExecutorService pool = Executors.newFixedThreadPool(ITINERARIES);
    try {
      for (int round = 1; round <= ROUNDS; round++) {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < ITINERARIES; i++) {
          String id = ids.get(i);
          int index = i;
          int version = round; // 创建时 v1，因此第 1 轮提交 version=1
          String summary = "r" + round + "-i" + index;
          futures.add(pool.submit(() -> {
            startGate.await(5, TimeUnit.SECONDS);
            return mvc.perform(put("/api/itineraries/" + id + "/plan")
                    .session(owner)
                    .param("version", Integer.toString(version))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"destination\":\"Hangzhou\",\"summary\":\"" + summary + "\"}"))
                .andReturn().getResponse().getStatus();
          }));
        }
        startGate.countDown();
        for (Future<Integer> future : futures) {
          assertThat(future.get(20, TimeUnit.SECONDS))
              .as("第 %d 轮：不同行程之间不该有版本冲突", round)
              .isEqualTo(200);
        }
      }
    } finally {
      pool.shutdownNow();
    }

    for (int i = 0; i < ITINERARIES; i++) {
      JsonNode detail = json.readTree(mvc.perform(get("/api/itineraries/" + ids.get(i)).session(owner))
          .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertThat(detail.get("version").asLong())
          .as("行程 %d 的版本应只随自己的写入前进", i)
          .isEqualTo(1 + ROUNDS);
      assertThat(detail.at("/plan/summary").asText())
          .as("行程 %d 读到了别人的内容", i)
          .isEqualTo("r" + ROUNDS + "-i" + i);
    }
  }
}
