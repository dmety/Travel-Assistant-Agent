#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
报告发现 #4 的展示层配套：数据层用 null 表示缺失，展示层需转回 "暂无/待补充"。

本模块提供可复用的转换函数，供 API / JSON 序列化层调用——
下游在对外返回景点数据时，应先用 record_to_display() / kb_to_display()
把 null 字段转成 "暂无"，避免前端显示空白。

注意：本文件只负责"展示转换"，不改变数据层（KB 仍保持 null 语义）。
若项目有 Spring Boot 后端，应在 DTO 序列化阶段等价实现此逻辑。
"""
DISPLAY_NULL = "暂无"
_MISSING_TEXT = {
    "", "暂无", "无", "未知", "不详", "待补充", "待核实", "待确认",
    "待完善", "未提供", "数据缺失", "null", "none", "n/a", "na",
}

# 展示层需将 null 还原为"暂无"的字段
DISPLAY_FIELDS = [
    "ticket", "open_time", "tel", "website", "image",
    "wikidata", "rating", "description",
]

# 元数据字段（数据治理用，不进入展示层，避免被渲染成"暂无"）
META_FIELDS = [
    "same_name_group", "ticket_status", "open_time_status", "same_name_risk",
    "merge_review",
]

def field_to_display(value):
    """Missing/null/placeholder values render as '暂无'; explicit free text stays intact."""
    if value is None:
        return DISPLAY_NULL
    if isinstance(value, str) and value.strip().casefold() in _MISSING_TEXT:
        return DISPLAY_NULL
    return value


def _has_existing_ticket_or_open_time(record):
    for field in ("ticket", "open_time"):
        value = record.get(field)
        if value is None:
            continue
        if isinstance(value, str) and value.strip().casefold() in _MISSING_TEXT:
            continue
        return True
    return False


def _identity_warning(record):
    declared_sources = set(record.get("sources") or [])
    source_text = record.get("source") or ""
    for source_name in ("OpenStreetMap", "Amap"):
        if source_name in source_text:
            declared_sources.add(source_name)
    source_ids = [str(value) for value in (record.get("source_ids") or [])]
    incomplete_source_ids = any(
        source_name in declared_sources
        and not any(source_id.startswith(prefix) for source_id in source_ids)
        for source_name, prefix in (("OpenStreetMap", "osm-"), ("Amap", "amap-"))
    )
    same_name_flagged = bool(record.get("same_name_risk") or record.get("merge_review"))
    if not same_name_flagged and not incomplete_source_ids:
        return None
    reasons = []
    if same_name_flagged:
        reasons.append("同名候选待核")
    if incomplete_source_ids:
        reasons.append("跨源来源ID不完整")
    return (
        "身份核验提示：" + "；".join(reasons)
        + "。使用本条票价或开放时间前，请核对城市、坐标和来源ID。"
    )


def record_to_display(record):
    """返回展示层用的记录副本：剔除元数据字段、null 字段转 '暂无'。原记录不变。"""
    out = {k: v for k, v in record.items() if k not in META_FIELDS}
    for f in DISPLAY_FIELDS:
        if f in out:
            out[f] = field_to_display(out[f])
    if _has_existing_ticket_or_open_time(record):
        warning = _identity_warning(record)
        if warning:
            out["identity_warning"] = warning
    return out

def kb_to_display(kb):
    """整库转换为展示层结构（attractions 每条都过 record_to_display）。"""
    data = dict(kb)
    data["attractions"] = [record_to_display(a) for a in kb.get("attractions", [])]
    return data

if __name__ == "__main__":
    import json, sys
    p = sys.argv[1] if len(sys.argv) > 1 else \
        r"F:\repository\Travel-Assistant-Agent\data\china_attractions.json"
    kb = json.load(open(p, encoding="utf-8"))
    disp = kb_to_display(kb)
    nulls = sum(1 for a in disp["attractions"] if a.get("ticket") is None)
    print("转换后 ticket 仍为 null 的条数:", nulls, "(应=0)")
    print("示例首条:", json.dumps(disp["attractions"][0], ensure_ascii=False)[:220])
