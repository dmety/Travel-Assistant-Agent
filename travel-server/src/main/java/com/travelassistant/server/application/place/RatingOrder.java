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

  /**
   * 明显不是旅游景点的名称词；命中且没有豁免词时从候选里剔除。
   *
   * <p>分两类。第一类是学校与科教条目——它们的评分常是 5.0，纯按评分会把
   * 湖北工业大学、市第二中学、武汉图书馆顶到黄鹤楼前面。
   *
   * <p>第二类是交通与市政的<b>功能性附属设施</b>：站前广场、候车室、停车场这些是
   * 通行空间，不是可游览对象。实测知识库里有「丽江站站前广场」，评分 5.0，
   * 不加这条会排到最前。
   *
   * <p><b>刻意不排除「火车站」「机场」「航站楼」本身</b>：浦口火车站（朱自清《背影》
   * 发生地）、原合肥骆岗机场航站楼都是真实景点，一并排除会错杀。宁可少排，
   * 也不把真景点挡掉——带「旧址」「遗址」的还能靠豁免词留下来。
   */
  private static final List<String> NON_TOURISM = List.of(
      // 学校与科教
      "小学", "中学", "大学", "学院", "幼儿园", "职业技术", "培训", "图书馆", "驾校",
      // 交通枢纽的附属空间
      "站前广场", "站台", "候车室", "候机厅", "候机室", "售票处", "检票口", "进站口", "出站口",
      "换乘中心", "地铁站", "公交站", "公交枢纽", "客运站", "汽车站", "长途站",
      // 市政与出行配套
      "停车楼", "停车场", "收费口", "收费站", "服务区", "加油站", "充电站", "加气站",
      "公共厕所", "公共卫生间", "公厕", "垃圾站", "变电所");

  /**
   * 人文景点豁免：名字带这些词的即使含排除词也保留（大学博物馆、火车站旧址等）。
   *
   * <p>只收<b>实体景点词</b>。「公园」「景区」这类是空间范围词，不在这里——
   * 它们一旦入选，「某某景区停车场」「某某公园停车场」就会被豁免掉，
   * 通行空间又混回候选里。实测就是这条把测试里的景区停车场放行了。
   */
  private static final List<String> KEEP_WORDS = List.of(
      "博物馆", "纪念馆", "旧址", "遗址", "美术馆", "艺术馆", "观景台", "古镇",
      "故居", "会址", "陵园", "纪念碑", "文化宫", "展览馆", "陈列馆");

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
    return a != null && isNonTourismName(a.name());
  }

  /**
   * 按名称判断是否为非景点。
   *
   * <p>地图实时检索拿到的是 POI 而不是 {@link Attraction}，同样需要这套口径：
   * 否则「站前广场」在知识库路径被挡住了，实时检索出来的又照样进候选。
   * 两条链路共用一个判断，才不会出现「同一个名字两边结论不同」。
   */
  public static boolean isNonTourismName(String name) {
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
