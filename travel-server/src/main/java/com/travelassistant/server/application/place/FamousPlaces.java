package com.travelassistant.server.application.place;

import com.travelassistant.server.application.intent.ConversationTurn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 著名景点权威归属表：知识库里只有同名冷门条目时，不让它抢走常识里的那个景区。
 *
 * <p>问题现场：「华山」在库里只有济南的华山风景区、「黄山」只有广州的黄山鲁森林公园、
 * 「老君山」只有成都的老君山。地名解析按库直查会把西岳华山认成济南、把安徽黄山认成广州，
 * 排出来的行程全是外省。这些是全国知名景区，用户说这个词的默认含义不该由库的覆盖缺口决定。
 *
 * <p>表里只记「归属」这类稳定常识（省份、地级市、景区本名），不记票价与开放时间；
 * 景点明细仍然走知识库或地图实时检索。{@code destination} 是给规划/检索用的地名：
 * 库里有该城市的可核验条目就用城市名，库里只有同名噪音时就用景区原名走实时检索。
 *
 * <p>匹配优先级高于知识库直查与模型判断；用户显式写了「济南华山」「广州黄山」这类
 * 同名冷门地点时（competingCities），本表主动让位，不替用户做主。
 */
public final class FamousPlaces {

  /** 一条权威归属。 */
  public record Entry(String name, String province, String city, String spot,
      String destination, List<String> competingCities) {

    /** 给人看的说明：既说清归属，也说清按哪个地名检索。 */
    public String note() {
      String where = province.equals(city) ? province : province + city;
      return "「" + name + "」是" + where + "的" + spot
          + "，按「" + destination + "」检索本地景点";
    }
  }

  /** 按景区名长度倒序匹配，避免「黄山」抢在「黄山风景区」之类的长词前面。 */
  private static final List<Entry> TABLE = List.of(
      new Entry("华山", "陕西", "渭南", "西岳华山风景名胜区", "华山", List.of("济南")),
      new Entry("黄山", "安徽", "黄山", "黄山风景名胜区", "黄山", List.of("广州")),
      new Entry("泰山", "山东", "泰安", "泰山风景名胜区", "泰山", List.of()),
      new Entry("张家界", "湖南", "张家界", "武陵源风景名胜区", "张家界", List.of()),
      new Entry("九寨沟", "四川", "阿坝", "九寨沟风景名胜区", "九寨沟", List.of()),
      new Entry("兵马俑", "陕西", "西安", "秦始皇帝陵博物院", "西安", List.of()),
      new Entry("鼓浪屿", "福建", "厦门", "鼓浪屿风景名胜区", "厦门", List.of()),
      new Entry("稻城亚丁", "四川", "甘孜", "稻城亚丁景区", "稻城亚丁", List.of()),
      new Entry("故宫", "北京", "北京", "故宫博物院", "北京", List.of("重庆", "厦门")),
      new Entry("西湖", "浙江", "杭州", "西湖风景名胜区", "杭州", List.of("福州")),
      new Entry("老君山", "河南", "洛阳", "老君山风景名胜区", "老君山", List.of("成都")),
      new Entry("布达拉宫", "西藏", "拉萨", "布达拉宫", "拉萨", List.of()),
      new Entry("茶卡盐湖", "青海", "海西", "茶卡盐湖", "茶卡盐湖", List.of()),
      new Entry("洪崖洞", "重庆", "重庆", "洪崖洞", "重庆", List.of()),
      new Entry("大唐不夜城", "陕西", "西安", "大唐不夜城", "西安", List.of()),
      new Entry("阿那亚", "河北", "秦皇岛", "阿那亚", "阿那亚", List.of()),
      new Entry("环球影城", "北京", "北京", "北京环球影城", "北京", List.of()),
      new Entry("迪士尼", "上海", "上海", "上海迪士尼度假区", "上海", List.of())
  ).stream().sorted(Comparator.comparingInt((Entry e) -> e.name().length()).reversed()).toList();

  /** 名字里带这些后缀的是同名机构/公园，不是景区本体。 */
  private static final List<String> NOISE_CONTINUATIONS = List.of(
      "鲁森林公园", "公司", "大厦", "医院", "大学", "中学", "小学", "幼儿园",
      "职业技术", "文化宫", "艺术剧院", "西湖公园");

  /** 用于判断「这句话是不是明确提了另一个地方」。 */
  private static final Set<String> CITY_TOKENS = buildCityTokens();

  private static Set<String> buildCityTokens() {
    Set<String> out = new LinkedHashSet<>();
    for (Entry e : TABLE) {
      out.add(e.city());
      out.addAll(e.competingCities());
    }
    // 常被当作换目的地的说法：出现这些城市就不该再沿用上文景点的旧状态
    out.addAll(List.of("北京", "上海", "天津", "重庆", "广州", "成都", "西安", "杭州",
        "武汉", "南京", "苏州", "青岛", "三亚", "丽江", "大理", "桂林", "长沙",
        "郑州", "济南", "厦门", "洛阳", "拉萨", "秦皇岛", "泰安", "黄山"));
    return Set.copyOf(out);
  }

  private FamousPlaces() {
  }

  /** 在一段话里找著名景点；没写、明确换了地方、或是同名机构时都返回空。 */
  public static Optional<Entry> find(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String t = text.strip();
    for (Entry e : TABLE) {
      if (!t.contains(e.name())) {
        continue;
      }
      if (e.competingCities().stream().anyMatch(t::contains)) {
        continue;
      }
      if (NOISE_CONTINUATIONS.stream().anyMatch(t::contains)) {
        continue;
      }
      return Optional.of(e);
    }
    return Optional.empty();
  }

  /** 从最近的用户轮次里找著名景点（只看用户说的话，助手转述的错城市不作数）。 */
  public static Optional<Entry> findInRecentUserTurns(List<ConversationTurn> history) {
    if (history == null || history.isEmpty()) {
      return Optional.empty();
    }
    List<ConversationTurn> turns = new ArrayList<>(history);
    for (int i = turns.size() - 1; i >= 0; i--) {
      ConversationTurn turn = turns.get(i);
      if (turn == null || !turn.fromUser() || !turn.usable()) {
        continue;
      }
      Optional<Entry> hit = find(turn.text());
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }

  /** 这句话是否明确提了另一个地方：是就不要沿用上文的景点状态。 */
  public static boolean mentionsOtherPlace(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String t = text.strip();
    if (t.contains("市") || t.contains("省")) {
      return true;
    }
    return CITY_TOKENS.stream().anyMatch(t::contains);
  }

  /** 会话里存的城市是否与景点归属冲突（旧会话里存着「济南」这种错值）。 */
  public static boolean conflicts(String savedCity, Entry entry) {
    if (savedCity == null || savedCity.isBlank() || entry == null) {
      return false;
    }
    String c = savedCity.strip();
    if (c.equals(entry.destination()) || c.equals(entry.name())
        || c.equals(entry.city()) || c.contains(entry.city())) {
      return false;
    }
    // 「渭南市」「厦门市」这类带后缀的写法也算同一归属
    String trimmed = c.endsWith("市") ? c.substring(0, c.length() - 1) : c;
    return !entry.city().equals(trimmed) && !entry.city().contains(trimmed);
  }
}
