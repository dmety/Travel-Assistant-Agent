#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
著名景点字段专项补全 —— 对 5A/4A 景区及高评分景点按名称精确搜索高德，补齐票价/电话/开放时间等。

用法:
  python tools/enrich_famous.py                     # 补全后落库
  python tools/enrich_famous.py --dry-run           # 只看统计不写文件
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from kb.amap_source import AmapClient, load_api_key  # noqa: E402
from kb.models import norm_name  # noqa: E402

UNIFIED_PATH = "data/china_attractions_unified.json"
INDEX_PATH = "data/china_attractions_unified_index.json"


def pick_targets(attractions):
    """选出需要补全的目标：5A/4A + 高评分(>=4.5)无票价的。"""
    targets = []
    for a in attractions:
        level = a.get("level", "")
        rating = a.get("rating", "")
        is_famous = level in ("5A景区", "4A景区")
        try:
            is_high_rated = float(rating) >= 4.5
        except (ValueError, TypeError):
            is_high_rated = False
        needs_ticket = not a.get("ticket")
        needs_tel = not a.get("tel")
        needs_open = not a.get("open_time")
        if is_famous or (is_high_rated and (needs_ticket or needs_tel or needs_open)):
            targets.append(a)
    return targets


def enrich_one(client, a, dry_run=False):
    """按名称+城市精确搜索高德，补全字段。返回补了哪些字段。"""
    city = a.get("city", "") or a.get("province", "")
    kw = a.get("name", "")
    if not kw:
        return []
    if dry_run:
        return []
    try:
        data = client.text_search(keywords=kw, city=city, types="110000|140000", page=1, pagesize=5)
    except Exception as e:
        return [f"ERR:{e}"]
    pois = data.get("pois", [])
    if not pois:
        return []
    # 找最匹配的（名称最相似）
    best = None
    best_score = -1
    nk = norm_name(kw)
    for p in pois:
        pn = norm_name(p.get("name", ""))
        if pn == nk:
            best = p
            best_score = 999
            break
        overlap = sum(1 for c in nk if c in pn)
        if overlap > best_score:
            best_score = overlap
            best = p
    if not best:
        return []
    biz = best.get("biz_ext", {}) or {}
    kt = best.get("keytag", "")
    patched = []
    # 票价
    if not a.get("ticket"):
        cost = biz.get("cost")
        if isinstance(cost, list):
            cost = ""
        if cost:
            a["ticket"] = cost
            patched.append("ticket")
    # 电话
    if not a.get("tel"):
        tel = best.get("tel", "")
        if tel:
            a["tel"] = tel
            patched.append("tel")
    # 开放时间
    if not a.get("open_time"):
        ot = biz.get("opentime2") or biz.get("open_time") or ""
        if ot:
            a["open_time"] = ot
            patched.append("open_time")
    # 等级 (keytag)
    if not a.get("level") and isinstance(kt, str) and "景区" in kt:
        a["level"] = kt
        patched.append("level")
    # 图片
    if not a.get("image"):
        photos = best.get("photos", []) or []
        urls = [ph.get("url") for ph in photos if ph.get("url")]
        if urls:
            a["image"] = urls[0]
            a["photos"] = urls
            patched.append("image")
    return patched


def main():
    key = load_api_key()
    if not key:
        print("错误：未配置 AMAP_API_KEY", file=sys.stderr)
        sys.exit(1)

    dry_run = "--dry-run" in sys.argv

    with open(UNIFIED_PATH, encoding="utf-8") as f:
        doc = json.load(f)
    attractions = doc["attractions"]
    targets = pick_targets(attractions)
    print(f"[补全] 目标景点 {len(targets)} 个（5A/4A + 高评分无票价/电话/开放时间）",
          file=sys.stderr)

    client = AmapClient(key=key, qps_sleep=0.3)
    stats = {"ticket": 0, "tel": 0, "open_time": 0, "level": 0, "image": 0, "err": 0}
    for i, a in enumerate(targets):
        patched = enrich_one(client, a, dry_run=dry_run)
        for f in patched:
            if f.startswith("ERR"):
                stats["err"] += 1
            else:
                stats[f] = stats.get(f, 0) + 1
        if patched and not patched[0].startswith("ERR"):
            fields = [f for f in patched if not f.startswith("ERR")]
            print(f"  [{i+1}/{len(targets)}] {a['name']} ← 补{fields}", file=sys.stderr)
        if (i + 1) % 50 == 0:
            print(f"  ... 进度 {i+1}/{len(targets)}", file=sys.stderr)
        time.sleep(0.2)

    if dry_run:
        print(f"\n[dry-run] 统计: {stats}", file=sys.stderr)
        return

    # 更新统计
    doc["with_ticket"] = sum(1 for a in attractions if a.get("ticket"))
    doc["with_open_time"] = sum(1 for a in attractions if a.get("open_time"))
    doc["with_level"] = sum(1 for a in attractions if a.get("level"))
    doc["with_image"] = sum(1 for a in attractions if a.get("image"))
    doc["enriched_at"] = datetime.now(timezone.utc).isoformat()

    with open(UNIFIED_PATH, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    # 重建索引
    index = {}
    for a in attractions:
        index.setdefault(a.get("province", ""), {}).setdefault(a.get("city", ""), []).append(a["id"])
    with open(INDEX_PATH, "w", encoding="utf-8") as f:
        json.dump({"generated_at": doc.get("enriched_at", ""), "total": len(attractions),
                   "index": index}, f, ensure_ascii=False, indent=2)

    print(f"\n[完成] 补全统计: {stats}", file=sys.stderr)
    print(f"  更新后: 票价 {doc['with_ticket']} / 开放时间 {doc['with_open_time']} / 等级 {doc['with_level']} / 图片 {doc['with_image']}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
