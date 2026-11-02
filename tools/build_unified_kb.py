#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
统一中国景点知识库构建器（OSM 基底 + 高德补强）
=================================================
  A 路线 : OpenStreetMap / Overpass  (免费、免 Key、ODbL 开源基底)
  B 路线 : 高德 Amap Web服务        (商业免费额度，补强 等级/评分/开放时间/票价/电话/图片)

流程:
  1. 载入 OSM 基底 data/china_attractions_osm.json
  2. 逐城市用高德 POI 检索拉取权威景点，按 (省, 归一名) 与 OSM 匹配：
     - 命中 -> 补强等级/评分/开放时间/票价/电话/地址/图片，source 标记合并
     - 未命中 -> 作为高德专属记录追加（补齐 OSM 超时缺失的城市）
  3. 落库:
     data/china_attractions.json          融合知识库（Agent 主库）
     data/china_attractions_index.json    省->市->[id] 检索索引

用法:
  python tools/build_unified_kb.py                 # 全量 39 城融合
  python tools/build_unified_kb.py --test-city 北京  # 单城验证（不落库）
"""
import json
import os
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from kb.models import from_osm, norm_name  # noqa: E402
from kb.amap_source import AmapClient, from_amap, load_api_key  # noqa: E402
from build_china_kb import CITIES  # noqa: E402

OSM_PATH = "data/china_attractions_osm.json"
OUT_PATH = "data/china_attractions.json"
INDEX_PATH = "data/china_attractions_index.json"


def merge_city(attractions, by_key, client, city):
    """拉取单城市高德 POI 并融合，返回 (matched, added, fetched)。"""
    try:
        pois = client.fetch_city(city)
    except Exception as e:
        print(f"    [Amap][{city}] 整体失败: {e}", flush=True)
        return 0, 0, 0
    matched = added = 0
    for p in pois:
        a = from_amap(p)
        cand = by_key.get((a.province, norm_name(a.name)))
        if cand:
            tgt = cand[0]
            if not tgt.level:
                tgt.level = a.level
            if not tgt.rating:
                tgt.rating = a.rating
            if not tgt.open_time:
                tgt.open_time = a.open_time
            if not tgt.ticket:
                tgt.ticket = a.ticket
            if not tgt.tel:
                tgt.tel = a.tel
            if not tgt.address:
                tgt.address = a.address
            if not tgt.image and a.image:
                tgt.image = a.image
                tgt.photos = a.photos
            if "Amap" not in tgt.sources:
                tgt.sources.append("Amap")
            tgt.source = "OpenStreetMap + Amap"
            matched += 1
        else:
            attractions.append(a)
            by_key.setdefault((a.province, norm_name(a.name)), []).append(a)
            added += 1
    return matched, added, len(pois)


def build(osm_path, out_path, index_path, cities, key):
    with open(osm_path, encoding="utf-8") as f:
        osm_doc = json.load(f)
    attractions = [from_osm(o) for o in osm_doc["attractions"]]
    by_key = {}
    for a in attractions:
        by_key.setdefault((a.province, norm_name(a.name)), []).append(a)

    client = AmapClient(key=key)
    stats = {"fetched": 0, "matched": 0, "added": 0, "cities": 0}
    for city, prov, lat, lon, r in cities:
        print(f"[Amap] 城市 {city} ...", flush=True)
        m, ad, f = merge_city(attractions, by_key, client, city)
        stats["cities"] += 1
        stats["fetched"] += f
        stats["matched"] += m
        stats["added"] += ad
        print(f"        高德拉取 {f} 个，匹配补强 {m} 个，新增 {ad} 个", flush=True)
        time.sleep(1)

    index = {}
    for a in attractions:
        index.setdefault(a.province, {}).setdefault(a.city, []).append(a.id)

    n_level = sum(1 for a in attractions if a.level)
    n_rating = sum(1 for a in attractions if a.rating)
    n_open = sum(1 for a in attractions if a.open_time)
    n_img = sum(1 for a in attractions if a.image)
    n_ticket = sum(1 for a in attractions if a.ticket)

    result = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "OpenStreetMap Overpass (ODbL) + 高德 Amap Web服务(商业免费额度)",
        "note": "Travel-Assistant-Agent 的中国景点统一知识库；OSM 作开源基底，高德补强等级/评分/开放时间/票价/电话/图片",
        "osm_count": len(osm_doc["attractions"]),
        "count": len(attractions),
        "with_level": n_level,
        "with_rating": n_rating,
        "with_open_time": n_open,
        "with_ticket": n_ticket,
        "with_image": n_img,
        "amap_stats": stats,
        "attractions": [a.to_dict() for a in attractions],
    }
    os.makedirs("data", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump({"generated_at": result["generated_at"], "source": result["source"],
                   "total": len(attractions), "index": index}, f, ensure_ascii=False, indent=2)

    print(f"\n[完成] 融合知识库共 {len(attractions)} 个景点"
          f"（OSM {len(osm_doc['attractions'])} + 高德新增 {stats['added']}）", file=sys.stderr)
    print(f"  含等级 {n_level} / 评分 {n_rating} / 开放时间 {n_open} / 票价 {n_ticket} / 图片 {n_img}",
          file=sys.stderr)
    print(f"  高德匹配补强 {stats['matched']} 个；写入:", file=sys.stderr)
    print(f"    {out_path}", file=sys.stderr)
    print(f"    {index_path}", file=sys.stderr)


if __name__ == "__main__":
    key = load_api_key()
    if not key:
        print("错误：未配置 AMAP_API_KEY（请在 .env 或环境变量中设置）", file=sys.stderr)
        sys.exit(1)

    argv = sys.argv[1:]
    if "--test-city" in argv:
        idx = argv.index("--test-city")
        tcity = argv[idx + 1] if idx + 1 < len(argv) else "北京"
        client = AmapClient(key=key)
        pois = client.fetch_city(tcity)
        print(f"[test] {tcity} 高德拉取 {len(pois)} 个 POI")
        for p in pois[:5]:
            a = from_amap(p)
            print(f"   - {a.name} | {a.level} | 评分{a.rating} | {a.open_time[:20] if a.open_time else ''} | img={bool(a.image)}")
        sys.exit(0)

    build(OSM_PATH, OUT_PATH, INDEX_PATH, CITIES, key)
