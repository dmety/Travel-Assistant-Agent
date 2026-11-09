package com.travelassistant.server.agent.vote;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.BallotBatch;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.Ranking;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.Rejection;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.Rubric;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.VoteOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Java 侧与 {@code tools/agent/plan_vote.py} 的 22 个自测逐条等价。
 * 用同一组评委、方案和分数，断言同样的排名与得分，任一处规则漂移都会在这里暴露。
 */
class PlanVoteTallyTest {

  private static final List<String> PLANS = List.of("A", "B", "C");
  private static final Map<String, Rubric> RUBRICS = rubrics();

  private static Map<String, Rubric> rubrics() {
    Map<String, Rubric> out = new LinkedHashMap<>();
    out.put("budget.auditor", rubric("budget.auditor", "1.2", true,
        "totalCost", "feeDisclosure"));
    out.put("food.recommender", rubric("food.recommender", "1.0", false,
        "restaurantQuality", "dietaryCoverage"));
    out.put("transit.planner", rubric("transit.planner", "1.0", false,
        "transferCount", "walkDistance"));
    out.put("weather.guard", rubric("weather.guard", "0.8", false,
        "indoorBackup", "rainRisk"));
    out.put("energy.guard", rubric("energy.guard", "1.0", true,
        "walkLoad", "restSlots"));
    return out;
  }

  private static Rubric rubric(String provider, String weight, boolean allowVeto,
      String... criteria) {
    return new Rubric(provider, List.of(criteria), new BigDecimal(weight), allowVeto);
  }

