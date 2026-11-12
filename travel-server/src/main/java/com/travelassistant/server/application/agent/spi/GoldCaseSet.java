package com.travelassistant.server.application.agent.spi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * G1 契约探针与 G2 安全探针的固定用例集，从 classpath 的 {@code agent/gold_cases.json} 读取。
 *
 * <p>仓库里只有 {@code tools/agent/gold_cases.json} 一份，Maven 构建时复制进 classpath；
 * Python 的 G0 闸门负责校验它与 {@code provider-contract.json} 的 {@code goldCases} 列表同步。
 * 用例集随规范版本入库，同一配置、同一版规则集、同一组用例必须得到一致判定。
 */
@Component
public class GoldCaseSet {
  private static final String RESOURCE = "agent/gold_cases.json";

  /** 用例输入。deadline 取 FUTURE 或 EXPIRED，避免把绝对时间写进规范用例集。 */
  public record Input(
      String intent,
      String runId,
      Map<String, Object> context,
      List<Map<String, Object>> facts,
      Map<String, String> hints,
      String deadline) {
  }

  /** 一条 gold case。detectorCode 是该用例若违规，校验器应以哪个原因码拒绝。 */
  public record GoldCase(
      String id,
      String gate,
      String name,
      Input input,
      Map<String, Object> expect,
      String detectorCode) {
  }

  private final String contractVersion;
  private final List<GoldCase> cases;

  /** Spring 注入构造器；另一个两参构造器只供测试构造自定义用例集。 */
  @Autowired
  public GoldCaseSet(ObjectMapper json) {
    GoldCaseSet loaded = load(json);
    this.contractVersion = loaded.contractVersion;
    this.cases = loaded.cases;
  }

  /** 供测试构造自定义用例集（如版本漂移场景）。 */
  public GoldCaseSet(String contractVersion, List<GoldCase> cases) {
    this.contractVersion = contractVersion;
    this.cases = List.copyOf(cases);
  }

  private static GoldCaseSet load(ObjectMapper json) {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      Map<String, Object> doc = json.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
      List<GoldCase> parsed = new ArrayList<>();
      Object rawCases = doc.get("cases");
      if (rawCases instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> raw) {
            parsed.add(parseCase(asMap(raw)));
          }
        }
      }
      return new GoldCaseSet(String.valueOf(doc.get("contractVersion")), parsed);
    } catch (IOException e) {
      throw new IllegalStateException("Agent gold cases 缺失或不可解析：" + RESOURCE, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Map<?, ?> raw) {
    return (Map<String, Object>) raw;
  }

  private static GoldCase parseCase(Map<String, Object> raw) {
    Map<String, Object> input = raw.get("input") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    Map<String, Object> expect = raw.get("expect") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    List<Map<String, Object>> facts = new ArrayList<>();
    if (input.get("facts") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> fact) {
          facts.add(asMap(fact));
        }
      }
    }
    Map<String, String> hints = new LinkedHashMap<>();
    if (input.get("hints") instanceof Map<?, ?> map) {
      map.forEach((key, value) -> hints.put(String.valueOf(key),
          value == null ? null : String.valueOf(value)));
    }
    Map<String, Object> context = input.get("context") instanceof Map<?, ?> m
        ? asMap(m) : Map.of();
    return new GoldCase(
        str(raw, "id"), str(raw, "gate"), str(raw, "name"),
        new Input(str(input, "intent"), str(input, "runId"), context,
            facts, hints, str(input, "deadline")),
        expect, str(raw, "detectorCode"));
  }

  public String contractVersion() {
    return contractVersion;
  }

  public List<GoldCase> cases() {
    return cases;
  }

  public List<GoldCase> forGate(String gate) {
    List<GoldCase> result = new ArrayList<>();
    for (GoldCase item : cases) {
      if (gate.equals(item.gate())) {
        result.add(item);
      }
    }
    return result;
  }

  public Optional<GoldCase> byId(String id) {
    return cases.stream().filter(item -> id.equals(item.id())).findFirst();
  }

  /** 把用例输入转成 Provider 唯一认识的请求对象。 */
  public AgentContext context(Input input, int timeoutMs) {
    List<Fact> facts = new ArrayList<>();
    for (Map<String, Object> raw : input.facts()) {
      facts.add(fact(raw));
    }
    Instant deadline = "EXPIRED".equals(input.deadline())
        ? Instant.now().minusSeconds(1)
        : Instant.now().plusMillis(Math.max(100, timeoutMs));
    return new AgentContext(input.runId(), null, input.intent(),
        input.context(), facts, input.hints(), deadline);
  }

  public RunBudget budget(Input input, int timeoutMs) {
    Instant deadline = "EXPIRED".equals(input.deadline())
        ? Instant.now().minusSeconds(1)
        : Instant.now().plusMillis(Math.max(100, timeoutMs));
    return new RunBudget(deadline, 1, 1);
  }

  private static Fact fact(Map<String, Object> raw) {
    Map<String, Object> value = raw.get("value") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    Map<String, Object> ev = raw.get("evidence") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    return new Fact(str(raw, "factId"), str(raw, "type"), value,
        new Evidence(str(ev, "sourceKind"), str(ev, "sourceId"),
            str(ev, "verificationStatus"), parseInstant(str(ev, "fetchedAt")),
            str(ev, "excerpt")));
  }

  private static Instant parseInstant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value instanceof String s ? s : null;
  }
}
