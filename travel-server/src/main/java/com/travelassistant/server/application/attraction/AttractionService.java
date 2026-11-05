package com.travelassistant.server.application.attraction;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 景点查询服务。
 *
 * <p>职责：把结果条数限制、空查询与"库不可用"的判断收敛到一处，
 * 让控制器只负责协议转换。库不可用时抛
 * {@link AttractionUnavailableException}，由统一异常处理转 503 并带原因，
 * 避免把"库没加载"和"确实没有这个景点"混为一谈。
 */
@Service
public class AttractionService {

  /** 默认返回条数。 */
  public static final int DEFAULT_LIMIT = 20;
  /** 单次查询最大条数，防止一次拉全库。 */
  public static final int MAX_LIMIT = 50;

  private final AttractionStore store;

  public AttractionService(AttractionStore store) {
    this.store = store;
  }

  /** 按城市或省份查询。 */
  public AttractionQueryResult byCity(String city, Integer limit) {
    ensureAvailable();
    int effective = normalizeLimit(limit);
    String key = city == null ? "" : city.strip();
    if (key.isEmpty()) {
      return AttractionQueryResult.empty(effective, "请提供城市名称");
    }
    List<Attraction> hit = store.findByCity(key);
    int matched = hit.size();
    if (matched == 0) {
      return AttractionQueryResult.empty(effective,
          "知识库中未找到城市「" + key + "」的景点");
    }
    int returned = Math.min(effective, hit.size());
    return new AttractionQueryResult(matched, returned, effective, null, hit.subList(0, returned));
  }

  /** 按景点名称查询（精确优先，其次名称前缀，最后名称包含）。 */
  public AttractionQueryResult byName(String keyword, Integer limit) {
    ensureAvailable();
    int effective = normalizeLimit(limit);
    String kw = keyword == null ? "" : keyword.strip();
    if (kw.isEmpty()) {
      return AttractionQueryResult.empty(effective, "请提供景点名称");
    }
    List<Attraction> hit = store.findByName(kw);
    int matched = hit.size();
    if (matched == 0) {
      return AttractionQueryResult.empty(effective,
          "知识库中未找到名称包含「" + kw + "」的景点");
    }
    int returned = Math.min(effective, matched);
    return new AttractionQueryResult(matched, returned, effective, null, hit.subList(0, returned));
  }

  /** 按 ID 查详情；库不可用时抛异常，ID 不存在返回空。 */
  public Optional<Attraction> byId(String id) {
    ensureAvailable();
    return store.findById(id);
  }

  /** 知识库状态（含不可用原因），供运维与客户端提示。 */
  public AttractionStore.Status status() {
    return store.status();
  }

  /** 库不可用时统一抛出，携带可读原因。 */
  private void ensureAvailable() {
    AttractionStore.Status s = store.status();
    if (!s.available()) {
      throw new AttractionUnavailableException(
          s.reason() == null ? "景点知识库未加载，查询已停用" : s.reason());
    }
  }

  /** 限制条数：非正数取默认值，超过上限则截到上限。 */
  static int normalizeLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }
}
