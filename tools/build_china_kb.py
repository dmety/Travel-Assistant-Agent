#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
中国景点知识库构建器 —— Agent 基础数据
=================================================
数据源 : OpenStreetMap / Overpass API
        完全免费、无需 Key、ODbL 开源许可（允许缓存/存储/修改）
输出   :
  data/china_attractions_osm.json         全量景点（OSM 原始基底，供融合库输入）
  data/china_attractions_osm_index.json   按 省 -> 市 -> [景点id] 分组索引（便于检索）

用法:
  python tools/build_china_kb.py            # 默认跑 8 个核心旅游城市（种子库）
  python tools/build_china_kb.py --all      # 跑全部内置城市（全国主要旅游城市）
  python tools/build_china_kb.py --seed 5   # 跑前 5 个城市
"""
import json
import os
import sys
import time
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

# 中国主要城市: (市名, 省/直辖市, 纬度, 经度, 半径米)
# 覆盖直辖市 + 主要省会 + 热门旅游城市；可继续往里加。
CITIES = [
    ("北京", "北京市", 39.9042, 116.4074, 15000),
    ("上海", "上海市", 31.2304, 121.4737, 18000),
    ("重庆", "重庆市", 29.5630, 106.5516, 20000),
    ("天津", "天津市", 39.3434, 117.3616, 18000),
    ("西安", "陕西省", 34.3416, 108.9398, 18000),
    ("成都", "四川省", 30.5728, 104.0668, 18000),
    ("杭州", "浙江省", 30.2741, 120.1551, 15000),
    ("苏州", "江苏省", 31.2989, 120.5853, 15000),
    ("南京", "江苏省", 32.0603, 118.7969, 16000),
    ("广州", "广东省", 23.1291, 113.2644, 18000),
    ("深圳", "广东省", 22.5431, 114.0579, 16000),
    ("武汉", "湖北省", 30.5928, 114.3055, 18000),
    ("长沙", "湖南省", 28.2282, 112.9388, 16000),
    ("厦门", "福建省", 24.4798, 118.0894, 14000),
    ("青岛", "山东省", 36.0671, 120.3826, 16000),
    ("昆明", "云南省", 24.8801, 102.8329, 16000),
    ("丽江", "云南省", 26.8721, 100.2299, 12000),
    ("桂林", "广西壮族自治区", 25.2736, 110.2990, 14000),
    ("三亚", "海南省", 18.2528, 109.5119, 14000),
    ("拉萨", "西藏自治区", 29.6520, 91.1721, 14000),
    ("哈尔滨", "黑龙江省", 45.8038, 126.5347, 16000),
    ("沈阳", "辽宁省", 41.8057, 123.4315, 16000),
    ("济南", "山东省", 36.6512, 117.1201, 16000),
    ("郑州", "河南省", 34.7466, 113.6254, 16000),
    ("福州", "福建省", 26.0745, 119.2965, 14000),
    ("南昌", "江西省", 28.6829, 115.8579, 14000),
    ("合肥", "安徽省", 31.8206, 117.2272, 14000),
    ("太原", "山西省", 37.8706, 112.5489, 14000),
    ("石家庄", "河北省", 38.0428, 114.5149, 14000),
    ("兰州", "甘肃省", 36.0611, 103.8343, 14000),
    ("西宁", "青海省", 36.6171, 101.7782, 12000),
    ("银川", "宁夏回族自治区", 38.4872, 106.2309, 12000),
    ("乌鲁木齐", "新疆维吾尔自治区", 43.8256, 87.6168, 14000),
    ("呼和浩特", "内蒙古自治区", 40.8426, 111.7492, 14000),
    ("贵阳", "贵州省", 26.6477, 106.6302, 14000),
    ("南宁", "广西壮族自治区", 22.8170, 108.3665, 14000),
    ("海口", "海南省", 20.0444, 110.1989, 12000),
]


def build_query(lat, lon, radius_m, kinds):
    conds = []
    for k in kinds:
        conds.append(f'node["tourism"="{k}"](around:{radius_m},{lat},{lon});')
        conds.append(f'way["tourism"="{k}"](around:{radius_m},{lat},{lon});')
    return f"[out:json][timeout:30];(" + "".join(conds) + ");out center 500;"


def query_overpass(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    last_err = None
    for ep in OVERPASS_ENDPOINTS:
        try:
            req = urllib.request.Request(
                ep, data=data,
                headers={"User-Agent": "ChinaKbBuilder/0.1 (educational)"},
            )
            with urllib.request.urlopen(req, timeout=150) as resp:
                return json.loads(resp.read().decode())
        except Exception as e:
            last_err = e
    raise last_err


def normalize(elements, city, province):
    out = []
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
        out.append({
            "id": f"osm-{el['type']}-{el['id']}",
            "name": name,
            "name_zh": tags.get("name:zh"),
            "type": tags.get("tourism"),
            "city": city,
            "province": province,
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
    argv = sys.argv[1:]
    if "--all" in argv:
        cities = CITIES
    else:
        # 默认种子：前 8 个核心旅游城市
        try:
            idx = argv.index("--seed")
            n = int(argv[idx + 1])
        except (ValueError, IndexError):
            n = 8
        cities = CITIES[:n]

    all_spots = []
    seen = set()
    city_stats = []
    for name, prov, lat, lon, r in cities:
        q = build_query(lat, lon, r, TOURISM_KINDS)
        try:
            data = query_overpass(q)
            spots = normalize(data.get("elements", []), name, prov)
            before = len(all_spots)
            for s in spots:
                key = (round(s["lat"], 5), round(s["lon"], 5), s["name"])
                if key in seen:
                    continue
                seen.add(key)
                all_spots.append(s)
            added = len(all_spots) - before
            city_stats.append((name, added))
            print(f"  [OK] {name:<6} 新增 {added:>4} 个景点", file=sys.stderr)
        except Exception as e:
            city_stats.append((name, -1))
            print(f"  [FAIL] {name}: {e}", file=sys.stderr)
        time.sleep(1.5)  # 礼貌限流，避免被公共实例封禁

    # 生成按 省->市->[id] 索引
    index = {}
    for s in all_spots:
        index.setdefault(s["province"], {}).setdefault(s["city"], []).append(s["id"])

    result = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": "OpenStreetMap Overpass API (ODbL, keyless & free)",
        "note": "作为 Travel-Assistant-Agent 的中国景点知识库基底；可由高德/OpenTripMap 适配器补强字段",
        "city_count": len(cities),
        "count": len(all_spots),
        "attractions": all_spots,
    }
    os.makedirs("data", exist_ok=True)
    with open("data/china_attractions_osm.json", "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    with open("data/china_attractions_osm_index.json", "w", encoding="utf-8") as f:
        json.dump({
            "generated_at": result["generated_at"],
            "source": result["source"],
            "total": len(all_spots),
            "index": index,
        }, f, ensure_ascii=False, indent=2)

    print(f"\n[完成] 共 {len(all_spots)} 个中国景点，写入:", file=sys.stderr)
    print("  data/china_attractions_osm.json", file=sys.stderr)
    print("  data/china_attractions_osm_index.json", file=sys.stderr)
    print("\n各城市新增:", file=sys.stderr)
    for c, n in city_stats:
        print(f"  {c}: {n}", file=sys.stderr)


if __name__ == "__main__":
    main()
