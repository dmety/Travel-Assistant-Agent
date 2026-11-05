#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知识库确定性整改 + 结构体检（适配顶层 city_introductions 结构）。

背景
----
2026-11-05 起 data/china_attractions.json 的结构变了：每条记录内联的
`city_intro` 被迁移到顶层 `city_introductions` 字典（键 `省|市`，66 条），
记录内不再保存该字段。同时坐标系经证据链核验后已归一到 WGS-84 / GCJ-02，
不再有 `mixed`。

本脚本同步该结构。相比旧版本修正两处会造成倒退的行为：
  - 旧版把城市简介写回每条记录 → 重新引入 11858 份冗余（约占 133 万字符）；
  - 旧版按「source 同时含 Amap 与 OSM 就判 mixed」推断坐标系 → 会把 2166 条
    已核验记录退回 `mixed`（其中 WGS-84 249 条、GCJ-02 1917 条）。
    坐标系现在只体检、不推断，证据链修正见 tools/kb/ 下的专用脚本。

处理项
------
  1. 占位值语义化：标量字段的 "暂无" 归一为 JSON null
  2. 城市简介归集：只写顶层 `city_introductions`；对缺失城市按组内高频
     description（>=2 次且 >=50%）判定；`--apply` 时顺手清理记录内历史残留
  3. 坐标系：仅统计缺失或非法取值，不改写

用法
----
    python tools/remediate_kb.py --check   # 只做结构体检，有致命问题退出码 1
    python tools/remediate_kb.py --dry     # 预演，只打印将要发生的改动（默认）
    python tools/remediate_kb.py --apply   # 写回知识库
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KB_PATH = ROOT / "data" / "china_attractions.json"

PLACEHOLDER_VALUES = ("暂无",)
VALID_CRS = ("WGS-84", "GCJ-02")
SECTION = "attractions"


# ---------------------------------------------------------------- 基础读写

