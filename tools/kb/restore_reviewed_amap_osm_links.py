# -*- coding: utf-8 -*-
"""Restore reviewed Amap-to-OSM source links into the current KB snapshot.

Dry-run by default. Only applies links from the dated approved-link evidence
files when the Amap record ID and normalized name still match exactly and the
OSM source ID is not already owned by another KB record.
"""
import argparse
import json
from pathlib import Path

from .models import norm_name

ROOT = Path(__file__).resolve().parents[2]
KB_PATH = ROOT / "data" / "china_attractions.json"
EVIDENCE_PATHS = (
    ROOT / "reports" / "evidence" / "amap-osm-approved-node-links-2026-11-04.json",
    ROOT / "reports" / "evidence" / "amap-osm-approved-way-links-2026-11-04.json",
)


def plan(doc):
    rows = doc.get("attractions", [])
    by_id = {}
    owners = {}
    for row in rows:
        row_id = row.get("id")
        if row_id:
            by_id.setdefault(row_id, []).append(row)
        for source_id in row.get("source_ids") or []:
            owners.setdefault(source_id, set()).add(row_id)

    approved = []
    for evidence_path in EVIDENCE_PATHS:
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        criteria = evidence.get("criteria", {})
        required = (
            "exact_normalized_name",
            "unique_candidate_within_100m",
            "unique_source_id_across_kb",
            "no_existing_risk_flags",
            "no_conflicting_admin_tags",
        )
        if not all(criteria.get(key) is True for key in required):
            raise ValueError(f"Evidence criteria are incomplete: {evidence_path.name}")
        if criteria.get("max_distance_m", 10**9) > 25:
            raise ValueError(f"Evidence distance threshold is too broad: {evidence_path.name}")
        approved.extend((evidence_path.name, link) for link in evidence.get("records", []))

    changes = []
    already_present = 0
    for evidence_name, link in approved:
        matches = by_id.get(link.get("kb_id"), [])
        if len(matches) != 1:
            raise ValueError(f"Expected one KB row for {link.get('kb_id')}; found {len(matches)}")
        row = matches[0]
        source_ids = row.get("source_ids") or []
        if row.get("id") not in source_ids or not str(row.get("id", "")).startswith("amap-"):
            raise ValueError(f"Amap primary ID is not intact for {row.get('id')}")
        if norm_name(row.get("name")) != norm_name(link.get("kb_name")):
            raise ValueError(f"Name changed since review for {row.get('id')}")
        osm_id = link.get("osm_source_id")
        current_owners = owners.get(osm_id, set())
        if current_owners and current_owners != {row.get("id")}:
            raise ValueError(f"OSM ID {osm_id} is already claimed by {sorted(current_owners)}")
        if osm_id in source_ids:
            already_present += 1
            continue
        changes.append((row, osm_id, evidence_name))

    # No source ID may be assigned twice by the evidence batch.
    additions = [osm_id for _, osm_id, _ in changes]
    if len(additions) != len(set(additions)):
        raise ValueError("Approved evidence contains duplicate OSM IDs")
    return changes, already_present


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write validated links into the KB")
    args = parser.parse_args()

    doc = json.loads(KB_PATH.read_text(encoding="utf-8"))
    changes, already_present = plan(doc)
    print(f"validated_additions={len(changes)} already_present={already_present}")
    if not args.apply:
        print("Dry run only. Re-run with --apply to write the reviewed links.")
        return

    for row, osm_id, _ in changes:
        row.setdefault("source_ids", []).append(osm_id)
        sources = set(row.get("sources") or [])
        sources.update(("Amap", "OpenStreetMap"))
        row["sources"] = sorted(sources)
        row["source"] = "OpenStreetMap + Amap"
    KB_PATH.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"applied={len(changes)}")


if __name__ == "__main__":
    main()
