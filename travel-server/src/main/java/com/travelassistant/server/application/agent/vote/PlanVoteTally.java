package com.travelassistant.server.application.agent.vote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两轮投票的确定性计票器：等价移植 {@code tools/agent/plan_vote.py} 的规则。
 *
 * <p>输入只有票、候选方案列表和评委声明，不读时钟、不取随机数、不访问注册表，
 * 同样的输入必得同样的输出。票沿用 Provider 响应契约形状（{@code List<Map>}），
 * 因此 Python 原型与 Java 运行时可以逐例对照。
 *
 * <p>三条 {@code E_VOTE_*} 是编排层局部原因码，按《Agent 运行时架构设计》的定案
 * 只写进 {@code rejections} 与 {@code notices}，不进 {@code AdmissionReport}、
 * 不参与 {@code contractVersion}、不计 Provider 违规、不加熔断计数。
 */
public final class PlanVoteTally {

  public static final String SCORE_CATEGORY = "PLAN_SCORE";
  public static final String E_VOTE_RUBRIC = "E_VOTE_RUBRIC";
  public static final String E_VOTE_UNKNOWNPLAN = "E_VOTE_UNKNOWNPLAN";
  public static final String E_VOTE_DUPLICATE = "E_VOTE_DUPLICATE";

  private static final Set<String> FACT_KEYS =
      Set.of("factId", "type", "value", "evidence");
  private static final Set<String> VALUE_KEYS =
      Set.of("category", "planId", "rubric", "scores", "groundingFactIds", "decision");
  private static final Set<String> EVIDENCE_KEYS =
      Set.of("sourceKind", "sourceId", "verificationStatus", "fetchedAt", "excerpt");
  private static final Set<String> DECISIONS = Set.of("SCORE", "ABSTAIN", "VETO");
  private static final Set<String> ALLOWED_STATUS = Set.of("unverified", "pending");
  private static final BigDecimal SCORE_MIN = BigDecimal.ZERO;
  private static final BigDecimal SCORE_MAX = new BigDecimal("5");
  private static final int SCALE = 4;

  private PlanVoteTally() {
  }

  /** 评委声明：只按自己的 criteria 打分，weight 决定计票权重。 */
  public record Rubric(String providerId, List<String> criteria, BigDecimal weight,
      boolean allowVeto) {
    public Rubric {
      if (weight == null || weight.signum() <= 0) {
        throw new IllegalArgumentException("评委权重必须为正数");
      }
      criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }
  }

  /** 一个评委返回的一批票。providerId 由编排层按请求来源记录，不写进 Fact。 */
  public record BallotBatch(String providerId, List<Map<String, Object>> facts,
      Set<String> ownFactIds) {
    public BallotBatch {
      facts = facts == null ? List.of() : List.copyOf(facts);
      ownFactIds = ownFactIds == null ? Set.of() : Set.copyOf(ownFactIds);
    }
  }

  public record Rejection(String providerId, String factId, String code, String detail) {
  }

  public record Breakdown(String providerId, String normalized, String weight,
      Map<String, String> criteria, List<String> groundingFactIds) {
  }

  public record Ranking(String planId, String score, int voterCount, String weightSum,
      List<String> abstained, List<Breakdown> breakdown) {
  }

  public record Excluded(String planId, String reason, List<String> providers,
      List<String> groundingFactIds) {
  }

  public record VoteOutcome(String schemaVersion, String intent, String winner,
      List<Ranking> ranking, List<Excluded> excluded, List<String> notices,
      List<Rejection> rejections, boolean homogeneous) {
  }

  private record Accepted(String providerId, String planId, String decision,
      BigDecimal normalized, BigDecimal weight, Map<String, BigDecimal> criteria,
      List<String> groundingFactIds) {
  }

  private record Validation(List<Accepted> accepted, List<Rejection> rejections) {
  }

