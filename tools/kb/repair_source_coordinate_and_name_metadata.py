"""Repair primary-source CRS labels and mark same-name groups for review.

Rules are deliberately based on the primary record ID:
- osm-* primary coordinates are WGS-84;
- amap-* primary coordinates are GCJ-02.

No missing secondary-source IDs are guessed. Dry-run by default; pass --apply
to write the approved, deterministic metadata updates.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from .models import norm_name


DATA_PATH = Path(__file__).resolve().parents[2] / "data" / "china_attractions.json"


def repair(doc: dict) -> dict:
    rows = doc.get("attractions", [])
    stats = {
        "primary_ids_added": 0,
        "osm_crs_set_wgs84": 0,
        "amap_crs_set_gcj02": 0,
        "stale_amap_labels_corrected": 0,
        "ambiguous_groups_marked": 0,
        "rows_marked_for_review": 0,
    }

    # Refuse to infer a coordinate system from a row whose primary ID is
    # inconsistent with its declared primary source.
    for row in rows:
        row_id = str(row.get("id") or "")
        sources = set(row.get("sources") or [])
        if row_id.startswith("osm-"):
            if "OpenStreetMap" not in sources:
                raise ValueError(f"OSM primary ID lacks OSM source metadata: {row_id}")
            if row.get("coordinateSystem") != "WGS-84":
                row["coordinateSystem"] = "WGS-84"
                stats["osm_crs_set_wgs84"] += 1
        elif row_id.startswith("amap-"):
            if "Amap" not in sources:
                raise ValueError(f"Amap primary ID lacks Amap source metadata: {row_id}")
            if row.get("coordinateSystem") != "GCJ-02":
                row["coordinateSystem"] = "GCJ-02"
                stats["amap_crs_set_gcj02"] += 1
            if sources == {"Amap"} and row.get("source") != "Amap":
                row["source"] = "Amap"
                stats["stale_amap_labels_corrected"] += 1
        else:
            raise ValueError(f"Unknown primary source ID format: {row_id}")

        ids = row.setdefault("source_ids", [])
        if row_id and row_id not in ids:
            ids.append(row_id)
            stats["primary_ids_added"] += 1

    groups = defaultdict(list)
    for row in rows:
        key = (str(row.get("province") or ""), norm_name(row.get("name")))
        if key[0] and key[1]:
            groups[key].append(row)
    for key, group in groups.items():
        if len(group) < 2:
            continue
        group_id = f"{key[0]}|{key[1]}"
        for row in group:
            if not row.get("same_name_group"):
                row["same_name_group"] = group_id
        if not any(row.get("same_name_risk") or row.get("merge_review") for row in group):
            stats["ambiguous_groups_marked"] += 1
            for row in group:
                if not row.get("merge_review"):
                    row["merge_review"] = "same_normalized_name_requires_identity_review"
                    stats["rows_marked_for_review"] += 1

    return stats


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write repaired metadata")
    parser.add_argument("--path", type=Path, default=DATA_PATH)
    args = parser.parse_args()

    doc = json.loads(args.path.read_text(encoding="utf-8"))
    stats = repair(doc)
    print(f"{'Applying' if args.apply else 'Preview'}: "
          + json.dumps(stats, ensure_ascii=False, sort_keys=True))
    if not args.apply:
        return
    args.path.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Written: {args.path}")


if __name__ == "__main__":
    main()
