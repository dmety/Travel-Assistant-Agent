package com.travelassistant.server.application.intent;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 没有模型可用时的降级解析。
 *
 * <p>定位：**不是主力路径**。主力是模型（能听懂「带爸妈」「下周六」「六千」这类说法），
 * 这里只保证「不配密钥 / 模型抖动」时对话页仍然能用基本句式——「武汉 3 天 2 人预算 5000」。
 * 因此规则故意写得保守：认不出来就给 {@code null}，绝不猜，猜错的槽位比没有槽位更糟。
 */
final class IntentRules {

  /** 中文数字只到十：再大的天数会走「数字」分支或被上限截断。 */
  private static final Map<Character, Integer> CN_NUM = Map.ofEntries(
      Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2), Map.entry('三', 3),
      Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
      Map.entry('八', 8), Map.entry('九', 9), Map.entry('十', 10));

  private static final Pattern DAYS_ARABIC = Pattern.compile("(\\d{1,2})\\s*天");
  private static final Pattern DAYS_CN = Pattern.compile("([一两二三四五六七八九十])\\s*天");
  private static final Pattern PEOPLE_ARABIC = Pattern.compile("(\\d{1,2})\\s*[个人位]");
  private static final Pattern PEOPLE_CN_FAMILY = Pattern.compile("一家([一两二三四五六])口");
  private static final Pattern BUDGET_WITH_MARK = Pattern.compile(
      "预算[^，。！？,.!?]{0,6}?(\\d+(?:\\.\\d+)?)\\s*(万|千|k|K)?");
  private static final Pattern MONEY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|千)?\\s*(?:元|块)");
  private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
  private static final Pattern CN_DATE = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");

  /** 与 {@link IntentParser} 的偏好词表保持一致；这里只做关键词到词的映射。 */
  private static final Map<String, String> PREF_KEYWORDS = new LinkedHashMap<>();

  static {
    PREF_KEYWORDS.put("人文", "人文");
    PREF_KEYWORDS.put("历史", "人文");
    PREF_KEYWORDS.put("博物馆", "人文");
    PREF_KEYWORDS.put("古迹", "人文");
    PREF_KEYWORDS.put("古镇", "人文");
    PREF_KEYWORDS.put("文化", "人文");
    PREF_KEYWORDS.put("自然", "自然");
    PREF_KEYWORDS.put("山水", "自然");
    PREF_KEYWORDS.put("风景", "自然");
    PREF_KEYWORDS.put("爬山", "自然");
    PREF_KEYWORDS.put("登山", "自然");
    PREF_KEYWORDS.put("森林", "自然");
    PREF_KEYWORDS.put("草原", "自然");
    PREF_KEYWORDS.put("亲子", "亲子");
    PREF_KEYWORDS.put("小孩", "亲子");
    PREF_KEYWORDS.put("孩子", "亲子");
    PREF_KEYWORDS.put("儿童", "亲子");
    PREF_KEYWORDS.put("带娃", "亲子");
    PREF_KEYWORDS.put("美食", "美食");
    PREF_KEYWORDS.put("小吃", "美食");
    PREF_KEYWORDS.put("火锅", "美食");
    PREF_KEYWORDS.put("摄影", "摄影");
    PREF_KEYWORDS.put("拍照", "摄影");
    PREF_KEYWORDS.put("出片", "摄影");
    PREF_KEYWORDS.put("购物", "购物");
    PREF_KEYWORDS.put("逛街", "购物");
    PREF_KEYWORDS.put("夜景", "夜游");
    PREF_KEYWORDS.put("夜游", "夜游");
    PREF_KEYWORDS.put("温泉", "温泉");
    PREF_KEYWORDS.put("泡汤", "温泉");
    PREF_KEYWORDS.put("海岛", "海岛");
    PREF_KEYWORDS.put("沙滩", "海岛");
    PREF_KEYWORDS.put("慢", "慢节奏");
    PREF_KEYWORDS.put("不赶", "慢节奏");
    PREF_KEYWORDS.put("轻松", "慢节奏");
    PREF_KEYWORDS.put("悠闲", "慢节奏");
  }

  /** 语气、动词与数量词：剥掉这些，剩下的片段才可能是地名。长词在前，避免被短词先切掉。 */
  private static final String NOISE = "帮我|麻烦|请问|请|我想要|我想|我要|我们|我|打算|计划|准备|想去|"
      + "要去|去|到|在|从|出发|游玩|旅游|旅行|度假|逛逛|看看|转转|走走|好玩|行程|安排|规划|推荐|"
      + "一下|怎么|如何|几天|周末|自驾|老人|小孩|带|和|跟|一家人|一家老小|玩|游|耍";

  private static final String GREETING =
      "^(你好|您好|hi|hello|hey|在吗|在不在|谢谢|多谢|好的|好|嗯|ok|收到|早上好|晚上好)[!！。.~～\\s]*$";

  private IntentRules() {
  }

  /**
   * 按句式解析一句话。
   *
   * @param text  用户原话
   * @param today 服务器当天日期，用于相对日期换算
   */
  static TravelIntent parse(String text, LocalDate today) {
    String t = text == null ? "" : text;
    List<String> notes = new ArrayList<>();

    Integer days = days(t);
    LocalDate[] range = dates(t, today);
    LocalDate depart = range[0];
    LocalDate ret = range[1];
    if (days != null && depart != null && ret == null) {
      // 说了「下周六出发，玩三天」：返回日按 3 天行程推算，并如实标注这是推算值
      ret = depart.plusDays(days - 1L);
      notes.add("返回日期按 " + days + " 天行程推算为 " + ret);
    }

    return new TravelIntent(
        intent(t, days),
        firstPlaceHint(t),
        days,
        travelers(t),
        budget(t),
        TravelIntent.CURRENCY_CNY,
        preferences(t),
        depart,
        ret,
        "low",
        "按句式规则解析（未使用模型）",
        "rules",
        notes);
  }

  /** 从整句里抠出 1~3 个可能的片段，交给地名服务核实；不是最终结论。 */
  static List<String> placeHints(String text) {
    String s = String.valueOf(text == null ? "" : text)
        .replaceAll("\\d+\\s*(天|日|晚)", " ")
        .replaceAll("\\d+\\s*(人|位)", " ")
        .replaceAll("预算[^，。！？\\s]*", " ")
        .replaceAll(NOISE, " ")
        .replaceAll("[，。！？、,.!?；;：:\\s]+", " ")
        .trim();
    if (s.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String seg : s.split(" ")) {
      String word = seg.trim()
          .replaceAll("^[去到在从]", "")
          .replaceAll("[玩游耍了的呢吗吧呀啊嘛]+$", "");
      if (word.length() < 2) {
        continue;
      }
      addHint(out, word);
      addHint(out, word.replaceAll("(市|省|县|区|景区|风景区)$", ""));
    }
    return out.stream().limit(3).toList();
  }

  private static void addHint(Set<String> out, String word) {
    if (word.length() >= 2 && word.length() <= 10) {
      out.add(word);
    }
  }

  private static String firstPlaceHint(String text) {
    List<String> hints = placeHints(text);
    return hints.isEmpty() ? null : hints.get(0);
  }

  private static String intent(String text, Integer days) {
    if (text.strip().matches(GREETING)) {
      return TravelIntent.CHAT;
    }
    boolean planning = days != null
        || text.contains("行程") || text.contains("安排") || text.contains("规划")
        || text.contains("怎么玩") || text.contains("玩") || text.contains("天");
    if (!planning && (text.contains("吃") || text.contains("美食") || text.contains("小吃")
        || text.contains("餐厅") || text.contains("火锅"))) {
      return TravelIntent.FOOD_RECOMMEND;
    }
    return TravelIntent.PLAN_DRAFT;
  }

  private static Integer days(String text) {
    Matcher m = DAYS_ARABIC.matcher(text);
    if (m.find()) {
      int n = Integer.parseInt(m.group(1));
      return n >= 1 && n <= 30 ? n : null;
    }
    m = DAYS_CN.matcher(text);
    if (m.find()) {
      return CN_NUM.get(m.group(1).charAt(0));
    }
    if (text.contains("一周") || text.contains("一星期") || text.contains("1周")) {
      return 7;
    }
    if (text.contains("周末")) {
      return 2;
    }
    return null;
  }

  private static Integer travelers(String text) {
    Matcher m = PEOPLE_ARABIC.matcher(text);
    if (m.find()) {
      int n = Integer.parseInt(m.group(1));
      return n >= 1 && n <= 30 ? n : null;
    }
    m = PEOPLE_CN_FAMILY.matcher(text);
    if (m.find()) {
      return CN_NUM.get(m.group(1).charAt(0));
    }
    if (text.contains("一个人") || text.contains("独自") || text.contains("就我一个")) {
      return 1;
    }
    if (text.contains("两个人") || text.contains("二个人") || text.contains("情侣")
        || text.contains("夫妻") || text.contains("两口子")) {
      return 2;
    }
    // 「带爸妈」「带老人」只知道多了人，不知道具体几个：宁可留空让补问卡问，
    // 也不要瞎猜成 3 人（有的家庭是带一方父母，有的还带保姆）。
    return null;
  }

  private static BigDecimal budget(String text) {
    Matcher m = BUDGET_WITH_MARK.matcher(text);
    if (!m.find()) {
      m = MONEY.matcher(text);
      if (!m.find()) {
        return null;
      }
    }
    BigDecimal value = new BigDecimal(m.group(1));
    String unit = m.groupCount() >= 2 ? m.group(2) : null;
    if (unit != null) {
      if (unit.equals("万")) {
        value = value.multiply(BigDecimal.valueOf(10000));
      } else {
        value = value.multiply(BigDecimal.valueOf(1000));
      }
    }
    return value.signum() > 0 ? value : null;
  }

  private static List<String> preferences(String text) {
    Set<String> out = new LinkedHashSet<>();
    PREF_KEYWORDS.forEach((keyword, canonical) -> {
      if (text.contains(keyword)) {
        out.add(canonical);
      }
    });
    return out.stream().limit(4).toList();
  }

  /**
   * 日期：先找显式日期，再找相对说法；多个日期按出现顺序当作出发与返回。
   * 只做「今天 / 明天 / 后天 / 周X / 下周X / M月D日 / YYYY-MM-DD」这些常见写法。
   */
  private static LocalDate[] dates(String text, LocalDate today) {
    List<int[]> spans = new ArrayList<>();
    List<LocalDate> found = new ArrayList<>();

    Matcher iso = ISO_DATE.matcher(text);
    while (iso.find()) {
      try {
        LocalDate d = LocalDate.of(Integer.parseInt(iso.group(1)),
            Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        spans.add(new int[] {iso.start(), iso.end()});
        found.add(d);
      } catch (RuntimeException ignored) {
        // 非法日期（2026-13-40）当作没写
      }
    }
    if (found.isEmpty()) {
      Matcher cn = CN_DATE.matcher(text);
      while (cn.find()) {
        LocalDate d = cnDate(Integer.parseInt(cn.group(1)), Integer.parseInt(cn.group(2)), today);
        if (d == null) {
          continue;
        }
        spans.add(new int[] {cn.start(), cn.end()});
        found.add(d);
      }
    }
    if (found.isEmpty()) {
      LocalDate relative = relativeDate(text, today);
      return new LocalDate[] {relative, null};
    }
    if (found.size() == 1) {
      int end = spans.get(0)[1];
      String window = text.substring(end, Math.min(text.length(), end + 4));
      boolean isReturn = window.contains("返") || window.contains("回");
      return isReturn ? new LocalDate[] {null, found.get(0)}
          : new LocalDate[] {found.get(0), null};
    }
    return new LocalDate[] {found.get(0), found.get(found.size() - 1)};
  }

  private static LocalDate relativeDate(String text, LocalDate today) {
    if (text.contains("大后天")) {
      return today.plusDays(3);
    }
    if (text.contains("后天")) {
      return today.plusDays(2);
    }
    if (text.contains("明天") || text.contains("明日")) {
      return today.plusDays(1);
    }
    if (text.contains("今天") || text.contains("今日")) {
      return today;
    }
    boolean nextWeek = text.contains("下周") || text.contains("下个星期");
    if (text.contains("周六") || text.contains("星期六")) {
      return weekday(today, DayOfWeek.SATURDAY, nextWeek);
    }
    if (text.contains("周日") || text.contains("周天") || text.contains("星期日")
        || text.contains("星期天")) {
      return weekday(today, DayOfWeek.SUNDAY, nextWeek);
    }
    if (nextWeek) {
      return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }
    return null;
  }

  private static LocalDate weekday(LocalDate today, DayOfWeek target, boolean nextWeek) {
    LocalDate d = today.with(TemporalAdjusters.nextOrSame(target));
    return nextWeek ? d.plusWeeks(1) : d;
  }

  /** 「11 月 28 日」：今年已过去就算明年，避免解析出过去的日期。 */
  private static LocalDate cnDate(int month, int day, LocalDate today) {
    LocalDate d = LocalDate.of(today.getYear(), month, day);
    if (d.isBefore(today)) {
      try {
        d = LocalDate.of(today.getYear() + 1, month, day);
      } catch (RuntimeException e) {
        return null;
      }
    }
    return d;
  }
}
