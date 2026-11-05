"""Preview/apply missing ticket values from Gongyi's 2026 official list."""
import argparse
import json
from pathlib import Path

from .models import is_missing_value

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
SOURCE_PATH = ROOT / "reports" / "evidence" / "gongyi-official-attraction-snapshot-2026-11-04.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write approved missing ticket fields")
    args = parser.parse_args()

    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source = json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
    by_id = {row["id"]: row for row in data["attractions"]}
    pending = []
    for item in source["rows"]:
        row = by_id[item["kb_id"]]
        if row.get("name") != item["kb_name"] or row.get("city") != item["kb_city"]:
            raise ValueError(f"Knowledge-base identity changed for {item['kb_id']}")
        if not is_missing_value(row.get("ticket")):
            raise ValueError(f"Refusing to overwrite non-empty ticket for {item['kb_id']}")
        if not item.get("ticket") or not item.get("match_reason"):
            raise ValueError(f"Missing ticket or match rationale for {item['kb_id']}")
        pending.append((row, item))

    print(f"{'Applying' if args.apply else 'Preview'}: {len(pending)} unique, empty ticket fields.")
    for row, item in pending:
        print(f"{row['id']} | {row['name']} | {item['ticket']}")
        if args.apply:
            row["ticket"] = f"{item['ticket']}（巩义市文旅局2026年公示）"
            row["ticket_status"] = "unverified"
            row["ticket_source"] = source["source_url"]
            row["ticket_checked_at"] = source["retrieved_at"]

    if args.apply:
        data["with_ticket"] = sum(not is_missing_value(row.get("ticket")) for row in data["attractions"])
        data["ticket_status_counts"] = {
            status: sum(row.get("ticket_status") == status for row in data["attractions"])
            for status in ("verified", "unverified", "pending")
        }
        DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
        print("Written; imported values remain unverified.")


if __name__ == "__main__":
    main()
