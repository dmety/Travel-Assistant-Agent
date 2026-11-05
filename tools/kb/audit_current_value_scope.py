"""Audit issues 3-6 only on records already carrying a ticket or opening time."""
import json
from collections import Counter
from pathlib import Path

from .models import is_missing_value, norm_name

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
OUTPUT_PATH = ROOT / "reports" / "archive" / "knowledge-base-current-value-audit-2026-11-05.json"


def declared_sources(row):
    values = set(row.get("sources") or [])
    source_text = row.get("source") or ""
    for name in ("OpenStreetMap", "Amap"):
        if name in source_text:
            values.add(name)
    return values


def audit(doc):
    all_rows = doc.get("attractions", [])
    rows = [
        row for row in all_rows
        if not is_missing_value(row.get("ticket"))
        or not is_missing_value(row.get("open_time"))
    ]
    scoped_ids = {row.get("id") for row in rows}

    descriptions = Counter(row.get("description") for row in rows if row.get("description"))
    duplicate_description_rows = sum(n for n in descriptions.values() if n > 1)
    placeholder_values = sum(
        isinstance(row.get(field), str) and is_missing_value(row.get(field))
        for row in rows for field in ("ticket", "open_time")
    )
    status_inconsistencies = {
        field: sum(
            (is_missing_value(row.get(field)) and row.get(f"{field}_status") != "pending")
            or (not is_missing_value(row.get(field)) and row.get(f"{field}_status") == "pending")
            for row in rows
        )
        for field in ("ticket", "open_time")
    }

    coords = Counter(row.get("coordinateSystem") or "missing" for row in rows)
    invalid_coordinates = 0
    missing_coordinates = 0
    for row in rows:
        try:
            lat, lon = float(row["lat"]), float(row["lon"])
        except (KeyError, TypeError, ValueError):
            missing_coordinates += 1
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            invalid_coordinates += 1

    groups = {}
    for row in all_rows:
        key = (row.get("province"), norm_name(row.get("name")))
        if key[1]:
            groups.setdefault(key, []).append(row)
    relevant_ambiguous_groups = [
        group for group in groups.values()
        if len(group) > 1 and any(row.get("id") in scoped_ids for row in group)
    ]
    relevant_risk_rows = [
        row for row in rows if row.get("same_name_risk") or row.get("merge_review")
    ]

    source_id_lists = {
        row.get("id"): [str(source_id) for source_id in
                         (row.get("source_ids") or ([row.get("id")] if row.get("id") else []))]
        for row in all_rows
    }
    owners = {}
    for row_id, ids in source_id_lists.items():
        for source_id in set(ids):
            owners.setdefault(source_id, set()).add(row_id)
    cross_record_duplicates = {
        source_id: row_ids for source_id, row_ids in owners.items()
        if len(row_ids) > 1 and row_ids.intersection(scoped_ids)
    }
    merged_rows = [
        row for row in rows
        if {"OpenStreetMap", "Amap"}.issubset(declared_sources(row))
    ]
    review_groups = []
    for group in relevant_ambiguous_groups:
        review_groups.append({
            "name": group[0].get("name"),
            "members": [{
                "id": row.get("id"),
                "city": row.get("city"),
                "address": row.get("address"),
                "lat": row.get("lat"),
                "lon": row.get("lon"),
                "coordinate_system": row.get("coordinateSystem"),
                "in_scope": row.get("id") in scoped_ids,
                "ticket_present": not is_missing_value(row.get("ticket")),
                "open_time_present": not is_missing_value(row.get("open_time")),
                "same_name_risk": row.get("same_name_risk"),
                "merge_review": row.get("merge_review"),
                "source_ids": row.get("source_ids") or [],
            } for row in group],
        })
    relevant_unflagged_groups = sum(
        not any(row.get("same_name_risk") or row.get("merge_review") for row in group)
        for group in relevant_ambiguous_groups
    )

    return {
        "scope": "rows with an existing non-missing ticket or open_time; no new ticket/time fields added",
        "scope_record_count": len(rows),
        "ticket_value_count": sum(not is_missing_value(row.get("ticket")) for row in rows),
        "open_time_value_count": sum(not is_missing_value(row.get("open_time")) for row in rows),
        "issue_3": {
            "duplicate_description_rows": duplicate_description_rows,
            "rows_with_city_intro_field": sum("city_intro" in row for row in rows),
        },
        "issue_4": {
            "placeholder_values": placeholder_values,
            "ticket_status_inconsistencies": status_inconsistencies["ticket"],
            "open_time_status_inconsistencies": status_inconsistencies["open_time"],
        },
        "issue_5": {
            "coordinate_system_counts": dict(coords),
            "osm_primary_mixed_coordinate_rows": sum(
                str(row.get("id", "")).startswith("osm-")
                and row.get("coordinateSystem") == "mixed" for row in rows
            ),
            "missing_coordinate_system": coords.get("missing", 0),
            "unknown_coordinate_system": sum(
                count for system, count in coords.items()
                if system not in {"WGS-84", "GCJ-02", "mixed", "missing"}
            ),
            "missing_coordinates": missing_coordinates,
            "invalid_coordinates": invalid_coordinates,
        },
        "issue_6": {
            "relevant_ambiguous_same_name_groups": len(relevant_ambiguous_groups),
            "relevant_ambiguous_groups_without_risk_flag": relevant_unflagged_groups,
            "risk_or_review_rows": len(relevant_risk_rows),
            "merge_review_rows": sum(bool(row.get("merge_review")) for row in rows),
            "merge_review_groups": sum(
                any(row.get("merge_review") for row in group)
                for group in relevant_ambiguous_groups
            ),
            "merged_source_rows": len(merged_rows),
            "merged_rows_missing_osm_id": sum(
                not any(source_id.startswith("osm-") for source_id in source_id_lists.get(row.get("id"), []))
                for row in merged_rows
            ),
            "merged_rows_missing_amap_id": sum(
                not any(source_id.startswith("amap-") for source_id in source_id_lists.get(row.get("id"), []))
                for row in merged_rows
            ),
            "records_missing_source_ids": sum(not row.get("source_ids") for row in rows),
            "records_missing_primary_source_id": sum(
                bool(row.get("id")) and row.get("id") not in (row.get("source_ids") or [])
                for row in rows
            ),
            "duplicate_source_id_rows": sum(
                len(source_id_lists.get(row.get("id"), []))
                != len(set(source_id_lists.get(row.get("id"), [])))
                for row in rows
            ),
            "cross_record_duplicate_source_ids_touching_scope": len(cross_record_duplicates),
            "source_metadata_inconsistencies": sum(
                set(row.get("sources") or []) != declared_sources(row) for row in rows
            ),
            "review_groups": review_groups,
        },
    }


def main():
    doc = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    result = audit(doc)
    OUTPUT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
