# -*- coding: utf-8 -*-
"""方案投票计票原型（范式 B，离线 POC）。

只验证「结构化评分 + 确定性计票」的规则能否跑出可复核结果。
不接入 Java 运行时，不是 AgentProvider 实现，不参与构建与接口。

评分沿用《Agent接入规范》响应契约：Fact.type 只有 CONTENT，
用 value.category=PLAN_SCORE 承载；evidence.verificationStatus
只允许 unverified / pending；Fact 顶层不新增字段，避免 E_SCHEMA_ADDITIONAL。
运行：python tools/agent/plan_vote.py
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

SCORE_CATEGORY = "PLAN_SCORE"
ALLOWED_STATUS = {"unverified", "pending"}
FACT_KEYS = {"factId", "type", "value", "evidence"}
VALUE_KEYS = {"category", "planId", "rubric", "scores", "groundingFactIds", "decision"}
EVIDENCE_KEYS = {"sourceKind", "sourceId", "verificationStatus", "fetchedAt", "excerpt"}
DECISIONS = {"SCORE", "ABSTAIN", "VETO"}
SCORE_MIN = Decimal("0")
SCORE_MAX = Decimal("5")

# 原型新增的原因码。落地前须确认是否纳入《Agent接入规范》的原因码表，
# 那属于规范版本升级项，不在本原型里假装已经是稳定接口。
NEW_CODES = {"E_VOTE_DUPLICATE", "E_VOTE_RUBRIC", "E_VOTE_UNKNOWNPLAN"}


@dataclass(frozen=True)
class Rubric:
    """评委声明：只按自己的 criteria 打分，weight 决定计票权重。"""

    provider_id: str
    criteria: tuple[str, ...]
    weight: str = "1.0"
    allow_veto: bool = True

    def __post_init__(self) -> None:
        if Decimal(self.weight) <= 0:
            raise ValueError("weight must be positive")


@dataclass(frozen=True)
class BallotBatch:
    """一个评委返回的一批 facts。

    provider_id 由编排层按请求来源记录，不写进 Fact，保持契约形状不变。
    own_fact_ids 是该评委在征集阶段上报过的 factId 集合，评分只能引用它们。
    """

    provider_id: str
    facts: list[dict[str, Any]]
    own_fact_ids: tuple[str, ...]


def _reject(provider_id: str, fact: dict, code: str, detail: str) -> dict:
    return {
        "providerId": provider_id,
        "factId": fact.get("factId", ""),
        "code": code,
        "detail": detail,
    }


def validate_ballots(
    batches: list[BallotBatch],
    plan_ids: list[str],
    rubrics: dict[str, Rubric],
) -> tuple[list[dict], list[dict]]:
    """校验评分票，返回 (通过的票, 拒绝报告)。默认拒绝，不做静默降级。"""
    accepted: list[dict] = []
    rejections: list[dict] = []
    seen: set[tuple[str, str]] = set()

    for batch in batches:
        provider = batch.provider_id
        rubric = rubrics.get(provider)
        if rubric is None:
            rejections.append(_reject(provider, {}, "E_VOTE_RUBRIC", "评委未声明 rubric"))
            continue
        own = set(batch.own_fact_ids)

        for fact in batch.facts:
            r = lambda code, detail: rejections.append(_reject(provider, fact, code, detail))

            if set(fact) - FACT_KEYS:
                r("E_SCHEMA_ADDITIONAL", "Fact 出现契约之外的顶层字段")
                continue
            if fact.get("type") != "CONTENT":
                r("E_SCHEMA_TYPE", "评分 Fact 的 type 必须是 CONTENT")
                continue
            value = fact.get("value") or {}
            if set(value) - VALUE_KEYS:
                r("E_SCHEMA_ADDITIONAL", "value 出现白名单之外的键")
                continue
            if value.get("category") != SCORE_CATEGORY:
                r("E_SCHEMA_TYPE", "value.category 必须是 PLAN_SCORE")
                continue

            plan_id = value.get("planId")
            if plan_id not in plan_ids:
                r("E_VOTE_UNKNOWNPLAN", "planId 不在本轮候选方案里")
                continue
            if value.get("rubric") != provider:
                r("E_VOTE_RUBRIC", "评委只能按自己声明的 rubric 打分")
                continue

            decision = value.get("decision")
            if decision not in DECISIONS:
                r("E_SCHEMA_TYPE", "decision 只能是 SCORE / ABSTAIN / VETO")
                continue

            evidence = fact.get("evidence") or {}
            if set(evidence) - EVIDENCE_KEYS:
                r("E_SCHEMA_ADDITIONAL", "evidence 出现白名单之外的键")
                continue
            if evidence.get("verificationStatus") not in ALLOWED_STATUS:
                r("E_SEMANTIC_VERIFIED", "外部 Provider 不得自称 verified")
                continue

            grounding = value.get("groundingFactIds") or []
            if not isinstance(grounding, list):
                r("E_SCHEMA_TYPE", "groundingFactIds 必须是数组")
                continue
            if any(fid not in own for fid in grounding):
                r("E_SEMANTIC_GROUNDING", "引用了本评委未上报的 factId")
                continue
            if decision == "VETO":
                if not rubric.allow_veto:
                    r("E_VOTE_RUBRIC", "该评委无否决权")
                    continue
                if not grounding:
                    r("E_SEMANTIC_GROUNDING", "硬约束否决必须给出事实依据")
                    continue

            scores = value.get("scores") or {}
            if not isinstance(scores, dict):
                r("E_SCHEMA_TYPE", "scores 必须是对象")
                continue
            if set(scores) - set(rubric.criteria):
                r("E_VOTE_RUBRIC", "出现该 rubric 未声明的评分项")
                continue
            bad_value = False
            for key, raw in scores.items():
                try:
                    num = Decimal(str(raw))
                except Exception:
                    bad_value = True
                    break
                if not (SCORE_MIN <= num <= SCORE_MAX):
                    bad_value = True
                    break
            if bad_value:
                r("E_SCHEMA_LIMIT", "分项分数必须是 0 至 5 的数字")
                continue
            if decision == "SCORE" and not scores:
                r("E_SCHEMA_REQUIRED", "打分票至少给一个评分项")
                continue

            key = (provider, plan_id)
            if key in seen:
                r("E_VOTE_DUPLICATE", "同一评委对同一方案只能投一票")
                continue
            seen.add(key)

            normalized = (
                sum(Decimal(str(v)) for v in scores.values()) / Decimal(len(scores))
                if scores else None
            )
            accepted.append({
                "providerId": provider,
                "planId": plan_id,
                "decision": decision,
                "normalized": normalized,
                "weight": Decimal(rubric.weight),
                "criteria": dict(scores),
                "groundingFactIds": list(grounding),
            })

    return accepted, rejections


def _homogeneous(accepted: list[dict]) -> bool:
    """评委打分完全无分歧时返回 True。此时投票不提供额外信息。"""
    by_plan: dict[str, list[Decimal]] = defaultdict(list)
    for b in accepted:
        if b["decision"] == "SCORE":
            by_plan[b["planId"]].append(b["normalized"])
    comparable = [vals for vals in by_plan.values() if len(vals) >= 2]
    if not comparable:
        return False
    return all(len(set(vals)) == 1 for vals in comparable)


def tally(
    batches: list[BallotBatch],
    plan_ids: list[str],
    rubrics: dict[str, Rubric],
) -> dict:
    """确定性计票。弃票不进分母，否决硬剔除，平局按固定规则裁决。"""
    accepted, rejections = validate_ballots(batches, plan_ids, rubrics)
    by_plan: dict[str, list[dict]] = defaultdict(list)
    for b in accepted:
        by_plan[b["planId"]].append(b)

    ranking: list[dict] = []
    excluded: list[dict] = []
    notices: list[str] = []

    for plan_id in plan_ids:
        ballots = by_plan.get(plan_id, [])
        vetoes = [b for b in ballots if b["decision"] == "VETO"]
        if vetoes:
            excluded.append({
                "planId": plan_id,
                "reason": "VETO",
                "providers": [b["providerId"] for b in vetoes],
                "groundingFactIds": sorted({
                    fid for b in vetoes for fid in b["groundingFactIds"]
                }),
            })
            notices.append("方案 %s 命中硬约束，由 %s 否决，不参与计票" % (
                plan_id, "、".join(b["providerId"] for b in vetoes)))
            continue

        scored = [b for b in ballots if b["decision"] == "SCORE"]
        abstained = [b["providerId"] for b in ballots if b["decision"] == "ABSTAIN"]
        silent = [p for p in rubrics if p not in {b["providerId"] for b in ballots}]
        if abstained:
            notices.append("方案 %s 有 %d 位评委弃票，不计入分母" % (plan_id, len(abstained)))
        if silent:
            notices.append("方案 %s 有 %d 位评委未交票" % (plan_id, len(silent)))

        if not scored:
            notices.append("方案 %s 无有效评分，不参与排名" % plan_id)
            continue

        weight_sum = sum((b["weight"] for b in scored), Decimal("0"))
        weighted = sum((b["normalized"] * b["weight"] for b in scored), Decimal("0"))
        score = weighted / weight_sum if weight_sum > 0 else Decimal("0")

        ranking.append({
            "planId": plan_id,
            "score": score,
            "voterCount": len(scored),
            "weightSum": weight_sum,
            "abstained": abstained,
            "breakdown": [
                {
                    "providerId": b["providerId"],
                    "normalized": b["normalized"],
                    "weight": b["weight"],
                    "criteria": b["criteria"],
                    "groundingFactIds": b["groundingFactIds"],
                }
                for b in scored
            ],
        })

    # 分数降序 -> 有效评委数降序 -> planId 字典序。三步全确定性，可复现。
    ranking.sort(key=lambda r: (-r["score"], -r["voterCount"], r["planId"]))
    winner = None
    if ranking:
        top = ranking[0]["score"]
        tied = [r for r in ranking if r["score"] == top]
        if len(tied) == 1:
            winner = tied[0]["planId"]
        else:
            max_voters = max(r["voterCount"] for r in tied)
            finalists = [r for r in tied if r["voterCount"] == max_voters]
            winner = min(r["planId"] for r in finalists)
            if len(finalists) > 1:
                notices.append("榜首同分且评委数相同，按 planId 字典序裁决为 %s" % winner)
            else:
                notices.append("榜首同分，按有效评委数裁决为 %s" % winner)

    homogeneous = _homogeneous(accepted)
    if homogeneous:
        notices.append("评委评分完全一致，投票未提供额外信息，请检查各评委是否用了独立 rubric")

    return {
        "schemaVersion": "1.0",
        "intent": "DECISION_ADVICE",
        "winner": winner,
        "ranking": [
            {
                "planId": r["planId"],
                "score": str(r["score"].quantize(Decimal("0.0001"))),
                "voterCount": r["voterCount"],
                "weightSum": str(r["weightSum"]),
                "abstained": r["abstained"],
                "breakdown": [
                    {
                        "providerId": b["providerId"],
                        "normalized": str(b["normalized"].quantize(Decimal("0.0001"))),
                        "weight": str(b["weight"]),
                        "criteria": {k: str(Decimal(str(v))) for k, v in b["criteria"].items()},
                        "groundingFactIds": b["groundingFactIds"],
                    }
                    for b in r["breakdown"]
                ],
            }
            for r in ranking
        ],
        "excluded": excluded,
        "notices": notices,
        "rejections": rejections,
        "homogeneous": homogeneous,
    }


def _fact(provider: str, plan_id: str, scores: dict, grounding: list[str],
          decision: str = "SCORE", **overrides) -> dict:
    value = {
        "category": SCORE_CATEGORY,
        "planId": plan_id,
        "rubric": provider,
        "scores": scores,
        "groundingFactIds": grounding,
        "decision": decision,
    }
    fact = {
        "factId": "score:%s:%s" % (provider, plan_id),
        "type": "CONTENT",
        "value": value,
        "evidence": {
            "sourceKind": "EXTERNAL",
            "verificationStatus": "unverified",
            "fetchedAt": "2026-11-09T06:00:00Z",
        },
    }
    fact.update(overrides)
    return fact


RUBRICS = {
    "budget.auditor": Rubric("budget.auditor", ("totalCost", "feeDisclosure"), "1.2"),
    "food.recommender": Rubric("food.recommender", ("restaurantQuality", "dietaryCoverage"), "1.0", False),
    "transit.planner": Rubric("transit.planner", ("transferCount", "walkDistance"), "1.0", False),
    "weather.guard": Rubric("weather.guard", ("indoorBackup", "rainRisk"), "0.8", False),
    "energy.guard": Rubric("energy.guard", ("walkLoad", "restSlots"), "1.0"),
}
PLANS = ["A", "B", "C"]
OWN_FACTS = {
    p: tuple("f-%s-%s" % (p.split(".")[0], plan) for plan in PLANS)
    for p in RUBRICS
}


def heterogeneous_batches() -> list[BallotBatch]:
    """五个评委按各自 rubric 打分，B 被体力硬约束否决，energy 对 C 弃票。"""
    rows = {
        "budget.auditor": {"A": (5, 4), "B": (2, 3), "C": (1, 5)},
        "food.recommender": {"A": (2, 3), "B": (5, 5), "C": (4, 4)},
        "transit.planner": {"A": (5, 4), "B": (1, 1), "C": (3, 4)},
        "weather.guard": {"A": (1, 2), "B": (4, 4), "C": (5, 5)},
        "energy.guard": {"A": (4, 3)},
    }
    batches = []
    for provider, per_plan in rows.items():
        criteria = RUBRICS[provider].criteria
        facts = []
        for plan, values in per_plan.items():
            facts.append(_fact(
                provider, plan, dict(zip(criteria, values)),
                ["f-%s-%s" % (provider.split(".")[0], plan)],
            ))
        if provider == "energy.guard":
            facts.append(_fact(
                provider, "B", {}, ["f-energy-B"], decision="VETO",
            ))
            facts.append(_abstain(provider, "C"))
        batches.append(BallotBatch(provider, facts, OWN_FACTS[provider]))
    return batches


def _abstain(provider: str, plan_id: str) -> dict:
    f = _fact(provider, plan_id, {}, [], decision="ABSTAIN")
    f["factId"] = "score:%s:%s:abstain" % (provider, plan_id)
    return f


def homogeneous_batches() -> list[BallotBatch]:
    """所有评委给同一套分数，用来验证「同构投票」能被识别。"""
    shared = {"A": 4, "B": 3, "C": 5}
    batches = []
    for provider, rubric in RUBRICS.items():
        facts = [
            _fact(
                provider, plan,
                {c: shared[plan] for c in rubric.criteria},
                ["f-%s-%s" % (provider.split(".")[0], plan)],
            )
            for plan in PLANS
        ]
        batches.append(BallotBatch(provider, facts, OWN_FACTS[provider]))
    return batches


def run_self_test() -> int:
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, note: str = "") -> None:
        checks.append((name, ok, note))

    result = tally(heterogeneous_batches(), PLANS, RUBRICS)
    check("异质评委无契约违规", not result["rejections"],
          "rejections=%d" % len(result["rejections"]))
    check("B 被硬约束剔除", [e["planId"] for e in result["excluded"]] == ["B"])
    check("异质性未被误判", result["homogeneous"] is False)
    check("推荐 C", result["winner"] == "C", "winner=%s" % result["winner"])

    score_c = next((r for r in result["ranking"] if r["planId"] == "C"), None)
    # C 的 energy 评委弃票，分母只含其余四位：1.2 + 1.0 + 1.0 + 0.8 = 4.0
    check("弃票不计入分母", score_c is not None and score_c["weightSum"] == "4.0",
          "weightSum=%s" % (score_c["weightSum"] if score_c else "n/a"))
    check("C 加权分 3.7750", score_c is not None and score_c["score"] == "3.7750",
          "score=%s" % (score_c["score"] if score_c else "n/a"))
    score_a = next((r for r in result["ranking"] if r["planId"] == "A"), None)
    check("A 加权分 3.4200", score_a is not None and score_a["score"] == "3.4200",
          "score=%s" % (score_a["score"] if score_a else "n/a"))
    check("breakdown 只给结构化分项",
          all("criteria" in b and "groundingFactIds" in b
              for r in result["ranking"] for b in r["breakdown"]))

    again = tally(heterogeneous_batches(), PLANS, RUBRICS)
    check("重复运行结果一致", again["winner"] == result["winner"]
          and [r["score"] for r in again["ranking"]] == [r["score"] for r in result["ranking"]])

    # 单视角对照：只看预算评委，A 最省钱会胜出；全员投票后 C 胜，
    # 说明其余评委的视角确实改变了结论，投票不是同义重复。
    budget_batches = [
        b for b in heterogeneous_batches() if b.provider_id == "budget.auditor"
    ]
    budget_only = tally(budget_batches, PLANS, {"budget.auditor": RUBRICS["budget.auditor"]})
    check("单视角选 A，全员投票选 C",
          budget_only["winner"] == "A" and result["winner"] == "C",
          "single=%s all=%s" % (budget_only["winner"], result["winner"]))

    homo = tally(homogeneous_batches(), PLANS, RUBRICS)
    check("同构评委被识别", homo["homogeneous"] is True)
    check("同构给出退化提示",
          any("未提供额外信息" in n for n in homo["notices"]))

    # 负例：逐条构造单张坏票，确认被对应原因码拦下
    def single(fact: dict, provider: str = "budget.auditor") -> list[dict]:
        batch = BallotBatch(provider, [fact], OWN_FACTS[provider])
        _, rejected = validate_ballots([batch], PLANS, RUBRICS)
        return [r["code"] for r in rejected]

    good = _fact("budget.auditor", "A", {"totalCost": 5}, ["f-budget-A"])
    check("正常票通过", single(good) == [])
    check("引用他人 factId 触发 grounding",
          "E_SEMANTIC_GROUNDING" in single(
              _fact("budget.auditor", "A", {"totalCost": 5}, ["f-food-A"])))
    verified = dict(good)
    verified["evidence"] = dict(good["evidence"], verificationStatus="verified")
    check("自称 verified 被拒", "E_SEMANTIC_VERIFIED" in single(verified))
    check("分数越界被拒", "E_SCHEMA_LIMIT" in single(
        _fact("budget.auditor", "A", {"totalCost": 6}, ["f-budget-A"])))
    check("未声明评分项被拒", "E_VOTE_RUBRIC" in single(
        _fact("budget.auditor", "A", {"scenery": 4}, ["f-budget-A"])))
    check("未知方案被拒", "E_VOTE_UNKNOWNPLAN" in single(
        _fact("budget.auditor", "D", {"totalCost": 4}, ["f-budget-D"])))
    extra = dict(good, extraField=1)
    check("多余顶层字段被拒", "E_SCHEMA_ADDITIONAL" in single(extra))

    dup_batch = BallotBatch("budget.auditor", [good, dict(good)], OWN_FACTS["budget.auditor"])
    _, dup_rejected = validate_ballots([dup_batch], PLANS, RUBRICS)
    check("重复票被拒", any(r["code"] == "E_VOTE_DUPLICATE" for r in dup_rejected))
    veto_no_evidence = _fact("energy.guard", "A", {}, [], decision="VETO")
    check("无依据否决被拒",
          "E_SEMANTIC_GROUNDING" in single(veto_no_evidence, "energy.guard"))

    # 平局：两位评委给 A 与 C 同分、同评委数，按 planId 字典序裁决
    tie_rubrics = {
        "a.judge": Rubric("a.judge", ("x",), "1.0", False),
        "b.judge": Rubric("b.judge", ("x",), "1.0", False),
    }
    tie_own = {"a.judge": ("f-a-A", "f-a-C"), "b.judge": ("f-b-A", "f-b-C")}
    tie_batches = [
        BallotBatch(p, [
            _fact(p, plan, {"x": 4}, ["f-%s-%s" % (p[0], plan)])
            for plan in ("A", "C")
        ], tie_own[p])
        for p in tie_rubrics
    ]
    tie = tally(tie_batches, ["A", "C"], tie_rubrics)
    check("平局按字典序确定性裁决", tie["winner"] == "A", "winner=%s" % tie["winner"])

    passed = sum(1 for _, ok, _ in checks if ok)
    print("== 方案投票原型自测 ==")
    for name, ok, note in checks:
        print("%s  %s%s" % ("PASS" if ok else "FAIL", name,
                           ("  [%s]" % note) if note else ""))
    print("-- %d/%d 通过 --" % (passed, len(checks)))
    print("winner=%s excluded=%s homogeneous=%s" % (
        result["winner"], [e["planId"] for e in result["excluded"]],
        result["homogeneous"]))
    for r in result["ranking"]:
        print("  %s  score=%s  voters=%d  weightSum=%s" % (
            r["planId"], r["score"], r["voterCount"], r["weightSum"]))
    for n in result["notices"]:
        print("  notice: %s" % n)
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())
