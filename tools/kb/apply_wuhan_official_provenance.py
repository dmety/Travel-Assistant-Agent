"""Attach official Wuhan source evidence to exact matching Hubei KB values."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
EVIDENCE_PATH = ROOT / "reports" / "wuhan-official-provenance-2026-11-05.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write exact-match provenance updates")
    args = parser.parse_args()
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    evidence = json.loads(EVIDENCE_PATH.read_text(encoding="utf-8"))
    rows = {row["id"]: row for row in data["attractions"]}
    counts = {"ticket_source": 0, "ticket_value_refresh": 0, "open_time_source": 0, "open_time_value_refresh": 0, "skipped_existing_source": 0}

    for item in evidence["records"]:
        row = rows.get(item["kb_id"])
        if row is None:
            raise ValueError(f"Missing knowledge-base row: {item['kb_id']}")
        identity = (row.get("name"), row.get("province"), row.get("city"))
        expected = (item["kb_name"], item["province"], item["city"])
        if identity != expected:
            raise ValueError(f"Identity changed for {item['kb_id']}: {identity!r}")

        for field in ("ticket", "open_time"):
            source_url = item.get(f"{field}_source_url")
            if not source_url:
                continue
            expected_value = item.get(f"{field}_expected_value")
            replacement = item.get(f"{field}_replacement_value")
            current_value = row.get(field)
            if current_value != expected_value and not (
                replacement is not None
                and current_value == replacement
                and row.get(f"{field}_source") == source_url
            ):
                raise ValueError(f"Value changed for {item['kb_id']} {field}; refusing source attachment")
            source_key = f"{field}_source"
            checked_key = f"{field}_checked_at"
            if row.get(source_key):
                counts["skipped_existing_source"] += 1
                continue
            if replacement is not None:
                if args.apply:
                    row[field] = replacement
                counts[f"{field}_value_refresh"] += 1
            if args.apply:
                row[source_key] = source_url
                row[checked_key] = evidence["retrieved_at"]
                row[f"{field}_status"] = "unverified"
                supporting_url = item.get(f"{field}_supporting_source_url")
                if supporting_url:
                    row[f"{field}_supporting_source"] = supporting_url
            counts[f"{field}_source"] += 1

    if args.apply:
        DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(json.dumps({"mode": "apply" if args.apply else "preview", **counts}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