def load_kb(path=KB_PATH):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save_kb(doc, path=KB_PATH):
    # 必须保持紧凑单行：原文件是约 10 MB 的单行 JSON，改成 indent=2 会膨胀成
    # 数百万行并产生巨量 diff。结尾补换行，与仓库现有文件保持一致。
    Path(path).write_text(
        json.dumps(doc, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def city_key(row):
    """记录对应的简介键，与已有数据的 `省|市` 形式保持一致。"""
    return f"{row.get('province') or ''}|{row.get('city') or ''}"


# ---------------------------------------------------------------- 处理项 1

def normalize_placeholders(rows):
    """标量字段的 "暂无" 归一为 None，返回改动字段数。"""
    changed = 0
    for row in rows:
        for key, value in list(row.items()):
            if isinstance(value, str) and value in PLACEHOLDER_VALUES:
                row[key] = None
                changed += 1
    return changed


# ---------------------------------------------------------------- 处理项 2

def collect_introductions(rows, existing):
    """归集城市简介，返回 (新映射, 新增城市数, 记录内残留数)。

    只产出顶层字典；已有简介优先保留，绝不覆盖人工核验过的文本。
    """
    introductions = dict(existing or {})
    legacy_rows = 0

    # 旧库兼容：记录内若还有 city_intro，仅作为「没有既有简介时的种子」
    for row in rows:
        legacy = row.get("city_intro")
        if not isinstance(legacy, str) or not legacy.strip():
            continue
        legacy_rows += 1
        introductions.setdefault(city_key(row), legacy.strip())

    # 缺失城市：用组内高频 description 判定（多数决，避免误删真实景点描述）
    groups = defaultdict(list)
    for row in rows:
        groups[city_key(row)].append(row)

    added = 0
    for key, group in groups.items():
        if introductions.get(key):
            continue
        counter = Counter()
        for row in group:
            desc = row.get("description")
            if isinstance(desc, str) and desc.strip() and desc not in PLACEHOLDER_VALUES:
                counter[desc.strip()] += 1
        if not counter:
            continue
        text, freq = counter.most_common(1)[0]
        total = sum(counter.values())
        if freq >= 2 and freq >= 0.5 * total:
            introductions[key] = text
            added += 1

    return introductions, added, legacy_rows


def apply_introductions(doc, rows, introductions):
    """写回简介映射，并清除记录内的 city_intro 残留，返回 (清残留数, 描述置空数)。"""
    doc["city_introductions"] = introductions
    stripped = 0
    desc_nulled = 0
    for row in rows:
        if "city_intro" in row:
            row.pop("city_intro", None)
            stripped += 1
        intro = introductions.get(city_key(row))
        if intro and row.get("description") == intro:
            row["description"] = None
            desc_nulled += 1
    return stripped, desc_nulled


# ---------------------------------------------------------------- 体检

def check_structure(doc):
    """返回 [(level, message)]，level ∈ {OK, WARN, FAIL}。"""
    rows = doc.get(SECTION) or []
    introductions = doc.get("city_introductions") or {}
    report = []

    legacy = [row.get("id") for row in rows if "city_intro" in row]
    if legacy:
        report.append(("FAIL", f"记录内残留 city_intro：{len(legacy)} 条（应为 0），示例 {legacy[:3]}"))
    else:
        report.append(("OK", "记录内无 city_intro 残留（简介已全部收归顶层）"))

    keys = {city_key(row) for row in rows}
    missing = sorted(keys - set(introductions))
    orphan = sorted(set(introductions) - keys)
    if missing:
        report.append(("WARN", f"{len(missing)} 个城市缺简介，示例 {missing[:5]}"))
    else:
        report.append(("OK", f"city_introductions 覆盖全部 {len(keys)} 个城市"))
    if orphan:
        report.append(("WARN", f"{len(orphan)} 个孤儿简介键（无对应记录），示例 {orphan[:5]}"))

    bad_crs = [row.get("id") for row in rows if row.get("coordinateSystem") not in VALID_CRS]
    if bad_crs:
        report.append(("WARN", f"{len(bad_crs)} 条坐标系缺失或非法，示例 {bad_crs[:3]}"))
    else:
        dist = Counter(row.get("coordinateSystem") for row in rows)
        report.append(("OK", f"坐标系全部合法：{dict(dist)}"))

    declared = doc.get("count")
    if declared is not None and declared != len(rows):
        report.append(("WARN", f"顶层 count={declared} 与实际条数 {len(rows)} 不一致（--apply 会自动同步）"))
    else:
        report.append(("OK", f"顶层 count 与实际条数一致（{len(rows)}）"))

    return report


# ---------------------------------------------------------------- 主流程

def main(argv=None):
    parser = argparse.ArgumentParser(description="知识库确定性整改 / 结构体检")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="只做结构体检，有致命问题退出码 1")
    mode.add_argument("--apply", action="store_true", help="写回知识库")
    mode.add_argument("--dry", action="store_true", help="预演，只统计不写文件（默认）")
    parser.add_argument("--kb", default=str(KB_PATH), help="知识库路径")
    args = parser.parse_args(argv)

    doc = load_kb(args.kb)
    rows = doc.get(SECTION) or []
    if not rows:
        print(f"[FAIL] {args.kb} 里没有 {SECTION} 数据")
        return 1

    if args.check:
        report = check_structure(doc)
        fatal = False
        for level, message in report:
            print(f"[{level:<4}] {message}")
            fatal = fatal or level == "FAIL"
        print(f"\n体检结论：{'存在致命问题' if fatal else '通过'}（{len(rows)} 条记录）")
        return 1 if fatal else 0

    stat = {"records": len(rows)}
    stat["placeholders_nulled"] = normalize_placeholders(rows)

    introductions, added, legacy_seen = collect_introductions(rows, doc.get("city_introductions"))
    stat["cities_with_intro"] = len(introductions)
    stat["cities_inferred"] = added
    stat["legacy_city_intro_seen"] = legacy_seen

    if args.apply:
        stripped, desc_nulled = apply_introductions(doc, rows, introductions)
        stat["legacy_city_intro_stripped"] = stripped
        stat["description_nulled"] = desc_nulled
        if doc.get("count") != len(rows):
            stat["count_before"] = doc.get("count")
            doc["count"] = len(rows)
            stat["count_after"] = len(rows)
        save_kb(doc, args.kb)
        print(f"[APPLY] 已写回 {args.kb}")
    else:
        # 预演也要如实反映「写回时会发生什么」
        stat["legacy_city_intro_stripped"] = sum(1 for row in rows if "city_intro" in row)
        stat["description_nulled"] = sum(
            1 for row in rows
            if introductions.get(city_key(row)) and row.get("description") == introductions.get(city_key(row))
        )
        print("[DRY] 仅统计，未改动文件")

    print(json.dumps(stat, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
