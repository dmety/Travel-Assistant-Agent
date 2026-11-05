"""Repair stale human-readable source labels for unresolved mixed-coordinate rows.

Only rows whose primary and sole source ID is an Amap ID, whose structured
sources list is exactly ["Amap"], and whose coordinates remain explicitly
marked mixed are eligible. No source IDs or coordinate claims are inferred.
"""
import argparse
import json
from pathlib import Path

DATA_PATH = Path(__file__).resolve().parents[2] / "data" / "china_attractions.json"
REPORT_PATH = Path(__file__).resolve().parents[2] / "reports" / "stale-source-label-repair-2026-11-04.json"


def eligible(row):
    ids = row.get("source_ids") or []
    return (
        row.get("coordinateSystem") == "mixed"
        and row.get("sources") == ["Amap"]
        and str(row.get("id", "")).startswith("amap-")
        and ids == [row.get("id")]
        and "OpenStreetMap" in (row.get("source") or "")
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write the reviewed source-label repair")
    args = parser.parse_args()

    doc = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    rows = [row for row in doc.get("attractions", []) if eligible(row)]
    if len(rows) != 9:
        raise SystemExit(f"Expected exactly 9 evidence-qualified stale labels; found {len(rows)}. No changes made.")

    changed = []
    for row in rows:
        changed.append({
            "id": row["id"],
            "name": row.get("name"),
            "city": row.get("city"),
            "old_source": row.get("source"),
            "new_source": "Amap",
            "source_ids": row.get("source_ids"),
            "coordinateSystem_retained": row.get("coordinateSystem"),
        })
        row["source"] = "Amap"

    if args.apply:
        DATA_PATH.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report = {
            "applied_at": "2026-11-04",
            "changed_count": len(changed),
            "rationale": "Structured source list and sole source ID identify Amap; offline exact-name OSM review found no reliable OSM object for these 9 records. Correct stale display label only; keep mixed coordinateSystem and do not invent OSM IDs.",
            "records": changed,
        }
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"APPLIED {len(changed)} source-label repairs; mixed coordinates retained.")
    else:
        print(f"DRY RUN: {len(changed)} source-label repairs eligible; no files changed.")
    for row in changed:
        print(f"{row['id']} | {row['name']} | {row['city']} | {row['old_source']} -> {row['new_source']}")


if __name__ == "__main__":
    main()
