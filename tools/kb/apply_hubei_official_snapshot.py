"""Preview/apply missing Hubei ticket and opening-time values."""
import argparse
import json
from pathlib import Path

from .models import is_missing_value

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
SOURCE_PATH = ROOT / "reports" / "evidence" / "hubei-official-ticket-hours-2026-11-04.json"
SUPPLEMENT_PATH = ROOT / "reports" / "evidence" / "hubei-official-museum-ticket-supplement-2026-11-04.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write approved missing Hubei fields")
    args = parser.parse_args()

    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    evidence_files = [SOURCE_PATH, SUPPLEMENT_PATH]
    evidence_sets = [json.loads(path.read_text(encoding="utf-8")) for path in evidence_files]
    by_id = {row["id"]: row for row in data["attractions"]}
    changes = []
    for evidence in evidence_sets:
        for item in evidence["records"]:
            row = by_id[item["kb_id"]]
            if row.get("province") not in ("湖北省", "湖北"):
                raise ValueError(f"Refusing non-Hubei record: {item['kb_id']}")
            if row.get("name") != item["kb_name"] or row.get("city") != item["kb_city"]:
                raise ValueError(f"Knowledge-base identity changed: {item['kb_id']}")
            for field in ("ticket", "open_time"):
                value = item.get(field)
                if not value:
                    continue
                if not is_missing_value(row.get(field)):
                    if (row.get(field) == value
                            and row.get(f"{field}_source") == item.get(f"{field}_source")):
                        continue
                    raise ValueError(f"Refusing to overwrite non-empty {field}: {item['kb_id']}")
                if not item.get(f"{field}_source") or not item.get(f"{field}_match_reason"):
                    raise ValueError(f"Missing provenance or match rationale for {item['kb_id']} {field}")
                changes.append((row, item, field, value, evidence["retrieved_at"]))

    print(f"{'Applying' if args.apply else 'Preview'}: {len(changes)} Hubei fields.")
    for row, item, field, value, checked_at in changes:
        print(f"{field} | {row['id']} | {row['name']} | {value}")
        if args.apply:
            row[field] = value
            row[f"{field}_status"] = "unverified"
            row[f"{field}_source"] = item[f"{field}_source"]
            row[f"{field}_checked_at"] = checked_at

    if not args.apply:
        return

    for field in ("ticket", "open_time"):
        data[f"with_{field}"] = sum(not is_missing_value(row.get(field)) for row in data["attractions"])
        data[f"{field}_status_counts"] = {
            status: sum(row.get(f"{field}_status") == status for row in data["attractions"])
            for status in ("verified", "unverified", "pending")
        }
    DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print("Written; all imported fields remain unverified.")


if __name__ == "__main__":
    main()
