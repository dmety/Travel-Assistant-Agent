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

from kb.models import from_osm, is_missing_value, norm_name  # noqa: E402
from kb.amap_source import AmapClient, from_amap, load_api_key  # noqa: E402
from build_china_kb import CITIES  # noqa: E402

BASE = os.path.dirname(HERE)
OSM_PATH = os.path.join(BASE, "data", "china_attractions_osm.json")
OUT_PATH = os.path.join(BASE, "data", "china_attractions.json")
INDEX_PATH = os.path.join(BASE, "data", "china_attractions_index.json")


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
        cand = by_key.get((a.province, norm_name(a.name)), [])
        # Names produce candidates only. Merge only a unique same-city,
        # nearby, unambiguous pair with known compatible coordinate systems.
        # A duplicate name group is ambiguous even if only one member happens
        # to fall inside the distance threshold. Require a singleton group and
        # an explicit OSM -> Amap source pairing before automatic fusion.
        eligible = []
        for item in cand:
            if len(cand) != 1 or not item.id.startswith("osm-") or not a.id.startswith("amap-"):
                continue
            # Explicitly reviewed risk flags always require human resolution.
            # A short distance alone must not override a known name collision.
            if item.same_name_risk or item.merge_review or a.same_name_risk or a.merge_review:
                continue
            if item.city != a.city or not a.city or not item.lat or not item.lon or not a.lat or not a.lon:
                continue
            left = item.coordinateSystem or ("WGS-84" if "OpenStreetMap" in item.source and "Amap" not in item.source else "")
            right = a.coordinateSystem or "GCJ-02"
            if left not in ("WGS-84", "GCJ-02") or right not in ("WGS-84", "GCJ-02"):
                continue
            from kb.models import gcj02_to_wgs84
            la, lo = (gcj02_to_wgs84(item.lat, item.lon) if left == "GCJ-02" else (item.lat, item.lon))
            ra, ro = (gcj02_to_wgs84(a.lat, a.lon) if right == "GCJ-02" else (a.lat, a.lon))
            import math
            dlat, dlon = math.radians(la-ra), math.radians(lo-ro)
            h = math.sin(dlat/2)**2 + math.cos(math.radians(ra))*math.cos(math.radians(la))*math.sin(dlon/2)**2
            if 6371008.8 * 2 * math.asin(min(1, math.sqrt(h))) <= 1000:
                eligible.append(item)
        if len(eligible) == 1:
            tgt = eligible[0]
            known_ids = list(tgt.source_ids or ([tgt.id] if tgt.id else []))
            for source_id in (a.source_ids or [a.id]):
                if source_id and source_id not in known_ids:
                    known_ids.append(source_id)
            tgt.source_ids = known_ids
            if not tgt.level:
                tgt.level = a.level
            if not tgt.rating:
                tgt.rating = a.rating
            if not tgt.open_time:
                tgt.open_time = a.open_time
                if a.open_time:
                    tgt.open_time_source = a.open_time_source
                    tgt.open_time_checked_at = a.open_time_checked_at
            if not tgt.ticket:
                tgt.ticket = a.ticket
                if a.ticket:
                    tgt.ticket_source = a.ticket_source
                    tgt.ticket_checked_at = a.ticket_checked_at
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
            # Coordinates stay from the OSM target; only descriptive fields
            # are enriched from Amap, so the coordinate reference remains WGS-84.
            tgt.ticket_status = "unverified" if a.ticket else tgt.ticket_status
            tgt.open_time_status = "unverified" if a.open_time else tgt.open_time_status
            matched += 1
        else:
            attractions.append(a)
            by_key.setdefault((a.province, norm_name(a.name)), []).append(a)
            added += 1
    return matched, added, len(pois)


def build(osm_path, out_path, index_path, cities, key):
    with open(osm_path, encoding="utf-8") as f:
        osm_doc = json.load(f)
    prior_by_id = {}
    city_introductions = {}
    if os.path.exists(out_path):
        try:
            with open(out_path, encoding="utf-8") as f:
                prior_data = json.load(f)
                prior_by_id = {a.get("id"): a for a in prior_data.get("attractions", []) if a.get("id")}
                city_introductions = dict(prior_data.get("city_introductions", {}))
                for a in prior_data.get("attractions", []):
                    intro = a.get("city_intro")
                    if intro and a.get("city"):
                        city_introductions.setdefault(f"{a.get('province', '')}|{a['city']}", intro)
        except (OSError, ValueError, TypeError):
            prior_by_id = {}
    attractions = [from_osm(o) for o in osm_doc["attractions"]]
    for attraction in attractions:
        prior = prior_by_id.get(attraction.id, {})
        known_ids = list(attraction.source_ids or ([attraction.id] if attraction.id else []))
        for source_id in prior.get("source_ids") or ([prior.get("id")] if prior.get("id") else []):
            if source_id and source_id not in known_ids:
                known_ids.append(source_id)
        attraction.source_ids = known_ids
        for field in ("same_name_group", "same_name_risk", "merge_review"):
            if prior.get(field):
                setattr(attraction, field, prior[field])
        if prior.get("city_intro") and attraction.city:
            city_introductions.setdefault(f"{attraction.province}|{attraction.city}", prior["city_intro"])
        for field in ("ticket", "open_time", "ticket_source", "ticket_checked_at", "open_time_source", "open_time_checked_at"):
            if not is_missing_value(prior.get(field)):
                value = prior[field]
                setattr(attraction, field, value.strip() if isinstance(value, str) else value)
                # No row-level verification evidence is currently stored, so
                # historical "verified" labels cannot be carried forward.
                setattr(attraction, field + "_status", "unverified")
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

    n_level = sum(1 for a in attractions if a.level not in (None, "", "其他"))
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
        "city_introductions": city_introductions,
        "ticket_status_counts": {s: sum(1 for a in attractions if a.ticket_status == s) for s in ("verified", "unverified", "pending")},
        "open_time_status_counts": {s: sum(1 for a in attractions if a.open_time_status == s) for s in ("verified", "unverified", "pending")},
        "attractions": [{k: v for k, v in a.to_dict().items() if k != "city_intro"} for a in attractions],
    }
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
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
