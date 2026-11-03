#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 KB 加 3 个数据质量状态字段（落地数据质量表 #1/#2/#6 的建议）：
  - ticket_status     : "verified"(有真实票价) / "pending"(缺失待核验)
  - open_time_status  : "verified"(有开放时间) / "pending"(缺失待核验)
  - same_name_risk    : 仅跨源高风险同名组(35组)赋予 "cross_city" / "far_apart_<dist>m"
数据层缺失值已是 null（#4 已语义化），本脚本把"待核验"从隐式 null 变为显式 status，
满足表建议"标记待核验，不能按零元/凭模型补齐"。
用法:
    python add_status_fields.py --dry      # 只统计
    python add_status_fields.py --apply    # 写入
"""
import json
import sys
import os

BASE = r"F:\repository\Travel-Assistant-Agent"
KB = os.path.join(BASE, "data", "china_attractions.json")
AUDIT = r"F:\wb_tmp\cross_source_audit.json"


def load(p):
    with open(p, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    mode = "--apply" if "--apply" in sys.argv else "--dry"
    data = load(KB)
    audit = load(AUDIT)
    recs = data["attractions"]

    # 构建 id -> risk（跨城市优先于远距离，因为更危险）
    risk = {}
    for g in audit.get("detail_diff_city", []):
        for i in g["ids"]:
            risk[i] = "cross_city"
    for g in audit.get("detail_far_apart", []):
        for i in g["ids"]:
            if i not in risk:
                risk[i] = "far_apart_%dm" % g["max_distance_m"]

    n_tv = n_tp = n_ov = n_op = n_risk = 0
    for r in recs:
        rid = r.get("id")
        r["ticket_status"] = "verified" if r.get("ticket") not in (None, "暂无", "") else "pending"
        r["open_time_status"] = "verified" if r.get("open_time") not in (None, "暂无", "") else "pending"
        if rid in risk:
            r["same_name_risk"] = risk[rid]
            n_risk += 1
        else:
            r.pop("same_name_risk", None)
        n_tv += r["ticket_status"] == "verified"
        n_tp += r["ticket_status"] == "pending"
        n_ov += r["open_time_status"] == "verified"
        n_op += r["open_time_status"] == "pending"

    if mode == "--apply":
        with open(KB, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print("[APPLY] 已写入知识库")
    else:
        print("[DRY] 仅统计，未改动文件")
    print(f"ticket_status     verified={n_tv}  pending={n_tp}")
    print(f"open_time_status  verified={n_ov}  pending={n_op}")
    print(f"same_name_risk    标记={n_risk} 组")


if __name__ == "__main__":
    main()
