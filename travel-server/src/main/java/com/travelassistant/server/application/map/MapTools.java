package com.travelassistant.server.application.map;

import com.travelassistant.server.application.place.RatingOrder;
import com.travelassistant.server.application.place.ReviewLinks;
import com.travelassistant.server.domain.location.Place;
import com.travelassistant.server.infrastructure.amap.AmapClient;
import com.travelassistant.server.infrastructure.baidu.BaiduPlaceClient;
import com.travelassistant.server.infrastructure.geo.CoordinateTransforms;
import java.util.ArrayList;
import java.util.Comparator;
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
   * 就是没有——调用方不得用「免费」「全天开放」补齐。评分只有 {@code extensions=all}
   * / {@code scope=2} 才返回，缺失为 null，同样不补默认值。
   */
  public record ScenicSpot(String name, String type, String address, String district,
      String tel, Double lat, Double lon, String coordinateSystem, String source,
      Double rating, Integer commentCount, String reviewUrl, String reviewSource) {
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

  /** 评分先验：没有评价数时把分数往这个基准收缩，避免「1 条评价的 5.0」压过老牌景区。 */
  private static final double PRIOR_SCORE = 4.0;
  private static final int PRIOR_WEIGHT = 30;
  /** 评价数缺失（高德 place/text 不提供）时按这个样本量参与排序；只影响次序，不展示成数据。 */
  private static final int UNKNOWN_SAMPLE = 15;

  /**
   * 好评高的放前面，但评分要带样本量：实测黄梅有一批 5.0 分、只有 6～11 条评价的冷门小庙，
   * 纯按评分会把 4.6 分 / 53 条评价的四祖寺挤出候选。这里按评价数把评分向 4.0 先验收缩，
   * 卡片仍展示原始评分与评价数，用户能自己判断；无评分的候选排最后。
   */
  private static double rankScore(ScenicSpot s) {
    if (s.rating() == null) {
      return -1;
    }
    int n = s.commentCount() == null ? UNKNOWN_SAMPLE : Math.max(s.commentCount(), 0);
    return (s.rating() * n + PRIOR_SCORE * PRIOR_WEIGHT) / (n + PRIOR_WEIGHT);
  }

  private static final Comparator<ScenicSpot> RANK = Comparator
      .comparingDouble((ScenicSpot s) -> -rankScore(s))
      .thenComparingInt(s -> -(s.commentCount() == null ? 0 : s.commentCount()))
      .thenComparingInt(s -> s.type() != null && s.type().contains("风景名胜") ? 0 : 1);

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

    // 好评高的放前面；无评分的候选仍按风景名胜优先，稳定且可复现
    merged.sort(RANK);
    List<ScenicSpot> unique = dedupe(merged);
    int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
    List<ScenicSpot> spots = unique.size() <= capped ? unique : unique.subList(0, capped);
    if (spots.size() < unique.size()) {
      notices.add("候选超过上限，按评分（含评价数可信度）与类型顺序截取到 " + capped + " 条");
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
  /**
   * 类型或名称命中噪音词就丢弃。
   *
   * <p>名称这一层统一走 {@link RatingOrder#isNonTourismName}，与知识库候选同一口径：
   * 站前广场、候车室、停车场这类通行空间不该出现在景点候选里。原先这里只看学校词，
   * 于是「丽江站站前广场」（评分 5.0）在知识库路径被挡住、实时检索又能钻进来。
   */
  private static boolean isNoise(Place poi) {
    return hitsNoise(poi.type()) || RatingOrder.isNonTourismName(poi.name());
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
   *
   * <p>另一条通道是「名称命中 + 风景类类型」：西岳华山在渭南市华阴市，区县/城市/地址
   * 都不含「华山」二字，没有这条会漏掉最负盛名的目标本体；而同名企业因类型不是
   * 风景类仍会被拦住。
   */
  private static boolean mentions(Place poi, String name) {
    if (contains(poi.district(), name) || contains(poi.city(), name)) {
      return true;
    }
    if ((poi.district() == null || poi.district().isBlank())
        && contains(poi.address(), name)) {
      return true;
    }
    return contains(poi.name(), name) && isScenicType(poi.type());
  }

  /** 风景类类型词：名称命中目标地名时，靠类型确认它是景区而不是同名企业。 */
  private static boolean isScenicType(String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    return type.contains("风景名胜") || type.contains("旅游区") || type.contains("景区")
        || type.contains("景点") || type.contains("公园") || type.contains("世界遗产")
        || type.contains("寺庙") || type.contains("古迹") || type.contains("国家级");
  }

  private static boolean contains(String text, String name) {
    return text != null && text.contains(name);
  }

  private static ScenicSpot toSpot(Place poi, String source) {
    // 评论正文地图 API 不返回，只给官方详情页入口；链接随整条 POI 原文走，
    // 跨源去重换记录时不会出现「百度的评分 + 高德的链接」这种拼接事实。
    String reviewUrl = SOURCE_AMAP.equals(source)
        ? ReviewLinks.amapPlace(poi.poiId())
        : ReviewLinks.baiduDetail(poi.detailUrl());
    return new ScenicSpot(poi.name(), poi.type(), poi.address(), poi.district(),
        poi.tel(), poi.lat(), poi.lon(), poi.coordinateSystem(), source,
        poi.rating(), poi.commentCount(), reviewUrl,
        source == null ? null : source + "地图");
  }

  /** 同来源按名称去重；跨来源「同名且 50 米内」视为同一实体，保留评分更高的那条原文。 */
  private static List<ScenicSpot> dedupe(List<ScenicSpot> spots) {
    List<ScenicSpot> result = new ArrayList<>();
    for (ScenicSpot spot : spots) {
      if (spot.name() == null || spot.name().isBlank()) {
        continue;
      }
      boolean duplicate = false;
      for (int i = 0; i < result.size(); i++) {
        ScenicSpot kept = result.get(i);
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
          // 直接换整条记录：评分、评价数与 POI 来源保持一致，不把两个源拼成「半个事实」
          if (rankScore(spot) > rankScore(kept)
              || (rankScore(spot) == rankScore(kept)
                  && comments(spot) > comments(kept))) {
            result.set(i, spot);
          }
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        result.add(spot);
      }
    }
    result.sort(RANK);
    return result;
  }

  private static int comments(ScenicSpot s) {
    return s.commentCount() == null ? 0 : s.commentCount();
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
