#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
报告发现 #6：跨源按“省份+归一化名称”取首个候选，同省同名景点可能误合并。
本脚本为【只读审计】，不修改知识库。

逻辑：
  - 按 (province, name) 分组；组内含 >1 条记录的，视为“潜在合并/重名”候选。
  - 进一步区分风险：
      * 同组内 city 不一致 -> 高概率误合并（不同城市同名）
      * 同组内坐标互算距离 > 1km -> 不同地点同名
  - 输出汇总 + 明细到 F:/wb_tmp/cross_source_audit.json

用法:
    python tools/audit_cross_source.py
"""
import json
import os
from collections import defaultdict

BASE = r"F:\repository\Travel-Assistant-Agent"
KB = os.path.join(BASE, "data", "china_attractions.json")
OUT = r"F:\wb_tmp\cross_source_audit.json"

def haversine(a, b):
    import math
    R = 6371000.0
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = math.sin(dlat/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2
    return 2*R*math.asin(min(1, h**0.5))

def main():
    data = json.load(open(KB, encoding="utf-8"))
    recs = data["attractions"]

    groups = defaultdict(list)
    for a in recs:
        groups[(a.get("province"), a.get("name"))].append(a)

    multi = {k: v for k, v in groups.items() if len(v) > 1}
    risk_city = []   # 同组城市不一致
    risk_dist = []   # 同组坐标距离>1km
    for (prov, name), items in multi.items():
        cities = set(i.get("city") for i in items)
        if len(cities) > 1:
            risk_city.append({
                "province": prov, "name": name,
                "cities": list(cities), "count": len(items),
                "ids": [i.get("id") for i in items],
            })
        # 坐标距离：两两比较，任取最大距离
        pts = [(i.get("lat"), i.get("lon")) for i in items]
        maxd = 0.0
        for x in range(len(pts)):
            for y in range(x+1, len(pts)):
                if None not in pts[x] and None not in pts[y]:
                    maxd = max(maxd, haversine(pts[x], pts[y]))
        if maxd > 1000.0:
            risk_dist.append({
                "province": prov, "name": name,
                "max_distance_m": round(maxd), "count": len(items),
                "ids": [i.get("id") for i in items],
            })

    out = {
        "total_records": len(recs),
        "groups_with_dup_name": len(multi),
        "risk_diff_city": len(risk_city),
        "risk_far_apart": len(risk_dist),
        "detail_diff_city": risk_city,
        "detail_far_apart": risk_dist,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in out.items() if not k.startswith("detail")}, ensure_ascii=False, indent=2))
    print(f"明细已写入: {OUT}")

if __name__ == "__main__":
    main()
