package com.travelassistant.server.application.collaboration;

import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.infrastructure.persistence.CollaborationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 修改记录的落盘实现。
 *
 * <p>刻意吞掉异常并记 WARN：记录是旁路信息，写失败不应让用户的一次正常编辑失败。
 * 真正的数据（行程本身）已经原子落盘，这里丢了只是少一条流水。
 */
@Component
public class PersistenceChangeLogRecorder implements ChangeLogRecorder {
  private static final Logger log = LoggerFactory.getLogger(PersistenceChangeLogRecorder.class);

  private final CollaborationStore store;

  public PersistenceChangeLogRecorder(CollaborationStore store) {
    this.store = store;
  }

  @Override
  public void record(String itineraryId, String actor, String section, String summary,
      long version) {
    try {
      store.addChange(ChangeEntry.of(itineraryId, actor, section, summary, version));
    } catch (RuntimeException e) {
      log.warn("写入修改记录失败（行程 {}，板块 {}）：{}", itineraryId, section, e.getMessage());
    }
  }

  @Override
  public void purge(String itineraryId) {
    try {
      store.purge(itineraryId);
    } catch (RuntimeException e) {
      log.warn("清理修改记录失败（行程 {}）：{}", itineraryId, e.getMessage());
    }
  }
}
