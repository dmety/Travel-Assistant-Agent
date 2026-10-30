#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
旅游景点知识库接入层（演示版 v0.1）

数据源：OpenStreetMap Overpass API
  - 完全免费、无需 Key、ODbL 开源许可（允许缓存/存储/修改）
  - 适合作为「开源 + 可调用 API」的景点知识库基底

功能：
  1. 按中心点 + 半径拉取 tourism 类 POI（景点/博物馆/观景台等）
  2. 归一化为统一景点模型
  3. 落库为 JSON，供后续知识库使用

用法：
  python tools/kb_ingest.py [输出路径] [纬度] [经度] [半径米]
  python tools/kb_ingest.py            # 默认：杭州西湖周边 8km
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# 公共 Overpass 实例 + 镜像，按顺序尝试
OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]

# 旅游相关 OSM 标签值
TOURISM_KINDS = [
    "attraction", "museum", "viewpoint", "artwork",
    "theme_park", "zoo", "aquarium", "gallery", "picnic_site",
]


def build_query(lat, lon, radius_m, kinds):
    conds = []
    for k in kinds:
        conds.append(f'node["tourism"="{k}"](around:{radius_m},{lat},{lon});')
        conds.append(f'way["tourism"="{k}"](around:{radius_m},{lat},{lon});')
    return f"[out:json][timeout:25];(" + "".join(conds) + ");out center 300;"


def query_overpass(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    last_err = None
    for ep in OVERPASS_ENDPOINTS:
        try:
            req = urllib.request.Request(
                ep, data=data,
                headers={"User-Agent": "TravelKbIngest/0.1 (educational)"},
            )
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.loads(resp.read().decode())
        except Exception as e:
            last_err = e
    raise last_err


def normalize(elements):
    out = []
    seen = set()
    for el in elements:
        tags = el.get("tags", {})
        if el["type"] == "way":
            c = el.get("center", {})
            lat, lon = c.get("lat"), c.get("lon")
        else:
            lat, lon = el.get("lat"), el.get("lon")
        if lat is None or lon is None:
            continue
        name = tags.get("name") or tags.get("name:zh") or tags.get("name:en")
        if not name:
            continue
        key = (round(lat, 5), round(lon, 5), name)
        if key in seen:
            continue
        seen.add(key)
        out.append({
            "id": f"osm-{el['type']}-{el['id']}",
            "name": name,
            "name_zh": tags.get("name:zh"),
            "type": tags.get("tourism"),
            "lat": lat,
            "lon": lon,
            "address": tags.get("addr:full") or tags.get("addr:city"),
            "website": tags.get("website") or tags.get("url"),
            "wikidata": tags.get("wikidata"),
            "description": tags.get("description"),
            "source": "OpenStreetMap",
        })
    return out


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "data/attractions_sample.json"
    lat = float(sys.argv[2]) if len(sys.argv) > 2 else 30.2592   # 杭州西湖
    lon = float(sys.argv[3]) if len(sys.argv) > 3 else 120.1490
    radius = int(sys.argv[4]) if len(sys.argv) > 4 else 8000

    q = build_query(lat, lon, radius, TOURISM_KINDS)
    print(f"[Overpass QL]\n{q}\n", file=sys.stderr)

    data = query_overpass(q)
    spots = normalize(data.get("elements", []))

    result = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "OpenStreetMap Overpass API (ODbL, keyless & free)",
        "center": {"lat": lat, "lon": lon, "radius_m": radius},
        "count": len(spots),
        "attractions": spots,
    }
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"[OK] 已写入 {out_path}，共 {len(spots)} 个景点\n", file=sys.stderr)
    print("前 12 条预览：", file=sys.stderr)
    for s in spots[:12]:
        print(f"  - {s['name']}  [{s['type']}]  ({s['lat']:.4f},{s['lon']:.4f})", file=sys.stderr)


if __name__ == "__main__":
    main()
