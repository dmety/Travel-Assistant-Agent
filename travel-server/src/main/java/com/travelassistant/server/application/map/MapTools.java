package com.travelassistant.server.application.map;

import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 地图工具层：高德 + 百度地图能力的统一门面，任何 agent Provider 都可以注入调用。
 *
 * <p>存在的理由：地图 API 的正确口径不该在每个 Provider 里各写一遍——
 * 坐标系换算（百度 BD-09LL 统一转 GCJ-02）、跨源去重（同名且 50 米内视为同一实体）、
 * 区县过滤（关键字检索会命中其他省市）、降级姿态（缺密钥或接口失败只记 notice，
 * 绝不编造候选）都在这层消化。Provider 拿到的就是带坐标系标签的干净结果，
 * 只需按自己的契约装 fact。
 *
 * <p>消费方：{@code builtin.inspiration} 的库外景点发现（知识库没收录的区县如
 * 「黄梅」「婺源」）；后续评委 Agent、停车推荐等能力接入地图数据时也走这里，
 * 不再各自直连 {@link AmapClient} / {@link BaiduPlaceClient}。
 *
 * <p>降级边界：两个源都不可用或零命中时返回空列表加如实 notice，
 * 调用方据此转 DEGRADED——本层没有「本地开放数据底账」，不会兜底编造。
 */
@Service
public class MapTools {

  /** 数据来源标识，随每个候选返回；Provider 据此挑契约证据的 sourceKind。 */
  public static final String SOURCE_AMAP = "高德";
  public static final String SOURCE_BAIDU = "百度";

  /**
   * 单个景点候选。字段全部来自地图 POI 原文：等级、票价、开放时间地图检索拿不到，
   * 就是没有——调用方不得用「免费」「全天开放」补齐。
   */
  public record ScenicSpot(String name, String type, String address, String district,
      String tel, Double lat, Double lon, String coordinateSystem, String source) {
  }

  /** 一次检索的结果：候选 + 给人看的 notice（含降级原因），候选可能为空。 */
  public record ScenicResult(String place, List<ScenicSpot> spots, List<String> notices) {
  }

  private static final int FETCH = 25;
  /** 跨源同名 POI 的距离阈值：50 米内视为同一实体（与美食候选同口径）。 */
  private static final double MERGE_METERS = 50.0;
  /** 单次返回上限：灵感场景 8 条够用，具体上限由调用方决定，这里只做硬顶。 */
  private static final int MAX_LIMIT = 12;

  /**
   * 类型噪音词：高德 140000「科教文化服务」大类把学校、职教、剧院都圈了进来，
   * 纯类别码过滤拦不住——与美食候选的连锁品牌名单同思路，按类型文本剔除。
   * 没被剔除的科教条目（博物馆、广场）排到风景名胜后面。
   */
  private static final List<String> NOISE_TYPE_WORDS = List.of(
      "学校", "小学", "中学", "大学", "学院", "幼儿园", "职业技术", "培训");

  private final AmapClient amap;
  private final BaiduPlaceClient baidu;

  public MapTools(AmapClient amap, BaiduPlaceClient baidu) {
    this.amap = amap;
    this.baidu = baidu;
  }

