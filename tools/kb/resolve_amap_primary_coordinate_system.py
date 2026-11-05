"""Resolve CRS for Amap-only primary records currently marked mixed.

The script only updates records with an Amap primary ID, a sole matching
Amap source ID, and sources == ["Amap"]. Amap's from_amap parser assigns
GCJ-02, and the official Amap documentation confirms that coordinate system.
No OSM association is inferred.
"""
import argparse
import json
from pathlib import Path

DATA_PATH = Path(__file__).resolve().parents[2] / "data" / "china_attractions.json"
REPORT_PATH = Path(__file__).resolve().parents[2] / "reports" / "archive" / "amap-primary-crs-resolution-2026-11-04.json"
SOURCE_URL = "https://lbs.amap.com/faq/advisory/others/39838"


def eligible(row):
    ids = row.get("source_ids") or []
    return (
        row.get("coordinateSystem") == "mixed"
        and row.get("sources") == ["Amap"]
        and row.get("source") == "Amap"
        and str(row.get("id", "")).startswith("amap-")
        and ids == [row.get("id")]
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="apply the evidence-backed CRS correction")
    args = parser.parse_args()
    doc = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    rows = [row for row in doc.get("attractions", []) if eligible(row)]
    if len(rows) != 9:
        raise SystemExit(f"Expected exactly 9 Amap-only mixed rows; found {len(rows)}. No changes made.")

    changed = []
    for row in rows:
        changed.append({
            "id": row["id"],
            "name": row.get("name"),
            "city": row.get("city"),
            "source_ids": row.get("source_ids"),
            "old_coordinateSystem": row.get("coordinateSystem"),
            "new_coordinateSystem": "GCJ-02",
        })
        row["coordinateSystem"] = "GCJ-02"

    if args.apply:
        DATA_PATH.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report = {
            "applied_at": "2026-11-04",
            "changed_count": len(changed),
            "basis": [
                "Each changed row's sole source ID is its Amap primary ID and its only declared source is Amap.",
                "tools/kb/amap_source.py::from_amap sets coordinateSystem=GCJ-02.",
                "Amap official FAQ states that Amap Open Platform services use GCJ-02.",
            ],
            "official_source_url": SOURCE_URL,
            "osm_linkage_inferred": False,
            "records": changed,
        }
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"APPLIED: marked {len(changed)} Amap-primary coordinate rows GCJ-02; no OSM IDs added.")
    else:
        print(f"DRY RUN: {len(changed)} Amap-primary mixed rows qualify; no files changed.")
    for row in changed:
        print(f"{row['id']} | {row['name']} | {row['city']} | mixed -> GCJ-02")


if __name__ == "__main__":
    main()
