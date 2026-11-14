package com.travelassistant.server.infrastructure.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * 管理操作审计底账（内存态，进程级）。
 *
 * <p>只记录管理动作的发生与结果，不记录令牌原文、不记录任何密钥或用户凭据——
 * 审计日志一旦落进敏感信息，反而成了攻击者的蜜罐。来源 IP、动作、目标、结果、摘要足够复盘。
 *
 * <p>与 {@code DeepSeekClient.usageSnapshot()} 同口径：进程生命周期内的环形缓冲，
 * 不落盘、重启即清空。管理审计不是业务数据，不应随备份扩散；若将来需要长期留存，
 * 再单独接持久化通道（且要脱敏），而不是默认写库。
 */
@Component
public class AdminAuditLog {

  /** 环形缓冲上限：超过后丢弃最旧的。管理动作频次低，500 条覆盖数天足够。 */
  private static final int CAPACITY = 500;

  private final List<Entry> entries = new CopyOnWriteArrayList<>();

  /**
   * 记一条审计项。
   *
   * @param action     动作：reload / probe / shadow / activate 等
   * @param target     作用对象：Provider id，或 "-" 表示整张注册表
   * @param remoteAddr 来源 IP（来自请求，非凭据）
   * @param success    是否成功
   * @param detail     摘要（如新状态、失败原因）；不得包含密钥或令牌
   */
  public void record(String action, String target, String remoteAddr, boolean success, String detail) {
    entries.add(new Entry(Instant.now(), action, target, remoteAddr, success, detail));
    while (entries.size() > CAPACITY) {
      entries.remove(0);
    }
  }

  /** 最近 {@code limit} 条，按时间倒序（最新在前）。 */
  public List<Entry> recent(int limit) {
    List<Entry> snapshot = new ArrayList<>(entries);
    Collections.reverse(snapshot);
    int n = Math.max(0, Math.min(limit, snapshot.size()));
    return snapshot.subList(0, n);
  }

  public int size() {
    return entries.size();
  }

  public record Entry(Instant time, String action, String target,
      String remoteAddr, boolean success, String detail) {
  }
}
