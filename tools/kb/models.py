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
