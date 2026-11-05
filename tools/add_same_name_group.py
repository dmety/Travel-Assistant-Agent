#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
报告发现 #6 的落地：为知识库每条记录打 same_name_group 标记，便于检索与去重。

逻辑（与 tools/audit_cross_source.py 的分组口径一致）：
  - 按 (province, name) 分组；
  - 组内记录数 > 1 的，视为"潜在同名/重名合并候选"，组内共享一个稳定 group id；
  - 组内仅 1 条的（唯一名称），same_name_group 置 null。

group id 取值：sng-<sha256(province\0name) 前 8 位十六进制>，确定性、可重跑一致。
本脚本【仅新增/更新 same_name_group 字段】，绝不覆盖 ticket/description/coordinateSystem 等已有数据。
幂等：重跑结果一致；若某记录已有非 null 的 same_name_group 但与当前分组冲突，则以当前分组重算覆盖
      （保留同一分组口径，避免历史脏值）。

用法:
    python tools/add_same_name_group.py --dry      # 预览影响
    python tools/add_same_name_group.py --apply    # 落库
"""
import json
import os
import sys
import hashlib
from collections import defaultdict

BASE = r"F:\repository\Travel-Assistant-Agent"
KB = os.path.join(BASE, "data", "china_attractions.json")
FIELD = "same_name_group"

def group_id(province, name):
    key = f"{province}\x00{name}"
    h = hashlib.sha256(key.encode("utf-8")).hexdigest()[:8]
    return f"sng-{h}"

def compute_groups(recs):
    buckets = defaultdict(list)
    for a in recs:
        buckets[(a.get("province"), a.get("name"))].append(a)
    # 仅组内 >1 条才赋予 group id
    gid_map = {}
    for k, items in buckets.items():
        if len(items) > 1:
            gid_map[k] = group_id(k[0], k[1])
    return gid_map

def main():
    mode = "--apply" if "--apply" in sys.argv else "--dry"
    data = json.load(open(KB, encoding="utf-8"))
    recs = data["attractions"]
    gid_map = compute_groups(recs)

    set_group = 0
    set_null = 0
    for a in recs:
        k = (a.get("province"), a.get("name"))
        g = gid_map.get(k)
        if g is not None:
            if a.get(FIELD) != g:
                if mode == "--apply":
                    a[FIELD] = g
                set_group += 1
        else:
            if a.get(FIELD) is not None:
                if mode == "--apply":
                    a[FIELD] = None
                set_null += 1

    multi = sum(1 for v in gid_map.values())
    print(f"模式: {mode}")
    print(f"重复名组(>1条)数量: {len(gid_map)}")
    print(f"被打上 group id 的记录变动数: {set_group}")
    print(f"被清空(唯一名→null)的记录变动数: {set_null}")
    covered = sum(1 for a in recs if a.get(FIELD) is not None)
    print(f"最终带 same_name_group 的记录数: {covered} / {len(recs)}")

    if mode == "--apply":
        with open(KB, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print("已落库。")

if __name__ == "__main__":
    main()
