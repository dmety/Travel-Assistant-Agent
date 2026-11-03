#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
跨源同名处置落地脚本（配套 docs/cross_source_review.md 第二部分建议）。

文档建议：距离 <5km 的「同省异地同名」可能为同一景区的不同入口/重复录入，
"可考虑人工复核合并"；>10km 为独立景点，"不得合并"。

本脚本据此给 KB 记录追加 merge_review 标记（纯数据治理元数据，不改动业务字段）：
  - far_apart_<d>m 且 d < 2000  → merge_review = "candidate"  (强疑似重复，优先人工确认合并)
  - far_apart_<d>m 且 2000<=d<5000 → merge_review = "review"   (弱疑似，建议人工复核)
  - 其余（>=5000 或 cross_city）   → 不加标记，保持独立

幂等：仅当 merge_review 当前为 None 时才写入；--apply 才落库。
展示层 tools/display_utils.py 的 META_FIELDS 已包含 merge_review，不会被渲染成"暂无"。
"""
import json, os, argparse, sys

KB_PATH = r"F:\repository\Travel-Assistant-Agent\data\china_attractions.json"
CANDIDATE_MAX = 2000   # <2km 强疑似
REVIEW_MAX = 5000      # <5km 弱疑似


def parse_dist(risk):
    """从 'far_apart_1021m' 解析距离整数；非该格式返回 None。"""
    if not isinstance(risk, str) or not risk.startswith("far_apart_"):
        return None
    s = risk[len("far_apart_"):]
    if not s.endswith("m"):
        return None
    try:
        return int(s[:-1])
    except ValueError:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="落库；默认仅 dry-run 打印")
    args = ap.parse_args()

    kb = json.load(open(KB_PATH, encoding="utf-8"))
    recs = kb["attractions"]
    cand = rev = skip = 0
    for r in recs:
        risk = r.get("same_name_risk")
        d = parse_dist(risk)
        if d is None:
            skip += 1
            continue
        if r.get("merge_review") is not None:
            skip += 1
            continue
        if d < CANDIDATE_MAX:
            r["merge_review"] = "candidate"; cand += 1
        elif d < REVIEW_MAX:
            r["merge_review"] = "review"; rev += 1
        # >=5000 不加标记

    print(f"[dry?={not args.apply}] candidate={cand} review={rev} 跳过(无risk/已标)={skip}")
    if args.apply:
        json.dump(kb, open(KB_PATH, "w", encoding="utf-8"),
                  ensure_ascii=False, separators=(",", ":"))
        print("已落库。")
    else:
        print("（dry-run，未落库；加 --apply 落库）")


if __name__ == "__main__":
    main()
