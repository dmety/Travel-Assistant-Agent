"""Scan local OSM PBF ways for exact-name, spatially close Amap POIs.

This is candidate generation only. It records one-to-one matches only after
requiring exact normalized names, a unique eligible candidate within 100 m,
no pre-existing OSM ID, and no KB risk/review flags. A separate reviewer and
apply step enforce tighter distance, feature and administrative checks.
"""
import tempfile
import json
import math
import os
import unicodedata
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from .models import gcj02_to_wgs84

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
OUTPUT_PATH = Path(tempfile.gettempdir()) / "travel-assistant-kb-work" / "amap-osm-way-candidates-2026-11-04.json"
PBF_PATH = Path(os.environ.get("TEMP", ".")) / "travel-assistant-china-latest.osm.pbf"
FEATURE_KEYS = ("tourism", "leisure", "amenity", "historic", "natural", "boundary", "addr:city", "addr:province", "addr:state")


def norm(value):
    return "".join(unicodedata.normalize("NFKC", str(value or "")).casefold().split())


def distance_m(a, b):
    rad = math.pi / 180
    dlat = (b[0] - a[0]) * rad
    dlon = (b[1] - a[1]) * rad
    h = math.sin(dlat / 2) ** 2 + math.cos(a[0] * rad) * math.cos(b[0] * rad) * math.sin(dlon / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(h)))


def segment_distance_m(point, start, end):
    # Local equirectangular projection is accurate enough for a 100 m screen.
    lat0 = math.radians(point[0])
    scale_x = 6371000 * math.cos(lat0) * math.pi / 180
    scale_y = 6371000 * math.pi / 180
    px, py = 0.0, 0.0
    ax, ay = (start[1] - point[1]) * scale_x, (start[0] - point[0]) * scale_y
    bx, by = (end[1] - point[1]) * scale_x, (end[0] - point[0]) * scale_y
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / denom))
    return math.hypot(ax + t * dx, ay + t * dy)


def point_in_polygon(point, coords):
    if len(coords) < 4 or coords[0] != coords[-1]:
        return False
    y, x = point
    inside = False
    j = len(coords) - 1
    for i, (yi, xi) in enumerate(coords):
        yj, xj = coords[j]
        if (yi > y) != (yj > y):
            crossing_x = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < crossing_x:
                inside = not inside
        j = i
    return inside


def geometry_distance(point, coords):
    if not coords:
        return None
    if point_in_polygon(point, coords):
        return 0.0
    if len(coords) == 1:
        return distance_m(point, coords[0])
    return min(segment_distance_m(point, a, b) for a, b in zip(coords, coords[1:]))


def main():
    import osmium

    if not PBF_PATH.is_file():
        raise SystemExit(f"China PBF not found: {PBF_PATH}")
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    targets = []
    for row in data["attractions"]:
        ids = set(row.get("source_ids") or [])
        if row.get("id"):
            ids.add(row["id"])
        if (str(row.get("id", "")).startswith("amap-")
                and row.get("coordinateSystem") == "GCJ-02"
                and not any(str(x).startswith("osm-") for x in ids)
                and not row.get("same_name_risk") and not row.get("merge_review")
                and row.get("name") and row.get("lat") is not None and row.get("lon") is not None):
            point = gcj02_to_wgs84(float(row["lat"]), float(row["lon"]))
            targets.append({"id": row["id"], "name": row["name"], "city": row.get("city"), "province": row.get("province"), "point": point})

    target_by_name = defaultdict(list)
    for row in targets:
        target_by_name[norm(row["name"])].append(row)

    ways = {}

    class WayHandler(osmium.SimpleHandler):
        def way(self, obj):
            name = obj.tags.get("name") or obj.tags.get("name:zh")
            key = norm(name)
            if not key or key not in target_by_name:
                return
            ways[obj.id] = {
                "name": name,
                "refs": [node.ref for node in obj.nodes],
                "tags": {k: obj.tags.get(k) for k in FEATURE_KEYS if obj.tags.get(k)},
            }

    WayHandler().apply_file(str(PBF_PATH), locations=False)
    needed_refs = {ref for way in ways.values() for ref in way["refs"]}
    locations = {}

    class NodeHandler(osmium.SimpleHandler):
        def node(self, obj):
            if obj.id in needed_refs and obj.location.valid():
                locations[obj.id] = (obj.location.lat, obj.location.lon)

    NodeHandler().apply_file(str(PBF_PATH), locations=False)

    raw = []
    for way_id, way in ways.items():
        coords = [locations[ref] for ref in way["refs"] if ref in locations]
        if not coords:
            continue
        key = norm(way["name"])
        for row in target_by_name[key]:
            dist = geometry_distance(row["point"], coords)
            if dist is not None and dist <= 100:
                raw.append({
                    "kb_id": row["id"], "kb_name": row["name"], "city": row["city"], "province": row["province"],
                    "osm_type": "way", "osm_id": way_id, "distance_m": round(dist, 1),
                    "osm_name": way["name"], "tags": way["tags"], "geometry_nodes": len(coords),
                })

    by_kb = defaultdict(list)
    by_osm = defaultdict(set)
    for item in raw:
        by_kb[item["kb_id"]].append(item)
        by_osm[item["osm_id"]].add(item["kb_id"])
    unique = [
        items[0] for kb_id, items in by_kb.items()
        if len(items) == 1 and items[0]["distance_m"] <= 50 and len(by_osm[items[0]["osm_id"]]) == 1
    ]
    report = {
        "source": "Geofabrik China OSM PBF",
        "source_url": "https://download.geofabrik.de/asia/china-latest.osm.pbf",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "method": "exact normalized Amap name; Amap GCJ-02 point converted to WGS-84; point-in-closed-way-polygon or minimum point-to-way-segment distance",
        "scope": "Amap-primary GCJ-02 records without OSM IDs or risk flags; candidate evidence only",
        "eligible_amap_rows": len(targets),
        "exact_name_ways": len(ways),
        "raw_pairs_within_100m": len(raw),
        "kb_rows_with_way_candidate": len(by_kb),
        "one_to_one_way_candidates_within_50m": len(unique),
        "candidates": sorted(unique, key=lambda item: (item["kb_id"], item["distance_m"])),
        "rejected_close_candidate_rows": sum(1 for items in by_kb.values() if len(items) != 1),
    }
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("eligible_amap_rows", "exact_name_ways", "raw_pairs_within_100m", "kb_rows_with_way_candidate", "one_to_one_way_candidates_within_50m", "rejected_close_candidate_rows")}, ensure_ascii=False))
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()