  private static Map<String, Object> scores(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((String) pairs[i], pairs[i + 1]);
    }
    return out;
  }

  private static Map<String, Object> fact(String provider, String plan,
      Map<String, Object> values, List<String> grounding, String decision) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("category", "PLAN_SCORE");
    value.put("planId", plan);
    value.put("rubric", provider);
    value.put("scores", values);
    value.put("groundingFactIds", grounding);
    value.put("decision", decision);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("sourceKind", "EXTERNAL");
    evidence.put("verificationStatus", "unverified");
    evidence.put("fetchedAt", "2026-11-09T06:00:00Z");
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("factId", "score:" + provider + ":" + plan);
    fact.put("type", "CONTENT");
    fact.put("value", value);
    fact.put("evidence", evidence);
    return fact;
  }

  private static Set<String> own(String provider) {
    String prefix = provider.split("\\.")[0];
    Set<String> out = new LinkedHashSet<>();
    for (String plan : PLANS) {
      out.add("f-" + prefix + "-" + plan);
    }
    return out;
  }

  private static List<String> grounding(String provider, String plan) {
    return List.of("f-" + provider.split("\\.")[0] + "-" + plan);
  }

  private static BallotBatch batch(String provider, List<Map<String, Object>> facts) {
    return new BallotBatch(provider, facts, own(provider));
  }

  /** 五个评委按各自 rubric 打分，B 被体力硬约束否决，体力对 C 弃票。 */
  private static List<BallotBatch> heterogeneous() {
    List<BallotBatch> out = new ArrayList<>();
    out.add(batch("budget.auditor", List.of(
        vote("budget.auditor", "A", "totalCost", 5, "feeDisclosure", 4),
        vote("budget.auditor", "B", "totalCost", 2, "feeDisclosure", 3),
        vote("budget.auditor", "C", "totalCost", 1, "feeDisclosure", 5))));
    out.add(batch("food.recommender", List.of(
        vote("food.recommender", "A", "restaurantQuality", 2, "dietaryCoverage", 3),
        vote("food.recommender", "B", "restaurantQuality", 5, "dietaryCoverage", 5),
        vote("food.recommender", "C", "restaurantQuality", 4, "dietaryCoverage", 4))));
    out.add(batch("transit.planner", List.of(
        vote("transit.planner", "A", "transferCount", 5, "walkDistance", 4),
        vote("transit.planner", "B", "transferCount", 1, "walkDistance", 1),
        vote("transit.planner", "C", "transferCount", 3, "walkDistance", 4))));
    out.add(batch("weather.guard", List.of(
        vote("weather.guard", "A", "indoorBackup", 1, "rainRisk", 2),
        vote("weather.guard", "B", "indoorBackup", 4, "rainRisk", 4),
        vote("weather.guard", "C", "indoorBackup", 5, "rainRisk", 5))));
    List<Map<String, Object>> energy = new ArrayList<>();
    energy.add(vote("energy.guard", "A", "walkLoad", 4, "restSlots", 3));
    energy.add(fact("energy.guard", "B", Map.of(),
        grounding("energy.guard", "B"), "VETO"));
    Map<String, Object> abstain = fact("energy.guard", "C", Map.of(),
        List.of(), "ABSTAIN");
    abstain.put("factId", "score:energy.guard:C:abstain");
    energy.add(abstain);
    out.add(batch("energy.guard", energy));
    return out;
  }

  private static Map<String, Object> vote(String provider, String plan,
      String c1, Object v1, String c2, Object v2) {
    return fact(provider, plan, scores(c1, v1, c2, v2), grounding(provider, plan), "SCORE");
  }

  private static List<BallotBatch> homogeneous() {
    Map<String, Integer> shared = Map.of("A", 4, "B", 3, "C", 5);
    List<BallotBatch> out = new ArrayList<>();
    for (Map.Entry<String, Rubric> entry : RUBRICS.entrySet()) {
      String provider = entry.getKey();
      List<String> criteria = entry.getValue().criteria();
      List<Map<String, Object>> facts = new ArrayList<>();
      for (String plan : PLANS) {
        facts.add(fact(provider, plan,
            scores(criteria.get(0), shared.get(plan), criteria.get(1), shared.get(plan)),
            grounding(provider, plan), "SCORE"));
      }
      out.add(batch(provider, facts));
    }
    return out;
  }

  private static VoteOutcome tally(List<BallotBatch> batches) {
    return PlanVoteTally.tally(batches, PLANS, RUBRICS);
  }

  private static Ranking rank(VoteOutcome outcome, String planId) {
    return outcome.ranking().stream().filter(r -> r.planId().equals(planId))
        .findFirst().orElseThrow();
  }

  private static List<String> codes(VoteOutcome outcome) {
    return outcome.rejections().stream().map(Rejection::code).toList();
  }

  @Test
  void heterogeneousBallotsHaveNoRejections() {
    assertThat(tally(heterogeneous()).rejections()).isEmpty();
  }

  @Test
  void vetoExcludesPlanB() {
    VoteOutcome outcome = tally(heterogeneous());
    assertThat(outcome.excluded().stream()
        .map(PlanVoteTally.Excluded::planId).toList()).containsExactly("B");
  }

  @Test
  void heterogeneousIsNotFlaggedAsHomogeneous() {
    assertThat(tally(heterogeneous()).homogeneous()).isFalse();
  }

  @Test
  void winnerIsC() {
    assertThat(tally(heterogeneous()).winner()).isEqualTo("C");
  }

  @Test
  void abstainDoesNotEnterDenominator() {
    assertThat(rank(tally(heterogeneous()), "C").weightSum()).isEqualTo("4.0");
  }

  @Test
  void cWeightedScoreIs3Point7750() {
    assertThat(rank(tally(heterogeneous()), "C").score()).isEqualTo("3.7750");
  }

  @Test
  void aWeightedScoreIs3Point4200() {
    assertThat(rank(tally(heterogeneous()), "A").score()).isEqualTo("3.4200");
  }

  @Test
  void breakdownCarriesCriteriaAndGrounding() {
    PlanVoteTally.Breakdown breakdown = rank(tally(heterogeneous()), "C")
        .breakdown().get(0);
    assertThat(breakdown.criteria()).containsKeys("totalCost", "feeDisclosure");
    assertThat(breakdown.groundingFactIds()).containsExactly("f-budget-C");
  }

  @Test
  void repeatedTallyIsReproducible() {
    assertThat(tally(heterogeneous()).ranking()).isEqualTo(tally(heterogeneous()).ranking());
  }

  @Test
  void singleViewPicksAButFullVotePicksC() {
    List<BallotBatch> onlyBudget = List.of(heterogeneous().get(0));
    assertThat(PlanVoteTally.tally(onlyBudget, PLANS,
        Map.of("budget.auditor", RUBRICS.get("budget.auditor"))).winner()).isEqualTo("A");
    assertThat(tally(heterogeneous()).winner()).isEqualTo("C");
  }

  @Test
  void homogeneousBallotsAreDetected() {
    assertThat(tally(homogeneous()).homogeneous()).isTrue();
  }

  @Test
  void homogeneousAddsDegradationNotice() {
    assertThat(tally(homogeneous()).notices())
        .anyMatch(n -> n.contains("投票未提供额外信息"));
  }

  @Test
  void normalBallotIsAccepted() {
    VoteOutcome outcome = tally(List.of(batch("budget.auditor",
        List.of(vote("budget.auditor", "A", "totalCost", 5, "feeDisclosure", 4)))));
    assertThat(outcome.rejections()).isEmpty();
    assertThat(outcome.winner()).isEqualTo("A");
  }

  @Test
  void referencingAnotherProvidersFactIsRejected() {
    Map<String, Object> foreign = fact("budget.auditor", "A",
        scores("totalCost", 5), List.of("f-food-A"), "SCORE");
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(foreign))))))
        .contains("E_SEMANTIC_GROUNDING");
  }

  @Test
  void selfClaimingVerifiedIsRejected() {
    Map<String, Object> f = vote("budget.auditor", "A", "totalCost", 5, "feeDisclosure", 4);
    asMap(f.get("evidence")).put("verificationStatus", "verified");
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_SEMANTIC_VERIFIED");
  }

  @Test
  void scoreAboveFiveIsRejected() {
    Map<String, Object> f = vote("budget.auditor", "A", "totalCost", 6, "feeDisclosure", 4);
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_SCHEMA_LIMIT");
  }

  @Test
  void undeclaredCriterionIsRejected() {
    Map<String, Object> f = vote("budget.auditor", "A", "totalCost", 5, "rainRisk", 4);
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_VOTE_RUBRIC");
  }

  @Test
  void unknownPlanIsRejected() {
    Map<String, Object> f = vote("budget.auditor", "D", "totalCost", 5, "feeDisclosure", 4);
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_VOTE_UNKNOWNPLAN");
  }

  @Test
  void extraTopLevelFieldIsRejected() {
    Map<String, Object> f = vote("budget.auditor", "A", "totalCost", 5, "feeDisclosure", 4);
    f.put("unexpected", "x");
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_SCHEMA_ADDITIONAL");
  }

  @Test
  void duplicateBallotIsRejected() {
    Map<String, Object> first = vote("budget.auditor", "A", "totalCost", 5, "feeDisclosure", 4);
    Map<String, Object> second = vote("budget.auditor", "A", "totalCost", 3, "feeDisclosure", 3);
    second.put("factId", "score:budget.auditor:A:again");
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(first, second))))))
        .contains("E_VOTE_DUPLICATE");
  }

  @Test
  void vetoWithoutGroundingIsRejected() {
    Map<String, Object> f = fact("budget.auditor", "A", Map.of(), List.of(), "VETO");
    assertThat(codes(tally(List.of(batch("budget.auditor", List.of(f))))))
        .contains("E_SEMANTIC_GROUNDING");
  }

  @Test
  void tieIsBrokenByPlanIdLexicographicOrder() {
    Map<String, Rubric> two = Map.of("p.a", rubric("p.a", "1.0", false, "cost"));
    List<String> plans = List.of("B", "A");
    Map<String, Object> a = fact("p.a", "A", scores("cost", 4), List.of(), "SCORE");
    Map<String, Object> b = fact("p.a", "B", scores("cost", 4), List.of(), "SCORE");
    VoteOutcome outcome = PlanVoteTally.tally(
        List.of(new BallotBatch("p.a", List.of(a, b), Set.of())), plans, two);
    assertThat(outcome.winner()).isEqualTo("A");
    assertThat(outcome.notices()).anyMatch(n -> n.contains("planId 字典序"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }
}