  /** 计票入口。默认拒绝：校验不通过的票整体丢弃，不做静默降级。 */
  public static VoteOutcome tally(List<BallotBatch> batches, List<String> planIds,
      Map<String, Rubric> rubrics) {
    List<BallotBatch> safeBatches = batches == null ? List.of() : batches;
    List<String> safePlans = planIds == null ? List.of() : List.copyOf(planIds);
    Map<String, Rubric> safeRubrics = rubrics == null ? Map.of() : rubrics;

    Validation validation = validateBallots(safeBatches, safePlans, safeRubrics);
    Map<String, List<Accepted>> byPlan = new LinkedHashMap<>();
    for (Accepted ballot : validation.accepted()) {
      byPlan.computeIfAbsent(ballot.planId(), k -> new ArrayList<>()).add(ballot);
    }

    List<Ranking> ranking = new ArrayList<>();
    List<Excluded> excluded = new ArrayList<>();
    List<String> notices = new ArrayList<>();
    // 排序与平局裁决必须用未量化的精确分数；量化只发生在输出，避免与 Python 原型
    // 在极接近分数上出现顺序差异。
    Map<String, BigDecimal> exactScores = new LinkedHashMap<>();

    for (String planId : safePlans) {
      List<Accepted> ballots = byPlan.getOrDefault(planId, List.of());
      List<Accepted> vetoes = ballots.stream()
          .filter(b -> "VETO".equals(b.decision())).toList();
      if (!vetoes.isEmpty()) {
        List<String> providers = vetoes.stream().map(Accepted::providerId).toList();
        Set<String> grounding = new TreeSet<>();
        for (Accepted veto : vetoes) {
          grounding.addAll(veto.groundingFactIds());
        }
        excluded.add(new Excluded(planId, "VETO", providers, new ArrayList<>(grounding)));
        notices.add("方案 " + planId + " 命中硬约束，由 "
            + String.join("、", providers) + " 否决，不参与计票");
        continue;
      }

      List<Accepted> scored = ballots.stream()
          .filter(b -> "SCORE".equals(b.decision())).toList();
      List<String> abstained = ballots.stream()
          .filter(b -> "ABSTAIN".equals(b.decision())).map(Accepted::providerId).toList();
      Set<String> voting = new LinkedHashSet<>();
      for (Accepted ballot : ballots) {
        voting.add(ballot.providerId());
      }
      List<String> silent = safeRubrics.keySet().stream()
          .filter(p -> !voting.contains(p)).sorted().toList();
      if (!abstained.isEmpty()) {
        notices.add("方案 " + planId + " 有 " + abstained.size() + " 位评委弃票，不计入分母");
      }
      if (!silent.isEmpty()) {
        notices.add("方案 " + planId + " 有 " + silent.size() + " 位评委未交票");
      }
      if (scored.isEmpty()) {
        notices.add("方案 " + planId + " 无有效评分，不参与排名");
        continue;
      }

      BigDecimal weightSum = BigDecimal.ZERO;
      BigDecimal weighted = BigDecimal.ZERO;
      for (Accepted ballot : scored) {
        weightSum = weightSum.add(ballot.weight());
        weighted = weighted.add(ballot.normalized().multiply(ballot.weight()));
      }
      BigDecimal score = weightSum.signum() > 0
          ? weighted.divide(weightSum, 10, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;
      exactScores.put(planId, score);

      List<Breakdown> breakdown = new ArrayList<>();
      for (Accepted ballot : scored) {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : ballot.criteria().entrySet()) {
          criteria.put(entry.getKey(), plain(entry.getValue()));
        }
        breakdown.add(new Breakdown(ballot.providerId(), scaled(ballot.normalized()),
            plain(ballot.weight()), criteria, ballot.groundingFactIds()));
      }
      ranking.add(new Ranking(planId, scaled(score), scored.size(), plain(weightSum),
          abstained, breakdown));
    }

    ranking.sort(Comparator
        .comparing((Ranking r) -> exactScores.getOrDefault(r.planId(), BigDecimal.ZERO))
        .reversed()
        .thenComparing(Comparator.comparingInt(Ranking::voterCount).reversed())
        .thenComparing(Ranking::planId));

    String winner = null;
    if (!ranking.isEmpty()) {
      BigDecimal top = exactScores.get(ranking.get(0).planId());
      List<Ranking> tied = ranking.stream()
          .filter(r -> top.compareTo(exactScores.getOrDefault(r.planId(), BigDecimal.ZERO)) == 0)
          .toList();
      if (tied.size() == 1) {
        winner = tied.get(0).planId();
      } else {
        int maxVoters = tied.stream().mapToInt(Ranking::voterCount).max().orElse(0);
        List<Ranking> finalists = tied.stream()
            .filter(r -> r.voterCount() == maxVoters).toList();
        winner = finalists.stream().map(Ranking::planId).min(String::compareTo).orElse(null);
        if (finalists.size() > 1) {
          notices.add("榜首同分且评委数相同，按 planId 字典序裁决为 " + winner);
        } else {
          notices.add("榜首同分，按有效评委数裁决为 " + winner);
        }
      }
    }

    boolean homogeneous = homogeneous(validation.accepted());
    if (homogeneous) {
      notices.add("评委评分完全一致，投票未提供额外信息，请检查各评委是否用了独立 rubric");
    }
    return new VoteOutcome("1.0", "DECISION_ADVICE", winner, ranking, excluded,
        notices, validation.rejections(), homogeneous);
  }

