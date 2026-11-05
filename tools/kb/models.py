# -*- coding: utf-8 -*-
"""统一景点数据模型 —— 融合 OSM 与 高德(Amap) 两源字段。"""
import re
from dataclasses import dataclass, field, asdict


@dataclass
class Attraction:
    id: str
    name: str
    type: str = ""            # 类型/分类（如 museum / 风景名胜;...）
    city: str = ""
    province: str = ""
    lat: float = 0.0
    lon: float = 0.0
    name_zh: str = ""
    address: str = ""
    level: str = ""           # A级 / 等级（高德 keytag：5A景区 / 4A景区）
    tags: list = field(default_factory=list)  # 高德 keytag 标签（文物古迹/公园/5A景区...）
    rating: str = ""          # 评分（高德：4.9）
    open_time: str = ""       # 开放时间
    ticket: str = ""          # 票价
    tel: str = ""             # 联系电话
    website: str = ""
    image: str = ""           # 主图 URL
    photos: list = field(default_factory=list)
    wikidata: str = ""
    description: str = ""
    source: str = ""          # OpenStreetMap / Amap / OpenStreetMap + Amap
    sources: list = field(default_factory=list)
    source_ids: list = field(default_factory=list)  # IDs for each source represented by this row
    coordinateSystem: str = ""  # CRS of the primary lat/lon pair
    same_name_group: str = ""
    same_name_risk: str = ""
    merge_review: str = ""
    ticket_status: str = "pending"
    open_time_status: str = "pending"
    ticket_source: str = ""
    ticket_checked_at: str = ""
    open_time_source: str = ""
    open_time_checked_at: str = ""

    def to_dict(self):
        return asdict(self)


def norm_name(name: str) -> str:
    """归一化名称用于跨源匹配：去空格。"""
    if not name:
        return ""
    return re.sub(r"\s+", "", str(name))


_GCJ_A = 6378245.0
_GCJ_EE = 0.00669342162296594323


def _out_of_china(lat: float, lon: float) -> bool:
    return not (0.8293 <= lat <= 55.8271 and 72.004 <= lon <= 137.8347)


def _transform_lat(x: float, y: float) -> float:
    import math
    value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
    value += 0.2 * math.sqrt(abs(x))
    value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    value += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    value += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return value


def _transform_lon(x: float, y: float) -> float:
    import math
    value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
    value += 0.1 * math.sqrt(abs(x))
    value += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    value += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    value += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return value


def wgs84_to_gcj02(lat: float, lon: float) -> tuple[float, float]:
    """Approximate WGS-84 -> GCJ-02 conversion for mainland-China points."""
    import math
    lat, lon = float(lat), float(lon)
    if _out_of_china(lat, lon):
        return lat, lon
    dlat = _transform_lat(lon - 105.0, lat - 35.0)
    dlon = _transform_lon(lon - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    sinlat = math.sin(radlat)
    magic = 1.0 - _GCJ_EE * sinlat * sinlat
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_GCJ_A * (1.0 - _GCJ_EE)) / (magic * sqrtmagic) * math.pi)
    dlon = (dlon * 180.0) / (_GCJ_A / sqrtmagic * math.cos(radlat) * math.pi)
    return lat + dlat, lon + dlon


def gcj02_to_wgs84(lat: float, lon: float) -> tuple[float, float]:
    """Approximate inverse GCJ-02 -> WGS-84; Amap does not expose reverse conversion."""
    lat, lon = float(lat), float(lon)
    if _out_of_china(lat, lon):
        return lat, lon
    wgs_lat, wgs_lon = lat, lon
    for _ in range(10):
        converted_lat, converted_lon = wgs84_to_gcj02(wgs_lat, wgs_lon)
        delta_lat, delta_lon = converted_lat - lat, converted_lon - lon
        wgs_lat -= delta_lat
        wgs_lon -= delta_lon
        if max(abs(delta_lat), abs(delta_lon)) < 1e-9:
            break
    return wgs_lat, wgs_lon


def is_missing_value(value) -> bool:
    """Return whether a ticket/open-time value is absent or a placeholder.

    Explicit free-admission text is deliberately not treated as missing.
    """
    if value is None:
        return True
    if not isinstance(value, str):
        return False
    normalized = value.strip().casefold()
    return normalized in {
        "", "暂无", "无", "未知", "不详", "待补充", "待核实", "待确认",
        "待完善", "未提供", "数据缺失", "null", "none", "n/a", "na",
    }


def from_osm(o: dict) -> "Attraction":
    """从 OSM/Overpass 归一化后的字典构造 Attraction。"""
    return Attraction(
        id=o.get("id", ""),
        name=o.get("name", ""),
        type=o.get("type", "") or "",
        city=o.get("city", ""),
        province=o.get("province", ""),
        lat=o.get("lat", 0.0),
        lon=o.get("lon", 0.0),
        name_zh=o.get("name_zh") or "",
        address=o.get("address") or "",
        website=o.get("website") or "",
        wikidata=o.get("wikidata") or "",
        description=o.get("description") or "",
        source="OpenStreetMap",
        sources=["OpenStreetMap"],
        source_ids=[o.get("id", "")] if o.get("id") else [],
        coordinateSystem="WGS-84",
    )
