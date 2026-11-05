# -*- coding: utf-8 -*-
"""Mark unsupported, pre-existing Hubei values as unverified.

Dry-run by default. This never changes ticket/open-time text and is restricted
to Hubei rows whose populated value is currently called verified but lacks a
row-level source URL or checked date.
"""
import argparse
import json
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KB_PATH = ROOT / "data" / "china_attractions.json"
AUDIT_PATH = ROOT / "reports" / "archive" / "hubei-unsupported-values-2026-11-04.json"


def populated(value):
    return value is not None and str(value).strip() != ""


def collect(doc):
    updates = []
    for row in doc.get("attractions", []):
        if not str(row.get("province", "")).startswith("湖北"):
            continue
        for field in ("ticket", "open_time"):
            if (populated(row.get(field))
                    and row.get(f"{field}_status") == "verified"
                    and not row.get(f"{field}_source")
                    and not row.get(f"{field}_checked_at")):
                updates.append({
                    "id": row.get("id"),
                    "name": row.get("name"),
                    "field": field,
                    "value": row.get(field),
                    "previous_status": "verified",
                    "new_status": "unverified",
                    "reason": "Existing value has no row-level source URL or checked date.",
                })
    return updates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write status changes")
    args = parser.parse_args()
    doc = json.loads(KB_PATH.read_text(encoding="utf-8"))
    updates = collect(doc)
    print(f"status_updates={len(updates)}")
    if not args.apply:
        print("Dry run only. Re-run with --apply to mark values unverified.")
        return

    by_id = {row.get("id"): row for row in doc["attractions"]}
    for item in updates:
        by_id[item["id"]][f"{item['field']}_status"] = "unverified"
    KB_PATH.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    AUDIT_PATH.write_text(json.dumps({
        "date": date.today().isoformat(),
        "scope": "Hubei only",
        "count": len(updates),
        "updates": updates,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"applied={len(updates)} audit={AUDIT_PATH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
