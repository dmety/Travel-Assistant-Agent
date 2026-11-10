package com.travelassistant.server.application.place;

import com.travelassistant.server.domain.attraction.Attraction;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 景点候选的统一排序口径：用户说「好评高的放前面」，那第一排序键就是真实评分。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>评分只认数据源里真实存在的数值（0 &lt; r &le; 5），缺失/脏值一律排到有评分的后面，
 *       <b>不因为「没查到」就当成差评，也不给它补一个默认分</b>；</li>
 *   <li>知识库里混着大学、图书馆这类 {@code rating=5.0} 的科教条目，纯按评分会把它们
 *       顶到最前，所以先剔除明显的非景点噪音；博物馆/纪念馆/旧址等人文景点保留；</li>
 *   <li>等级（5A/4A）与名称只作同级次序，排序结果对同一份输入可复现。</li>
 * </ul>
 *
 * <p>这里不产生任何数据，只重排已有事实。评分原文是高德/百度地图 POI 或离线知识库字段，
 * 票价、开放时间缺字段仍然由展示层如实标注「未核验」。
 */
public final class RatingOrder {

  private static final BigDecimal MAX_SCORE = new BigDecimal("5");

  /** 明显不是旅游景点的名称词；命中且没有豁免词时从候选里剔除。 */
  private static final List<String> NON_TOURISM = List.of(
      "小学", "中学", "大学", "学院", "幼儿园", "职业技术", "培训", "图书馆", "驾校");

  /** 人文景点豁免：名字带这些词的即使含校名/馆名也保留（大学博物馆、旧址等）。 */
  private static final List<String> KEEP_WORDS = List.of(
      "博物馆", "纪念馆", "旧址", "遗址", "美术馆", "艺术馆", "观景台", "公园", "景区", "古镇");

  private RatingOrder() {
  }

  /** 把评分原文解析成可比较的数值；空串、"[]"、越界值都当作「没有评分」。 */
  public static BigDecimal parse(String raw) {
    if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) {
      return null;
    }
    try {
      BigDecimal value = new BigDecimal(raw.strip());
      return value.compareTo(BigDecimal.ZERO) > 0
          && value.compareTo(MAX_SCORE) <= 0 ? value : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 是否为明显非景点条目：只按名称判断，不猜类型，避免误伤红色经典与人文场馆。 */
  public static boolean isNonTourism(Attraction a) {
    String name = a.name();
    if (name == null || name.isBlank()) {
      return false;
    }
    if (KEEP_WORDS.stream().anyMatch(name::contains)) {
      return false;
    }
    return NON_TOURISM.stream().anyMatch(name::contains);
  }

  private static BigDecimal scoreOf(Attraction a) {
    return parse(a.rating());
  }

  /** 有评分优先 → 评分降序 → 有等级优先 → 名称（稳定可复现）。 */
  public static final Comparator<Attraction> ATTRACTION = Comparator
      .comparingInt((Attraction a) -> scoreOf(a) == null ? 1 : 0)
      .thenComparing(RatingOrder::scoreOf, Comparator.nullsLast(Comparator.reverseOrder()))
      .thenComparingInt(a -> a.level() == null || a.level().isBlank() ? 1 : 0)
      .thenComparing(Attraction::name, Comparator.nullsLast(Comparator.naturalOrder()));

  /** 过滤噪音后按评分排序，截取前 {@code limit} 条；返回的是新列表，不改入参。 */
  public static List<Attraction> ranked(List<Attraction> source, int limit) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return source.stream()
        .filter(a -> !isNonTourism(a))
        .sorted(ATTRACTION)
        .limit(Math.max(limit, 1))
        .toList();
  }
}
