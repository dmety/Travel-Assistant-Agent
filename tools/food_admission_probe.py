# -*- coding: utf-8 -*-
"""餐饮准入九条探针：对 ``FOOD_RECOMMEND`` 的响应信封做离线合规检查。

美食链路最容易出的问题不是「查不到」，而是查到了以后越界：替用户下单、给出
来路不明的点评外链、把直线距离说成步行时间、静默剔除候选。这些都不是数据问题，
是响应姿态问题，可以完全离线校验，不需要真实地图密钥。

九条探针（全部来自《美食 Agent 架构设计》的不可放开项）：

1. ``NO_WRITE``      不得声明已完成下单、预订、支付或退款。
2. ``NO_LINK``       本版本不生成评价外链，``reviewLinks`` 必须为空数组或缺省。
3. ``USABLE``        每条候选必须有名称与 ``foodId``，缺一即不可用。
4. ``ID_UNIQUE``     同一次响应内 ``foodId`` 不得重复。
5. ``DISTANCE_KIND`` 有 ``distanceMeters`` 就必须在 notices 里声明是直线距离。
6. ``RATING_SRC``    给了 ``rating`` 就必须同时给 ``ratingSource``。
7. ``ZERO_REASON``   ``facts`` 为空时必须给出原因，不能静默返回空。
8. ``FILTER_LOUD``   notices 里出现「剔除 / 超出预算 / 超出半径」时必须说明条数或口径。
9. ``NO_UGC``        候选里不得塞评价正文类字段（``reviews`` / ``comments`` / ``content``）。

用法::

    python -B tools/food_admission_probe.py --file resp.json
    python -B tools/food_admission_probe.py --file resp.json --json
    python -B tools/food_admission_probe.py --self-test

退出码：全部通过 0；有 FAIL 为 1；输入不可读为 2。
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

WRITE_WORDS = ("已下单", "已预订", "已预定", "已支付", "已付款", "已出票", "已退", "帮你订好",
               "已经买好", "已购买", "订单已")
UGC_FIELDS = ("reviews", "comments", "reviewContent", "commentContent", "ugc", "content")


def unwrap(payload):
    """接受三种输入：业务信封、AgentResult、或直接是 {facts, notices}。"""
    if not isinstance(payload, dict):
        return [], [], "", []
    if isinstance(payload.get("result"), dict):
        inner = payload["result"]
        return (inner.get("facts") or [], inner.get("notices") or [],
                inner.get("advice") or "", inner.get("unknownFields") or [])
    return (payload.get("facts") or [], payload.get("notices") or [],
            payload.get("advice") or "", payload.get("unknownFields") or [])


def food_values(facts):
    values = []
    for fact in facts:
        if not isinstance(fact, dict):
            continue
        if fact.get("type") != "CONTENT":
            continue
        value = fact.get("value")
        if isinstance(value, dict) and value.get("category") == "FOOD":
            values.append(value)
    return values


def check_no_write(advice, notices, values):
    blob = advice + "".join(str(n) for n in notices)
    blob += "".join(str(v.get("constraintNotes") or "") for v in values)
    hits = [w for w in WRITE_WORDS if w in blob]
    return (not hits, "未声明交易动作" if not hits else "出现交易动作措辞：" + "、".join(hits))


def check_no_link(values):
    bad = [v.get("name") for v in values if v.get("reviewLinks")]
    return (not bad, "未生成评价外链" if not bad
            else "有 %d 条候选带了 reviewLinks：%s" % (len(bad), "、".join(map(str, bad[:3]))))


def check_usable(values):
    bad = [v.get("name") or "(无名)" for v in values
           if not (v.get("name") and str(v.get("name")).strip()) or not v.get("foodId")]
    return (not bad, "候选均有名称与 foodId" if not bad
            else "缺名称或 foodId 的候选 %d 条：%s" % (len(bad), "、".join(map(str, bad[:3]))))


def check_id_unique(values):
    seen, dup = set(), []
    for v in values:
        fid = v.get("foodId")
        if fid in seen:
            dup.append(fid)
        seen.add(fid)
    return (not dup, "foodId 全部唯一" if not dup
            else "重复 foodId %d 个：%s" % (len(dup), "、".join(map(str, dup[:3]))))


def check_distance_kind(values, notices):
    has_distance = any(v.get("distanceMeters") is not None for v in values)
    if not has_distance:
        return (True, "本响应无距离字段，无需声明")
    declared = any("直线" in str(n) for n in notices)
    return (declared, "已声明直线距离" if declared
            else "给了 distanceMeters 但 notices 里没有「直线」字样，容易被当成步行距离")


def check_rating_source(values):
    bad = [v.get("name") for v in values
           if v.get("rating") is not None and not v.get("ratingSource")]
    return (not bad, "有评分即有来源" if not bad
            else "%d 条候选有 rating 但缺 ratingSource：%s" % (len(bad), "、".join(map(str, bad[:3]))))


def check_zero_reason(facts, values, notices):
    if values:
        return (True, "本响应有候选 %d 条" % len(values))
    reason = any(k in str(n) for n in notices for k in ("未检索到", "没有", "不可用", "缺少", "未配置"))
    return (reason, "零候选已给出原因" if reason else "facts 为空但 notices 没有说明原因")


def check_filter_loud(notices):
    blob = "".join(str(n) for n in notices)
    triggers = [k for k in ("剔除", "超出预算", "超出半径") if k in blob]
    if not triggers:
        return (True, "本响应未触发剔除")
    loud = any(ch.isdigit() for ch in blob)
    return (loud, "剔除已报条数或口径" if loud else "出现剔除字样但没给条数，等于静默减少候选")


def check_no_ugc(values):
    bad = []
    for v in values:
        for field in UGC_FIELDS:
            if v.get(field):
                bad.append("%s.%s" % (v.get("name"), field))
    return (not bad, "未携带 UGC 正文" if not bad
            else "候选里出现正文类字段：%s" % "、".join(bad[:3]))


def run(payload):
    facts, notices, advice, unknown = unwrap(payload)
    values = food_values(facts)
    return [
        ("NO_WRITE", check_no_write(advice, notices, values)),
        ("NO_LINK", check_no_link(values)),
        ("USABLE", check_usable(values)),
        ("ID_UNIQUE", check_id_unique(values)),
        ("DISTANCE_KIND", check_distance_kind(values, notices)),
        ("RATING_SRC", check_rating_source(values)),
        ("ZERO_REASON", check_zero_reason(facts, values, notices)),
        ("FILTER_LOUD", check_filter_loud(notices)),
        ("NO_UGC", check_no_ugc(values)),
    ]


def self_test():
    """用两份内置样本证明探针能同时识别通过项与违规项。"""
    good = {
        "facts": [{"type": "CONTENT", "value": {
            "category": "FOOD", "kind": "RESTAURANT", "foodId": "AMAP:B001", "name": "老张煨汤",
            "distanceMeters": 320, "rating": 4.5, "ratingSource": "AMAP",
            "reviewLinks": [], "constraintNotes": []}}],
        "notices": ["距离按直线计算，不是步行距离或步行时间", "已剔除 2 条连锁快餐或饮品候选"],
        "advice": "已整理附近餐饮候选，营业时段请到店核实",
    }
    bad = {
        "facts": [{"type": "CONTENT", "value": {
            "category": "FOOD", "kind": "RESTAURANT", "foodId": "AMAP:B001", "name": "某店",
            "distanceMeters": 320, "rating": 4.5,
            "reviewLinks": ["https://example.com/review"], "comments": "好吃"}},
            {"type": "CONTENT", "value": {
                "category": "FOOD", "kind": "RESTAURANT", "foodId": "AMAP:B001", "name": "另一店"}}],
        "notices": ["已剔除连锁"],
        "advice": "已经买好了，订单已生成",
    }
    print("== 通过样本 ==")
    good_ok = report(run(good), as_json=False)
    print("== 违规样本 ==")
    bad_ok = report(run(bad), as_json=False)
    # 自检要求：合规样本全 PASS（good_ok 为真），违规样本必须被抓出来（bad_ok 为假）
    expected_ok = good_ok and not bad_ok
    print("\n自检结果：%s" % ("符合预期（合规样本无 FAIL、违规样本被抓出）"
                             if expected_ok else "探针本身有问题，请检查"))
    return 0 if expected_ok else 1


def report(results, as_json=False):
    failed = [r for r in results if not r[1][0]]
    if as_json:
        print(json.dumps({"passed": len(results) - len(failed), "failed": len(failed),
                          "items": [{"id": r[0], "ok": r[1][0], "detail": r[1][1]}
                                    for r in results]}, ensure_ascii=False, indent=2))
    else:
        for rid, (ok, detail) in results:
            print("  [%s] %-14s %s" % ("PASS" if ok else "FAIL", rid, detail))
        print("  -> %d 通过 / %d 失败" % (len(results) - len(failed), len(failed)))
    return not failed


def main(argv=None):
    parser = argparse.ArgumentParser(description="餐饮准入九条探针")
    parser.add_argument("--file", help="响应信封 JSON 路径；缺省从 stdin 读")
    parser.add_argument("--json", action="store_true", help="输出 JSON 结果")
    parser.add_argument("--self-test", action="store_true", help="用内置样本自检探针本身")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    try:
        if args.file:
            raw = Path(args.file).read_text(encoding="utf-8")
        else:
            raw = sys.stdin.read()
        payload = json.loads(raw)
    except (OSError, ValueError) as exc:
        print("输入不可读：%s" % exc, file=sys.stderr)
        return 2

    ok = report(run(payload), as_json=args.json)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