  /**
   * 按「黄梅」「婺源」这类区县/地名检索真实景点。
   *
   * <p>高德主源（关键字 + 景点类别码过滤）+ 百度第二源（region + tag=景点）。
   * 区县名不当高德 {@code city} 参数用——该参数按市级口径解析，区县命中不稳定；
   * 改为关键字检索后在本层按区县名/地址过滤，命中语义确定。
   *
   * @param place 区县或地名原文，如「黄梅」
   * @param limit 返回条数上限，会被夹到 1..12
   */
  public ScenicResult findScenicSpots(String place, int limit) {
    if (place == null || place.isBlank()) {
      return new ScenicResult(place, List.of(), List.of("缺少地名，未检索"));
    }
    String name = place.strip();
    List<String> notices = new ArrayList<>();
    List<ScenicSpot> merged = new ArrayList<>();
    int sourceFailures = 0;

    if (amap.configured()) {
      try {
        for (Place poi : amap.searchScenicSpots(name, null, FETCH)) {
          if (isNoise(poi)) {
            continue;
          }
          if (mentions(poi, name)) {
            merged.add(toSpot(poi, SOURCE_AMAP));
          }
        }
      } catch (RuntimeException e) {
        sourceFailures++;
        notices.add("高德实时检索失败：" + brief(e.getMessage()));
      }
    } else {
      sourceFailures++;
      notices.add("高德密钥未配置，实时景点检索缺少主数据源");
    }

    if (baidu.configured()) {
      try {
        for (Place poi : baidu.searchScenicSpots(name, FETCH)) {
          if (isNoise(poi)) {
            continue;
          }
          if (mentions(poi, name)) {
            merged.add(toSpot(poi, SOURCE_BAIDU));
          }
        }
      } catch (RuntimeException e) {
        sourceFailures++;
        notices.add("百度实时检索失败：" + brief(e.getMessage()));
      }
    } else {
      sourceFailures++;
      notices.add("百度密钥未配置");
    }

    // 风景名胜排前、科教文化（博物馆、广场）殿后：稳定排序，同一请求响应可复现
    merged.sort(java.util.Comparator.comparing(
        (ScenicSpot s) -> s.type() != null && s.type().contains("风景名胜") ? 0 : 1));
    List<ScenicSpot> unique = dedupe(merged);
    int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
    List<ScenicSpot> spots = unique.size() <= capped ? unique : unique.subList(0, capped);
    if (spots.size() < unique.size()) {
      notices.add("候选超过上限，按固定顺序截取到 " + capped + " 条");
    }
    if (!spots.isEmpty() && sourceFailures > 0) {
      notices.add("部分数据源不可用，候选覆盖可能不完整");
    }
    if (spots.isEmpty()) {
      notices.add("未检索到「" + name + "」的景点候选，未编造");
    }
    return new ScenicResult(name, List.copyOf(spots), List.copyOf(notices));
  }

  /** 类型或名称里命中噪音词（学校、培训等）的直接剔除；都缺失不算噪音。 */
  private static boolean isNoise(Place poi) {
    return hitsNoise(poi.type()) || hitsNoise(poi.name());
  }

  private static boolean hitsNoise(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    for (String word : NOISE_TYPE_WORDS) {
      if (text.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /**
   * POI 是否落在目标地名内：优先看区县名与城市名；地址匹配只在区县缺失时兜底——
   * 否则「黄梅公司大厦」这种名字里带地名的异地企业会混进候选。
   */
  private static boolean mentions(Place poi, String name) {
    if (contains(poi.district(), name) || contains(poi.city(), name)) {
      return true;
    }
    return (poi.district() == null || poi.district().isBlank())
        && contains(poi.address(), name);
  }

  private static boolean contains(String text, String name) {
    return text != null && text.contains(name);
  }

  private static ScenicSpot toSpot(Place poi, String source) {
    return new ScenicSpot(poi.name(), poi.type(), poi.address(), poi.district(),
        poi.tel(), poi.lat(), poi.lon(), poi.coordinateSystem(), source);
  }

  /** 同来源按名称去重；跨来源「同名且 50 米内」去重。名称归一化去掉空白与大小写。 */
  private static List<ScenicSpot> dedupe(List<ScenicSpot> spots) {
    List<ScenicSpot> result = new ArrayList<>();
    for (ScenicSpot spot : spots) {
      if (spot.name() == null || spot.name().isBlank()) {
        continue;
      }
      boolean duplicate = false;
      for (ScenicSpot kept : result) {
        if (!normalize(spot.name()).equals(normalize(kept.name()))) {
          continue;
        }
        if (spot.source().equals(kept.source())) {
          duplicate = true;
          break;
        }
        if (spot.lat() != null && spot.lon() != null
            && kept.lat() != null && kept.lon() != null
            && CoordinateTransforms.haversineMeters(spot.lat(), spot.lon(),
                kept.lat(), kept.lon()) < MERGE_METERS) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        result.add(spot);
      }
    }
    return result;
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  private static String brief(String message) {
    if (message == null) {
      return "无详细信息";
    }
    return message.length() > 120 ? message.substring(0, 120) + "…" : message;
  }
}
