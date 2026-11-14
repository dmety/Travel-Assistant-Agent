package com.travelassistant.server.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 管理审计底账：倒序、容量上限、字段不脱敏之外的信息都如实记录。 */
@DisplayName("管理审计底账（内存环形缓冲）")
class AdminAuditLogTest {

  @Test
  @DisplayName("recent 按时间倒序返回最新一条，字段完整")
  void recentIsNewestFirst() {
    AdminAuditLog log = new AdminAuditLog();
    log.record("reload", "-", "10.0.0.1", true, "registry v1");
    log.record("probe", "food.recommender", "10.0.0.2", false, "status=REJECTED");

    assertThat(log.size()).isEqualTo(2);
    var recent = log.recent(10);
    assertThat(recent).hasSize(2);
    assertThat(recent.get(0).action()).isEqualTo("probe");
    assertThat(recent.get(0).target()).isEqualTo("food.recommender");
    assertThat(recent.get(0).remoteAddr()).isEqualTo("10.0.0.2");
    assertThat(recent.get(0).success()).isFalse();
    assertThat(recent.get(0).detail()).isEqualTo("status=REJECTED");
    assertThat(recent.get(0).time()).isNotNull();
    assertThat(recent.get(1).action()).isEqualTo("reload");
  }

  @Test
  @DisplayName("limit 生效，且不超过已有条数")
  void limitIsRespected() {
    AdminAuditLog log = new AdminAuditLog();
    log.record("a", "-", "127.0.0.1", true, "");
    log.record("b", "-", "127.0.0.1", true, "");
    log.record("c", "-", "127.0.0.1", true, "");

    assertThat(log.recent(2)).hasSize(2);
    assertThat(log.recent(0)).isEmpty();
    assertThat(log.recent(99)).hasSize(3);
  }

  @Test
  @DisplayName("超过 500 条上限时丢弃最旧，始终保留最近 500 条")
  void ringBufferCapsAt500() {
    AdminAuditLog log = new AdminAuditLog();
    for (int i = 0; i < 520; i++) {
      log.record("op-" + i, "-", "127.0.0.1", true, "");
    }
    assertThat(log.size()).isEqualTo(500);
    var recent = log.recent(500);
    assertThat(recent).hasSize(500);
    // 最新一条是最后写入的 519，最旧保留的是 20（前面 0..19 已被丢弃）
    assertThat(recent.get(0).action()).isEqualTo("op-519");
    assertThat(recent.get(499).action()).isEqualTo("op-20");
  }
}
