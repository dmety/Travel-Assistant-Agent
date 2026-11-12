package com.travelassistant.server.application.agent.orchestration;

import com.travelassistant.server.application.agent.spi.Evidence;
import com.travelassistant.server.application.agent.spi.Fact;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 服务端下发锚点：把景点索引里的坐标转成带证据的 {@code ATTRACTION} fact。
 *
 * <p>Provider 不读仓库文件，也不自己猜坐标系。离线索引里 WGS-84 与 GCJ-02 混存，
 * 没有 {@code coordinateSystem} 标签的点一律跳过，绝不按城市中心或某个默认值兜底。
 * 契约的 {@code verificationStatus} 枚举只允许 unverified / pending，本地索引同样标
 * unverified，不伪装成官方核验数据。
 */
@Component
public class AnchorFactService {

  private final AttractionStore store;

  public AnchorFactService(AttractionStore store) {
    this.store = store;
  }

  /**
   * @param notices       追加锚点相关的提示，调用方负责按契约上限截断
   * @param unknownFields 追加缺失项，同样由调用方截断
   */
  public List<Fact> build(String intent, Map<String, Object> context,
      List<String> notices, List<String> unknownFields) {
    if (!"FOOD_RECOMMEND".equals(intent)) {
      return List.of();
    }
    List<Fact> facts = new ArrayList<>();
    int skipped = 0;

    if (context.get("attractionIds") instanceof List<?> ids) {
      for (Object raw : ids) {
        if (!(raw instanceof String id) || id.isBlank()) {
          continue;
        }
        Optional<Attraction> found = store.findById(id);
        if (found.isEmpty()) {
          skipped++;
          continue;
        }
        Attraction attraction = found.get();
        if (attraction.lat() == null || attraction.lon() == null
            || attraction.coordinateSystem() == null
            || attraction.coordinateSystem().isBlank()) {
          skipped++;
          continue;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("attractionId", attraction.id());
        value.put("name", attraction.name());
        value.put("city", attraction.city());
        value.put("lat", attraction.lat());
        value.put("lon", attraction.lon());
        value.put("coordinateSystem", attraction.coordinateSystem());
        facts.add(new Fact(factId("anchor:" + attraction.id()), "ATTRACTION", value,
            new Evidence("KB", attraction.id(), Evidence.UNVERIFIED, Instant.now(),
                "本地景点索引锚点坐标，未核验")));
      }
    }

    // 显式锚点用于前端实时定位：仍必须自带坐标系标签，否则跳过。
    if (context.get("anchor") instanceof Map<?, ?> raw) {
      @SuppressWarnings("unchecked")
      Map<String, Object> anchor = (Map<String, Object>) raw;
      Double lat = number(anchor.get("lat"));
      Double lon = number(anchor.get("lon"));
      String crs = anchor.get("coordinateSystem") instanceof String s ? s : null;
      if (lat == null || lon == null || crs == null || crs.isBlank()) {
        skipped++;
      } else {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("lat", lat);
        value.put("lon", lon);
        value.put("coordinateSystem", crs);
        facts.add(new Fact(factId("anchor:explicit"), "ATTRACTION", value,
            new Evidence("USER_INPUT", "explicit", Evidence.UNVERIFIED, Instant.now(),
                "用户提供的实时位置，未核验")));
      }
    }

    if (facts.isEmpty()) {
      unknownFields.add("anchor");
      notices.add("缺少可用锚点，请选择当日景点或提供带坐标系标签的位置；未默认按城市中心计算");
    } else if (skipped > 0) {
      notices.add("部分锚点缺少坐标或坐标系标签，已跳过，不猜测坐标系");
    }
    return List.copyOf(facts);
  }

  private static String factId(String raw) {
    return raw.length() <= 64 ? raw : raw.substring(0, 64);
  }

  private static Double number(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    return null;
  }
}
