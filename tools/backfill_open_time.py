#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 open_time_map.json 的 id->开放时间 回填到 KB 的 open_time 字段。
- 仅当目标记录的 open_time 字段为 "暂无" 或 None 时才写入（幂等，不覆盖已有值）。
- 用法:
    python backfill_open_time.py --dry      # 只统计
    python backfill_open_time.py --apply    # 真正写入
"""
import json
import sys
import os

BASE = r"F:\repository\Travel-Assistant-Agent"
KB = os.path.join(BASE, "data", "china_attractions.json")
MAP = r"F:\wb_tmp\open_time_map.json"


def load(p):
    with open(p, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    mode = "--apply" if "--apply" in sys.argv else "--dry"
    data = load(KB)
    omap = load(MAP)
    recs = data["attractions"]
    updated = skipped = still = 0
    for r in recs:
        rid = r.get("id")
        ot = r.get("open_time")
        if rid in omap:
            v = omap[rid]
            if ot in ("暂无", None):
                if mode == "--apply":
                    r["open_time"] = v
                updated += 1
            else:
                skipped += 1
        else:
            if ot in ("暂无", None):
                still += 1
    if mode == "--apply":
        with open(KB, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print("[APPLY] 已写入知识库")
    else:
        print("[DRY] 仅统计，未改动文件")
    print(f"命中映射且待更新: {updated}")
    print(f"命中映射但已有值(跳过): {skipped}")
    print(f"仍未补全(不在映射): {still}")


if __name__ == "__main__":
    main()
