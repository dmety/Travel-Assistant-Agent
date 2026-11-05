# -*- coding: utf-8 -*-
"""
知识库检索助手 —— 供 Travel-Assistant-Agent 调用。
加载知识库(china_attractions.json)，提供按城市 / 类型 / 关键词 / 坐标的检索。
"""
import json
import os
import math

from .models import gcj02_to_wgs84, is_missing_value

DEFAULT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "data", "china_attractions.json",
)


def load(path: str = None) -> dict:
    path = path or DEFAULT_PATH
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _identity_warning(row: dict) -> str:
    if is_missing_value(row.get("ticket")) and is_missing_value(row.get("open_time")):
        return ""
    sources = set(row.get("sources") or [])
    source_text = row.get("source") or ""
    for source_name in ("OpenStreetMap", "Amap"):
        if source_name in source_text:
            sources.add(source_name)
    source_ids = [str(value) for value in (row.get("source_ids") or [])]
    missing_source_id = any(
        source_name in sources
        and not any(source_id.startswith(prefix) for source_id in source_ids)
        for source_name, prefix in (("OpenStreetMap", "osm-"), ("Amap", "amap-"))
    )
    ambiguous_identity = bool(row.get("same_name_risk") or row.get("merge_review"))
    reasons = []
    if ambiguous_identity:
        reasons.append("同名候选待核")
    if missing_source_id:
        reasons.append("跨源来源ID不完整")
    if not reasons:
        return ""
    return "身份核验提示：" + "；".join(reasons) + "。使用票价或开放时间前，请核对城市、坐标和来源ID。"


def _attach_identity_warnings(rows: list) -> list:
    """Copy query results and keep a human/agent-readable warning on risky values."""
    result = []
    for row in rows:
        warning = _identity_warning(row)
        result.append({**row, "identity_warning": warning} if warning else row)
    return result


def by_city(doc: dict, city: str) -> list:
    """按城市或省份名检索。"""
    city = (city or "").strip()
    return _attach_identity_warnings([a for a in doc["attractions"]
                                      if a.get("city") == city or a.get("province") == city])


def city_introduction(doc: dict, city: str, province: str = None) -> str:
    """Return the deduplicated city introduction for an exact city key.

    When province is omitted, return a result only if the city name is unique
    across the stored introduction map.
    """
    city = (city or "").strip()
    province = (province or "").strip()
    introductions = doc.get("city_introductions", {})
    if not city:
        return ""
    if province:
        return introductions.get(f"{province}|{city}", "")
    matches = [text for key, text in introductions.items()
               if key.rsplit("|", 1)[-1] == city]
    return matches[0] if len(matches) == 1 else ""


def by_type(doc: dict, t: str) -> list:
    """按类型/分类检索（如 museum / 风景名胜 / 博物馆）。"""
    t = (t or "").strip()
    return _attach_identity_warnings([a for a in doc["attractions"] if t in (a.get("type") or "")])


def search(doc: dict, keyword: str) -> list:
    """按名称或地址关键词模糊检索。"""
    kw = (keyword or "").strip().lower()
    return _attach_identity_warnings([a for a in doc["attractions"]
                                      if kw in (a.get("name") or "").lower()
                                      or kw in (a.get("address") or "").lower()])


def _to_wgs84(lat: float, lon: float, coordinate_system: str) -> tuple[float, float]:
    system = (coordinate_system or "").strip().upper().replace("_", "-")
    if system in {"WGS84", "WGS-84"}:
        return float(lat), float(lon)
    if system in {"GCJ02", "GCJ-02"}:
        return gcj02_to_wgs84(lat, lon)
    raise ValueError(f"Unsupported coordinate system: {coordinate_system!r}; use WGS-84 or GCJ-02")


def nearby(doc: dict, lat: float, lon: float, radius_km: float = 5,
           coordinate_system: str = "WGS-84") -> list:
    """按坐标+半径(公里)检索；输入和每条记录均按其 CRS 统一到 WGS-84 后计算。"""
    if radius_km < 0:
        raise ValueError("radius_km must be non-negative")
    query_lat, query_lon = _to_wgs84(lat, lon, coordinate_system)
    if not (-90 <= query_lat <= 90 and -180 <= query_lon <= 180):
        raise ValueError("query coordinates are outside valid latitude/longitude ranges")
    out = []
    radius_m = radius_km * 1000.0
    for a in doc["attractions"]:
        try:
            record_lat, record_lon = float(a["lat"]), float(a["lon"])
            record_lat, record_lon = _to_wgs84(record_lat, record_lon, a.get("coordinateSystem"))
        except (KeyError, TypeError, ValueError):
            continue
        if not (-90 <= record_lat <= 90 and -180 <= record_lon <= 180):
            continue
        lat1, lat2 = math.radians(query_lat), math.radians(record_lat)
        dlat = lat2 - lat1
        dlon = math.radians(record_lon - query_lon)
        hav = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        distance_m = 6371008.8 * 2 * math.asin(min(1.0, math.sqrt(hav)))
        if distance_m <= radius_m:
            out.append(a)
    return _attach_identity_warnings(out)


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
