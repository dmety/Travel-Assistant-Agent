# -*- coding: utf-8 -*-
"""Read-only audit for the six known travel knowledge-base data risks."""
import json
import argparse
import sys
from collections import Counter
from pathlib import Path

from .models import is_missing_value, norm_name

DEFAULT_PATH = Path(__file__).resolve().parents[2] / "data" / "china_attractions.json"

STRICT_FAILURE_FIELDS = (
    "placeholder_values",
    "ticket_status_inconsistencies",
    "open_time_status_inconsistencies",
    "duplicate_description_rows",
    "city_intro_records",
    "cities_without_intro",
    "orphan_city_intro_keys",
    "missing_coordinate_system",
    "unknown_coordinate_system",
    "missing_coordinates",
    "invalid_coordinates",
    "ambiguous_groups_without_risk_flag",
    "records_missing_source_ids",
    "records_missing_primary_source_id",
    "duplicate_source_id_rows",
    "source_ids_claimed_by_multiple_records",
    "merged_rows_missing_osm_id",
    "merged_rows_missing_amap_id",
    "source_metadata_inconsistencies",
)


def build_report(doc: dict) -> dict:
    rows = doc.get("attractions", [])
    ticket_missing = sum(is_missing_value(a.get("ticket")) for a in rows)
    time_missing = sum(is_missing_value(a.get("open_time")) for a in rows)
    descriptions = Counter(a.get("description") for a in rows if a.get("description"))
    duplicated_description_rows = sum(n for n in descriptions.values() if n > 1)
    coords = Counter(a.get("coordinateSystem") or "missing" for a in rows)
    allowed_coordinate_systems = {"WGS-84", "GCJ-02", "mixed"}
    intro_map = doc.get("city_introductions", {})
    city_keys = {f"{a.get('province', '')}|{a.get('city', '')}" for a in rows if a.get("city")}
    missing_city_intro_keys = city_keys - set(intro_map)
    orphan_city_intro_keys = set(intro_map) - city_keys
    invalid_coordinate_rows = 0
    missing_coordinate_rows = 0
    for row in rows:
        try:
            lat, lon = float(row["lat"]), float(row["lon"])
        except (KeyError, TypeError, ValueError):
            missing_coordinate_rows += 1
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            invalid_coordinate_rows += 1
    groups = {}
    for row in rows:
        key = (row.get("province"), norm_name(row.get("name")))
        if key[1]:
            groups.setdefault(key, []).append(row)
    ambiguous_name_groups = [g for g in groups.values() if len(g) > 1]
    merge_review_groups = [g for g in groups.values() if any(a.get("merge_review") for a in g)]
    def declared_sources(row):
        values = set(row.get("sources") or [])
        source_text = row.get("source") or ""
        for name in ("OpenStreetMap", "Amap"):
            if name in source_text:
                values.add(name)
        return values

    merged_rows = [a for a in rows if {"OpenStreetMap", "Amap"}.issubset(declared_sources(a))]
    merged_source_ids_by_row = [
        set(a.get("source_ids") or ([a.get("id")] if a.get("id") else []))
        for a in merged_rows
    ]
    source_id_lists = [
        [str(source_id) for source_id in (a.get("source_ids") or ([a.get("id")] if a.get("id") else []))]
        for a in rows
    ]
    source_id_owners = {}
    for row, ids in zip(rows, source_id_lists):
        row_key = str(row.get("id") or "")
        for source_id in set(ids):
            source_id_owners.setdefault(source_id, set()).add(row_key)
    cross_record_duplicate_ids = {
        source_id: owners for source_id, owners in source_id_owners.items()
        if len(owners) > 1
    }
    return {
        "record_count": len(rows),
        "ticket_missing": ticket_missing,
        "ticket_nonempty_unverified": sum(
            not is_missing_value(a.get("ticket"))
            and a.get("ticket_status") != "verified" for a in rows),
        "ticket_nonempty_without_source": sum(
            not is_missing_value(a.get("ticket"))
            and not a.get("ticket_source") for a in rows),
        "ticket_verified_without_audit_evidence": sum(
            not is_missing_value(a.get("ticket"))
            and a.get("ticket_status") == "verified"
            and not (a.get("ticket_source") and a.get("ticket_checked_at")) for a in rows),
        "open_time_missing": time_missing,
        "open_time_nonempty_unverified": sum(
            not is_missing_value(a.get("open_time"))
            and a.get("open_time_status") != "verified" for a in rows),
        "open_time_nonempty_without_source": sum(
            not is_missing_value(a.get("open_time"))
            and not a.get("open_time_source") for a in rows),
        "open_time_verified_without_audit_evidence": sum(
            not is_missing_value(a.get("open_time"))
            and a.get("open_time_status") == "verified"
            and not (a.get("open_time_source") and a.get("open_time_checked_at")) for a in rows),
        "placeholder_values": sum(
            isinstance(row.get(field), str) and is_missing_value(row.get(field))
            for row in rows for field in ("ticket", "open_time")),
        "ticket_status_inconsistencies": sum(
            (is_missing_value(row.get("ticket")) and row.get("ticket_status") != "pending")
            or (not is_missing_value(row.get("ticket")) and row.get("ticket_status") == "pending")
            for row in rows),
        "open_time_status_inconsistencies": sum(
            (is_missing_value(row.get("open_time")) and row.get("open_time_status") != "pending")
            or (not is_missing_value(row.get("open_time")) and row.get("open_time_status") == "pending")
            for row in rows),
        "duplicate_description_rows": duplicated_description_rows,
        "city_intro_records": sum("city_intro" in row for row in rows),
        "city_intro_keys": len(intro_map),
        "cities_without_intro": len(missing_city_intro_keys),
        "orphan_city_intro_keys": len(orphan_city_intro_keys),
        "coordinate_system_counts": dict(coords),
        "osm_primary_mixed_coordinate_rows": sum(
            str(a.get("id", "")).startswith("osm-") and a.get("coordinateSystem") == "mixed" for a in rows),
        "missing_coordinate_system": coords.get("missing", 0),
        "unknown_coordinate_system": sum(
            1 for row in rows
            if (row.get("coordinateSystem") or "missing") not in allowed_coordinate_systems
            and (row.get("coordinateSystem") or "missing") != "missing"),
        "missing_coordinates": missing_coordinate_rows,
        "invalid_coordinates": invalid_coordinate_rows,
        "ambiguous_same_name_groups": len(ambiguous_name_groups),
        "ambiguous_groups_without_risk_flag": sum(
            not any(a.get("same_name_risk") or a.get("merge_review") for a in group)
            for group in ambiguous_name_groups),
        "same_name_risk_rows": sum(bool(a.get("same_name_risk")) for a in rows),
        "same_name_risk_counts": dict(Counter(a.get("same_name_risk") for a in rows if a.get("same_name_risk"))),
        "merge_review_rows": sum(bool(a.get("merge_review")) for a in rows),
        "merge_review_groups": len(merge_review_groups),
        "merged_source_rows": len(merged_rows),
        "records_missing_source_ids": sum(not a.get("source_ids") for a in rows),
        "records_missing_primary_source_id": sum(
            bool(a.get("id")) and a.get("id") not in (a.get("source_ids") or []) for a in rows),
        "duplicate_source_id_rows": sum(len(ids) != len(set(ids)) for ids in source_id_lists),
        "source_ids_claimed_by_multiple_records": len(cross_record_duplicate_ids),
        "records_with_cross_record_source_id_duplicates": len({
            owner for owners in cross_record_duplicate_ids.values() for owner in owners
        }),
        "declared_osm_rows_missing_osm_id": sum(
            "OpenStreetMap" in declared_sources(row)
            and not any(source_id.startswith("osm-") for source_id in ids)
            for row, ids in zip(rows, source_id_lists)),
        "declared_amap_rows_missing_amap_id": sum(
            "Amap" in declared_sources(row)
            and not any(source_id.startswith("amap-") for source_id in ids)
            for row, ids in zip(rows, source_id_lists)),
        "merged_rows_missing_osm_id": sum(not any(str(i).startswith("osm-") for i in ids) for ids in merged_source_ids_by_row),
        "merged_rows_missing_amap_id": sum(not any(str(i).startswith("amap-") for i in ids) for ids in merged_source_ids_by_row),
        "source_metadata_inconsistencies": sum(
            set(row.get("sources") or []) != declared_sources(row) for row in rows),
    }


def main():
    parser = argparse.ArgumentParser(description="Audit travel knowledge-base quality.")
    parser.add_argument("--strict", action="store_true",
                        help="return nonzero if unsafe structural/data-lineage invariants fail")
    parser.add_argument("--path", type=Path, default=DEFAULT_PATH,
                        help="knowledge-base JSON path (defaults to the repository data file)")
    args = parser.parse_args()
    with args.path.open(encoding="utf-8") as f:
        report = build_report(json.load(f))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.strict:
        failures = {key: report.get(key, 0) for key in STRICT_FAILURE_FIELDS if report.get(key, 0)}
        if failures:
            print("STRICT FAIL: unresolved structural/data-lineage checks: "
                  + json.dumps(failures, ensure_ascii=False), file=sys.stderr)
            return 1
        print("STRICT PASS: structural and lineage invariants hold.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
