package com.travelassistant.server.application.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.persistence.AttractionStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把用户嘴里的地名翻译成知识库里的城市。
 *
 * <p>三级判断，逐级降级，任何一级失败都不阻塞对话：
 * <ol>
 *   <li><b>知识库直查</b>：城市 / 省份 / 景点名，精确命中即返回，零延迟零成本；</li>
 *   <li><b>模型判断</b>：只在知识库落空时调用，让模型在**已知城市清单内**做归属判断
 *       （「黄梅」→ 黄冈、「婺源」→ 上饶），输出 JSON 后还要过一遍清单校验，
 *       模型给出清单外的城市一律不采信，避免编造；</li>
 *   <li><b>相似兜底</b>：模型不可用或也判断不出时，用字面公共子串找相近城市，
 *       让界面能给出可点选项，而不是让用户重新打字。</li>
 * </ol>
 *
 * <p>模型只做「归属判断」，不负责事实：它能选的城市必须真实存在于知识库索引，
 * 命中后仍由知识库提供景点与票价。识别结果按候选词缓存，避免同一地名反复计费。
 */
@Service
public class PlaceResolver {
  private static final Logger log = LoggerFactory.getLogger(PlaceResolver.class);

  /** 一次请求最多核实的候选词数量。 */
  private static final int MAX_CANDIDATES = 5;
  /** 缓存条目上限，超过就整体丢弃（地名识别是幂等的，缓存只是省钱省时）。 */
  private static final int CACHE_LIMIT = 256;
  /** 相似城市最多给几个。 */
  private static final int ALTERNATIVE_LIMIT = 3;

  private static final String SYSTEM_PROMPT = """
      你是中国的行政区划与旅游地名助手。用户会给你一个地名（可能是省、市、县、区、景区、俗称或错别字）。
      请判断它属于哪个地级市。清单里的城市要原样输出清单中的写法。
      规则：
      1. 县、区、县级市一般归到所属地级市；景区归到所在城市。
      2. 若它属于某个不在地名清单里的城市，也照实给出那个城市名，由调用方决定是否可用。
      3. 只有确实无法判断、或这个词与中国地名无关时，city 才输出 null。不要编造不存在的地名。
      4. 不要输出解释性段落或 Markdown 代码块，只输出一行 JSON，形如：
      {"city":"<城市>","level":"city|county|spot","reason":"一句话依据"}
      无法判断时输出：{"city":null,"level":"unknown","reason":"简短原因"}
      """;

  private final AttractionStore store;
  private final DeepSeekClient model;
  private final ObjectMapper mapper;
  private final Map<String, PlaceResolution> cache = new ConcurrentHashMap<>();

  public PlaceResolver(AttractionStore store, DeepSeekClient model, ObjectMapper mapper) {
    this.store = store;
    this.model = model;
    this.mapper = mapper;
  }

  /**
   * 识别地名。
   *
   * @param text   整句用户输入，可为空
   * @param hints  前端已经剥离出的候选词，可为空
   * @return 永远非 {@code null}；未识别时 {@code resolved=false} 并带相近城市
   */
  public PlaceResolution resolve(String text, List<String> hints) {
    List<String> candidates = candidates(text, hints);
    if (candidates.isEmpty()) {
      return PlaceResolution.miss("", "没有从这句话里找到地名，可以说「城市或景点 + 玩几天」", List.of());
    }
    String key = String.join("|", candidates);
    PlaceResolution cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    PlaceResolution result = lookup(candidates);
    remember(key, result);
    return result;
  }

  /** 已知城市/省份名（含别名），供前端做提示或调试。 */
  public List<String> knownPlaces() {
    List<String> names = new ArrayList<>(cityCounts().keySet());
    Collections.sort(names);
    return names;
  }