  private static Validation validateBallots(List<BallotBatch> batches, List<String> planIds,
      Map<String, Rubric> rubrics) {
    List<Accepted> accepted = new ArrayList<>();
    List<Rejection> rejections = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (BallotBatch batch : batches) {
      String provider = batch.providerId();
      Rubric rubric = rubrics.get(provider);
      if (rubric == null) {
        rejections.add(new Rejection(provider, "", E_VOTE_RUBRIC, "评委未声明 rubric"));
        continue;
      }
      for (Map<String, Object> fact : batch.facts()) {
        String factId = str(fact.get("factId"));
        if (!FACT_KEYS.containsAll(fact.keySet())) {
          reject(factId, provider, rejections, "E_SCHEMA_ADDITIONAL", "Fact 出现契约之外的顶层字段");
          continue;
        }
        if (!"CONTENT".equals(str(fact.get("type")))) {
          reject(factId, provider, rejections, "E_SCHEMA_TYPE", "评分 Fact 的 type 必须是 CONTENT");
          continue;
        }
        Map<String, Object> value = asMap(fact.get("value"));
        if (!VALUE_KEYS.containsAll(value.keySet())) {
          reject(factId, provider, rejections, "E_SCHEMA_ADDITIONAL", "value 出现白名单之外的键");
          continue;
        }
        if (!SCORE_CATEGORY.equals(str(value.get("category")))) {
          reject(factId, provider, rejections, "E_SCHEMA_TYPE", "value.category 必须是 PLAN_SCORE");
          continue;
        }
        String planId = str(value.get("planId"));
        if (!planIds.contains(planId)) {
          reject(factId, provider, rejections, E_VOTE_UNKNOWNPLAN, "planId 不在本轮候选方案里");
          continue;
        }
        if (!provider.equals(str(value.get("rubric")))) {
          reject(factId, provider, rejections, E_VOTE_RUBRIC, "评委只能按自己声明的 rubric 打分");
          continue;
        }
        String decision = str(value.get("decision"));
        if (!DECISIONS.contains(decision)) {
          reject(factId, provider, rejections, "E_SCHEMA_TYPE", "decision 只能是 SCORE / ABSTAIN / VETO");
          continue;
        }
        Map<String, Object> evidence = asMap(fact.get("evidence"));
        if (!EVIDENCE_KEYS.containsAll(evidence.keySet())) {
          reject(factId, provider, rejections, "E_SCHEMA_ADDITIONAL", "evidence 出现白名单之外的键");
          continue;
        }
        if (!ALLOWED_STATUS.contains(str(evidence.get("verificationStatus")))) {
          reject(factId, provider, rejections, "E_SEMANTIC_VERIFIED", "外部 Provider 不得自称 verified");
          continue;
        }
        List<String> grounding = asStrings(value.get("groundingFactIds"));
        if (value.get("groundingFactIds") != null
            && !(value.get("groundingFactIds") instanceof List<?>)) {
          reject(factId, provider, rejections, "E_SCHEMA_TYPE", "groundingFactIds 必须是数组");
          continue;
        }
        if (grounding.stream().anyMatch(fid -> !batch.ownFactIds().contains(fid))) {
          reject(factId, provider, rejections, "E_SEMANTIC_GROUNDING", "引用了本评委未上报的 factId");
          continue;
        }
        if ("VETO".equals(decision)) {
          if (!rubric.allowVeto()) {
            reject(factId, provider, rejections, E_VOTE_RUBRIC, "该评委无否决权");
            continue;
          }
          if (grounding.isEmpty()) {
            reject(factId, provider, rejections, "E_SEMANTIC_GROUNDING", "硬约束否决必须给出事实依据");
            continue;
          }
        }
        Map<String, Object> rawScores = asMap(value.get("scores"));
        if (value.get("scores") != null && !(value.get("scores") instanceof Map<?, ?>)) {
          reject(factId, provider, rejections, "E_SCHEMA_TYPE", "scores 必须是对象");
          continue;
        }
        if (rawScores.keySet().stream().anyMatch(k -> !rubric.criteria().contains(k))) {
          reject(factId, provider, rejections, E_VOTE_RUBRIC, "出现该 rubric 未声明的评分项");
          continue;
        }
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        boolean badValue = false;
        for (Map.Entry<String, Object> entry : rawScores.entrySet()) {
          try {
            BigDecimal num = new BigDecimal(String.valueOf(entry.getValue()));
            if (num.compareTo(SCORE_MIN) < 0 || num.compareTo(SCORE_MAX) > 0) {
              badValue = true;
              break;
            }
            scores.put(entry.getKey(), num);
          } catch (NumberFormatException e) {
            badValue = true;
            break;
          }
        }
        if (badValue) {
          reject(factId, provider, rejections, "E_SCHEMA_LIMIT", "分项分数必须是 0 至 5 的数字");
          continue;
        }
        if ("SCORE".equals(decision) && scores.isEmpty()) {
          reject(factId, provider, rejections, "E_SCHEMA_REQUIRED", "打分票至少给一个评分项");
          continue;
        }
        if (!seen.add(provider + "\u0000" + planId)) {
          reject(factId, provider, rejections, E_VOTE_DUPLICATE, "同一评委对同一方案只能投一票");
          continue;
        }
        BigDecimal normalized = null;
        if (!scores.isEmpty()) {
          BigDecimal sum = BigDecimal.ZERO;
          for (BigDecimal num : scores.values()) {
            sum = sum.add(num);
          }
          normalized = sum.divide(BigDecimal.valueOf(scores.size()), 10,
              RoundingMode.HALF_EVEN);
        }
        accepted.add(new Accepted(provider, planId, decision, normalized,
            rubric.weight(), scores, grounding));
      }
    }
    return new Validation(accepted, rejections);
  }

