# -*- coding: utf-8 -*-
"""
知识库检索助手 —— 供 Travel-Assistant-Agent 调用。
加载融合知识库(china_attractions_unified.json)，提供按城市 / 类型 / 关键词 / 坐标的检索。
"""
import json
import os

DEFAULT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "data", "china_attractions_unified.json",
)


def load(path: str = None) -> dict:
    path = path or DEFAULT_PATH
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def by_city(doc: dict, city: str) -> list:
    """按城市或省份名检索。"""
    city = (city or "").strip()
    return [a for a in doc["attractions"]
            if a.get("city") == city or a.get("province") == city]


def by_type(doc: dict, t: str) -> list:
    """按类型/分类检索（如 museum / 风景名胜 / 博物馆）。"""
    t = (t or "").strip()
    return [a for a in doc["attractions"] if t in (a.get("type") or "")]


def search(doc: dict, keyword: str) -> list:
    """按名称或地址关键词模糊检索。"""
    kw = (keyword or "").strip().lower()
    return [a for a in doc["attractions"]
            if kw in (a.get("name") or "").lower()
            or kw in (a.get("address") or "").lower()]


def nearby(doc: dict, lat: float, lon: float, radius_km: float = 5) -> list:
    """按坐标 + 半径(公里)检索周边景点。"""
    import math
    out = []
    r2 = radius_km ** 2
    for a in doc["attractions"]:
        dlat = (a.get("lat", 0) - lat) * 111.0
        dlon = (a.get("lon", 0) - lon) * 111.0 * math.cos(math.radians(lat))
        if dlat * dlat + dlon * dlon <= r2:
            out.append(a)
    return out


def stats(doc: dict) -> dict:
    return {k: doc.get(k) for k in (
        "count", "osm_count", "with_level", "with_rating",
        "with_open_time", "with_ticket", "with_image", "amap_stats")}


if __name__ == "__main__":
    import sys
    doc = load()
    print("知识库统计:", stats(doc))
    q = sys.argv[1] if len(sys.argv) > 1 else "故宫"
    res = search(doc, q)
    print(f"\n检索 '{q}' 命中 {len(res)} 条，示例前 5：")
    for a in res[:5]:
        print(f"  - {a['name']} | {a.get('city','')} | 等级{a.get('level','')} | 评分{a.get('rating','')}")