  private PlaceResolution lookup(List<String> candidates) {
    for (String candidate : candidates) {
      PlaceResolution hit = fromKnowledgeBase(candidate);
      if (hit != null) {
        return hit;
      }
    }
    PlaceResolution byModel = fromModel(candidates);
    if (byModel != null && byModel.resolved()) {
      return byModel;
    }
    List<String> similar = similarCities(candidates.get(0));
    // 模型有判断但库里没有这个城市：把「属于哪」说出来，比一句「没认出」有用
    String note = byModel != null && byModel.note() != null && !byModel.note().isBlank()
        ? byModel.note()
        : (similar.isEmpty()
            ? "没认出「" + candidates.get(0) + "」是哪里的地名，可以说它上一级的城市（知识库还没覆盖全部城市）"
            : "没认出「" + candidates.get(0) + "」，你是不是想去下面某个地方");
    return PlaceResolution.miss(candidates.get(0), note, similar);
  }

  /** 一级：知识库直查城市 / 景点。 */
  private PlaceResolution fromKnowledgeBase(String candidate) {
    try {
      List<Attraction> byCity = store.findByCity(candidate);
      if (!byCity.isEmpty()) {
        Attraction first = byCity.get(0);
        String city = first.city() == null || first.city().isBlank() ? candidate : first.city();
        return PlaceResolution.hit(candidate, city, null, first.province(), "city", "kb",
            "「" + candidate + "」是知识库中的城市");
      }
      List<Attraction> byName = store.findByName(candidate);
      if (!byName.isEmpty()) {
        Attraction first = byName.get(0);
        String city = first.city();
        if (city == null || city.isBlank()) {
          city = first.province();
        }
        if (city != null && !city.isBlank()) {
          return PlaceResolution.hit(candidate, city, first.name(), first.province(), "spot", "kb",
              "「" + candidate + "」是「" + first.name() + "」，按所在城市 " + city + " 检索");
        }
      }
    } catch (RuntimeException e) {
      // 库没加载或读取出错：交给模型与相似兜底，不让地名识别整体失败
      log.warn("知识库地名直查失败，转入模型判断：{}", e.getMessage());
    }
    return null;
  }

  /** 二级：模型在已知城市清单内做归属判断，结果必须过清单校验。 */
  private PlaceResolution fromModel(List<String> candidates) {
    if (model == null || !model.configured()) {
      return null;
    }
    List<String> known = knownPlaces();
    if (known.isEmpty()) {
      return null;
    }
    String user = "可选城市：" + String.join("、", known) + "\n待判断地名："
        + String.join(" / ", candidates);
    String answer;
    try {
      answer = model.complete(SYSTEM_PROMPT, user);
    } catch (AgentUnavailableException e) {
      log.warn("地名识别降级为相似兜底：{}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      log.warn("地名识别调用异常：{}", e.getMessage());
      return null;
    }
    JsonNode node = extractJson(answer);
    if (node == null) {
      log.warn("地名识别返回内容不是 JSON，已忽略");
      return null;
    }
    String city = text(node, "city");
    if (city == null) {
      return null;
    }
    String canonical = canonicalCity(city);
    if (canonical == null) {
      // 模型知道它在哪，但这个城市不在知识库里：用「可能属于」的措辞说出来，
      // 既不假装没认出，也不把一个未经清单校验的城市名当成确定事实。
      log.info("模型判断归属为知识库未收录的城市，按未命中处理");
      return PlaceResolution.miss(candidates.get(0),
          "「" + candidates.get(0) + "」可能属于" + city + "，但知识库里没有 " + city + " 的景点",
          List.of());
    }
    String reason = text(node, "reason");
    String level = text(node, "level");
    String note = reason == null
        ? "按「" + candidates.get(0) + "」判断为 " + canonical
        : reason + "，按 " + canonical + " 检索";
    return PlaceResolution.hit(candidates.get(0), canonical, null, null,
        level == null || level.isBlank() ? "city" : level, "llm", note);
  }

  /** 从模型输出里抠出第一段 JSON；不假设它遵守「不要代码块」的约束。 */
  private JsonNode extractJson(String answer) {
    if (answer == null || answer.isBlank()) {
      return null;
    }
    int start = answer.indexOf('{');
    int end = answer.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      return mapper.readTree(answer.substring(start, end + 1));
    } catch (Exception e) {
      return null;
    }
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String s = value.asText("").strip();
    return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
  }

