#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
景点精简索引库离线构建器（Java 在线检索的输入）
=================================================
职责边界：
  Python 侧只做"离线构建 + 清洗"，不做在线查询。
  输入 data/china_attractions.json（融合主库，**只读，绝不覆盖**）
  输出 data/attractions_index.json（精简库，供 Java 启动加载并自建索引）

做的事：
  1. 规范化占位值："暂无 / 未知 / 无 / - / -- / null / N/A" 等统一为 null；
     level 的"其他"视为无有效等级，同样置 null。
  2. 清理无效地址：address 等于 城市/省份 或二者简单拼接（如"北京市北京"）时置 null。
  3. 裁剪冗余字段：去掉每条重复的 city_intro、photos、tags、same_name_group 等，
     并丢弃与 name 完全相同的 name_zh，显著缩小体积。
  4. 输出带统计的紧凑 JSON，原子写入，不触碰原库。

用法：
  python tools/build_attraction_index.py
  python tools/build_attraction_index.py --input data/china_attractions.json \
                                         --output data/attractions_index.json
  python tools/build_attraction_index.py --dry-run     # 只统计不落盘
  python tools/build_attraction_index.py --check       # 校验已提交索引与源库一致，不落盘

退出码：0 成功；2 输入缺失；3 输入损坏；4 输出路径非法（试图覆盖原库）。
"""
import argparse
import json
import os
import sys
import tempfile
from datetime import datetime, timezone

DEFAULT_INPUT = os.path.join("data", "china_attractions.json")
DEFAULT_OUTPUT = os.path.join("data", "attractions_index.json")

# 视作"无信息"的占位值（统一转 None）
PLACEHOLDERS = {
    "", "暂无", "暂无信息", "暂无数据", "未知", "无", "没有",
    "-", "--", "—", "——", "null", "None", "none", "N/A", "n/a", "NA", "nan",
}
# 等级字段里不构成真实等级的值
LEVEL_NOISE = {"其他", "其它", "未知", "暂无"}

# 精简后保留的字段（顺序即输出顺序，便于 diff）
KEEP_FIELDS = [
    "id", "name", "city", "province", "type", "level",
    "rating", "open_time", "ticket", "address", "tel", "website",
    "image", "description", "lat", "lon", "source", "sources",
    "ticket_status", "open_time_status",
]


def clean_text(value):
    """占位值 / 空串 → None；其余保留去空白后的原值。"""
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        if text in PLACEHOLDERS:
            return None
        return text
    return value


def clean_address(address, city, province):
    """清理跨源拼接产生的伪地址，如 "北京市北京"。"""
    text = clean_text(address)
    if not text:
        return None
    city = (city or "").strip()
    province = (province or "").strip()
    noise = {city, province, city + province, province + city}
    if text in {n for n in noise if n}:
        return None
    return text


def clean_level(level):
    text = clean_text(level)
    if text is None or text in LEVEL_NOISE:
        return None
    return text


def normalize(item):
    """单条景点 → 精简、规范化后的 dict。"""
    city = clean_text(item.get("city")) or ""
    province = clean_text(item.get("province")) or ""
    name = clean_text(item.get("name")) or ""

    out = {}
    for field in KEEP_FIELDS:
        if field == "level":
            out[field] = clean_level(item.get("level"))
        elif field == "address":
            out[field] = clean_address(item.get("address"), city, province)
        elif field in ("city", "province"):
            out[field] = {"city": city, "province": province}[field]
        elif field == "name":
            out[field] = name
        elif field == "sources":
            sources = item.get("sources")
            out[field] = sources if isinstance(sources, list) and sources else None
        elif field in ("lat", "lon"):
            out[field] = item.get(field)
        else:
            out[field] = clean_text(item.get(field))

    # 名称缺失的条目无法被检索，直接丢弃（构建阶段统计）
    if not name:
        return None
    return out


def build(input_path, output_path, dry_run=False, check=False):
    if os.path.abspath(input_path) == os.path.abspath(output_path):
        print(f"[拒绝] 输出路径与输入相同，不允许覆盖原库：{input_path}", file=sys.stderr)
        return 4
    if not os.path.exists(input_path):
        print(f"[错误] 输入文件不存在：{input_path}", file=sys.stderr)
        return 2

    try:
        with open(input_path, encoding="utf-8") as f:
            doc = json.load(f)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        print(f"[错误] 输入文件损坏，无法解析：{input_path} ({e})", file=sys.stderr)
        return 3

    raw_items = doc.get("attractions") if isinstance(doc, dict) else None
    if not isinstance(raw_items, list):
        print("[错误] 输入结构异常：缺少 attractions 数组", file=sys.stderr)
        return 3

    items = []
    dropped_no_name = 0
    placeholder_hits = 0
    address_cleaned = 0
    level_cleaned = 0

    for raw in raw_items:
        if not isinstance(raw, dict):
            continue
        cleaned = normalize(raw)
        if cleaned is None:
            dropped_no_name += 1
            continue
        if raw.get("address") and cleaned.get("address") is None:
            address_cleaned += 1
        if raw.get("level") and cleaned.get("level") is None:
            level_cleaned += 1
        for field in KEEP_FIELDS:
            if raw.get(field) not in (None, "") and cleaned.get(field) is None:
                placeholder_hits += 1
        items.append(cleaned)

    city_counter = {}
    for a in items:
        city_counter[a["city"]] = city_counter.get(a["city"], 0) + 1

    result = {
        "schema": "attraction-index-v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source_kb": os.path.basename(input_path),
        "source_kb_generated_at": doc.get("generated_at"),
        "count": len(items),
        "city_count": len(city_counter),
        "cities": dict(sorted(city_counter.items(), key=lambda kv: (-kv[1], kv[0]))),
        "attractions": items,
    }

    stats = {
        "输入条数": len(raw_items),
        "输出条数": len(items),
        "因缺名称丢弃": dropped_no_name,
        "地址占位被清理": address_cleaned,
        "等级伪值被清理": level_cleaned,
        "字段占位命中": placeholder_hits,
        "城市数": len(city_counter),
        "输出大小": "-",
    }

    if check:
        try:
            with open(output_path, encoding="utf-8") as f:
                current = json.load(f)
        except (OSError, ValueError) as e:
            print(f"[过期] 索引缺失或损坏，请重新构建：{e}", file=sys.stderr)
            return 5
        # 构建时间不参与比较，规范化后的内容和来源元数据必须一致。
        expected = {k: v for k, v in result.items() if k != "generated_at"}
        actual = {k: v for k, v in current.items() if k != "generated_at"} if isinstance(current, dict) else None
        if actual != expected:
            print("[过期] 索引与源库不一致，请运行 python tools/build_attraction_index.py", file=sys.stderr)
            return 5
        print(f"[通过] 索引与源库一致：{len(items)} 条")
        return 0

    if dry_run:
        print("[dry-run] 不落盘。统计：")
        for k, v in stats.items():
            print(f"  {k}: {v}")
        return 0

    out_dir = os.path.dirname(os.path.abspath(output_path))
    os.makedirs(out_dir, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(dir=out_dir, prefix=".attractions_index", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp_path, output_path)  # 原子替换，只动输出文件
    except Exception:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise

    size_mb = os.path.getsize(output_path) / 1024 / 1024
    stats["输出大小"] = f"{size_mb:.2f} MB"
    print(f"[完成] 已生成 {output_path}")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    return 0


def main():
    parser = argparse.ArgumentParser(description="构建景点精简索引库（只读原库）")
    parser.add_argument("--input", default=DEFAULT_INPUT, help="融合主库路径（只读）")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="精简库输出路径")
    parser.add_argument("--dry-run", action="store_true", help="只统计，不写文件")
    parser.add_argument("--check", action="store_true", help="检查索引是否过期，不写文件；过期返回5")
    args = parser.parse_args()
    sys.exit(build(args.input, args.output, args.dry_run, args.check))


if __name__ == "__main__":
    main()