  private static void reject(String factId, String provider, List<Rejection> out,
      String code, String detail) {
    out.add(new Rejection(provider, factId, code, detail));
  }

  private static boolean homogeneous(List<Accepted> accepted) {
    Map<String, List<BigDecimal>> byPlan = new LinkedHashMap<>();
    for (Accepted ballot : accepted) {
      if ("SCORE".equals(ballot.decision())) {
        byPlan.computeIfAbsent(ballot.planId(), k -> new ArrayList<>())
            .add(ballot.normalized());
      }
    }
    List<List<BigDecimal>> comparable = byPlan.values().stream()
        .filter(v -> v.size() >= 2).toList();
    if (comparable.isEmpty()) {
      return false;
    }
    for (List<BigDecimal> values : comparable) {
      BigDecimal first = values.get(0);
      for (BigDecimal value : values) {
        if (first.compareTo(value) != 0) {
          return false;
        }
      }
    }
    return true;
  }

  private static String scaled(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_EVEN).toPlainString();
  }

  /** 与 Python {@code str(Decimal(...))} 对齐：保留尾随零，不做 stripTrailingZeros。 */
  private static String plain(BigDecimal value) {
    return value.toPlainString();
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static List<String> asStrings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      out.add(str(item));
    }
    return out;
  }
}
