"""Extract current OSM fee/opening-hours tags for already-linked KB OSM IDs.

Requires the optional ``osmium`` Python package and a local China PBF extract.
The script writes a review cache only; it never edits the attraction database.
"""
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
OUTPUT_PATH = ROOT / "reports" / "evidence" / "osm-attraction-tag-import-2026-11-04.json"
DEFAULT_PBF = Path(os.environ.get("TEMP", "/tmp")) / "travel-assistant-china-latest.osm.pbf"
PBF_PATH = Path(os.environ.get("OSM_CHINA_PBF", str(DEFAULT_PBF)))
OSM_ID_PATTERN = re.compile(r"^osm-(node|way|relation)-(\d+)$")
RELEVANT_TAGS = ("name", "name:zh", "tourism", "opening_hours", "fee", "charge")


def main() -> None:
    try:
        import osmium
    except ImportError as exc:
        raise SystemExit("Install optional dependency 'osmium' before parsing the PBF.") from exc

    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    linked_ids: dict[str, set[str]] = {}
    for row in data["attractions"]:
        ids = set(row.get("source_ids") or [])
        if row.get("id"):
            ids.add(row["id"])
        for source_id in ids:
            match = OSM_ID_PATTERN.fullmatch(str(source_id))
            if match:
                linked_ids.setdefault(str(source_id), set()).add(row["id"])
    wanted = set(linked_ids)
    extracted: dict[str, dict] = {}

    class Handler(osmium.SimpleHandler):
        def _record(self, kind, obj):
            osm_id = f"osm-{kind}-{obj.id}"
            if osm_id not in wanted:
                return
            tags = {key: obj.tags.get(key) for key in RELEVANT_TAGS if obj.tags.get(key)}
            if not any(key in tags for key in ("opening_hours", "fee", "charge")):
                return
            extracted[osm_id] = {
                "osm_id": osm_id,
                "version": getattr(obj, "version", None),
                "object_timestamp": str(getattr(obj, "timestamp", "")),
                "tags": tags,
                "kb_record_ids": sorted(linked_ids[osm_id]),
            }

        def node(self, obj):
            self._record("node", obj)

        def way(self, obj):
            self._record("way", obj)

        def relation(self, obj):
            self._record("relation", obj)

    if not PBF_PATH.is_file():
        raise SystemExit(f"PBF not found: {PBF_PATH}")
    handler = Handler()
    handler.apply_file(str(PBF_PATH), locations=False)

    evidence = {
        "source": "Geofabrik OpenStreetMap China extract",
        "source_url": "https://download.geofabrik.de/asia/china-latest.osm.pbf",
        "source_last_modified": os.environ.get("OSM_CHINA_PBF_LAST_MODIFIED"),
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "pbf_path": str(PBF_PATH),
        "linked_osm_ids_checked": len(wanted),
        "objects_with_fee_or_opening_hours": len(extracted),
        "records": [extracted[key] for key in sorted(extracted)],
    }
    OUTPUT_PATH.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Checked {len(wanted)} linked OSM IDs; cached {len(extracted)} objects with fee/opening-hours tags."
    )
    print(f"Review cache: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