  /** 模型可能写「黄冈市」「湖北省黄冈市」，统一收敛到清单里的写法。 */
  private String canonicalCity(String raw) {
    Map<String, Integer> known = cityCounts();
    String c = raw.strip();
    if (known.containsKey(c)) {
      return c;
    }
    for (String suffix : List.of("市", "县", "区", "省", "自治区", "特别行政区")) {
      if (c.length() > suffix.length() && c.endsWith(suffix)) {
        String trimmed = c.substring(0, c.length() - suffix.length());
        if (known.containsKey(trimmed)) {
          return trimmed;
        }
      }
    }
    String longest = null;
    for (String key : known.keySet()) {
      if (key.length() >= 2 && c.contains(key) && (longest == null || key.length() > longest.length())) {
        longest = key;
      }
    }
    return longest;
  }

  /**
   * 三级：字面相近的城市，用来给界面提供可点选项。
   * 只用公共子串打分，不引入任何写死的城市列表——库里有谁就推荐谁；
   * 只共享一个「山水州城」这类通名时不算相近，否则「长白山」会被推成「山东」。
   */
  private List<String> similarCities(String word) {
    if (word == null || word.isBlank()) {
      return List.of();
    }
    List<Map.Entry<String, Integer>> scored = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : cityCounts().entrySet()) {
      String name = entry.getKey();
      // 省份全称、自治区这类长名字不适合当目的地选项
      if (name.length() < 2 || name.length() > 4) {
        continue;
      }
      String shared = sharedSubstring(name, word);
      // 只共享一个字（「长白山」vs「长沙」共享「长」）不构成可推荐的相近地名，
      // 宁可只给文字说明，也不要给出误导性的按钮。
      if (shared.length() < 2) {
        continue;
      }
      // 景点多的城市优先，避免冷门同名把常用城市挤掉
      int score = shared.length() * 3 + (entry.getValue() >= 50 ? 1 : 0);
      scored.add(Map.entry(name, score));
    }
    scored.sort((a, b) -> b.getValue().equals(a.getValue())
        ? a.getKey().compareTo(b.getKey())
        : b.getValue() - a.getValue());
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : scored) {
      out.add(entry.getKey());
      if (out.size() >= ALTERNATIVE_LIMIT) {
        break;
      }
    }
    return out;
  }

  private String sharedSubstring(String a, String b) {
    String best = "";
    for (int i = 0; i < a.length(); i++) {
      for (int j = i + 1; j <= a.length(); j++) {
        String sub = a.substring(i, j);
        if (sub.length() > best.length() && b.contains(sub)) {
          best = sub;
        }
      }
    }
    return best;
  }

  private Map<String, Integer> cityCounts() {
    try {
      return store.knownCities();
    } catch (RuntimeException e) {
      log.warn("读取城市索引失败：{}", e.getMessage());
      return Map.of();
    }
  }

  private List<String> candidates(String text, List<String> hints) {
    Set<String> out = new LinkedHashSet<>();
    if (hints != null) {
      for (String hint : hints) {
        addCandidate(out, hint);
      }
    }
    if (out.isEmpty() && text != null) {
      // 前端没给候选时，整句原样试一次（模型能理解长句）
      String trimmed = text.strip();
      if (trimmed.length() >= 2 && trimmed.length() <= 30) {
        out.add(trimmed);
      }
    }
    return out.stream().limit(MAX_CANDIDATES).toList();
  }

  private void addCandidate(Set<String> out, String raw) {
    if (raw == null) {
      return;
    }
    String s = raw.strip();
    if (s.length() < 2 || s.length() > 30) {
      return;
    }
    out.add(s);
  }

  private void remember(String key, PlaceResolution result) {
    if (cache.size() >= CACHE_LIMIT) {
      cache.clear();
    }
    cache.put(key, result);
  }
}
