"""Apply exact, uniquely matched empty ticket fields from Hunan's 2025 directory."""
import json
from pathlib import Path

from .models import is_missing_value

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
SOURCE_PATH = ROOT / "reports" / "evidence" / "hunan-official-ticket-source-2026-11-04.json"


def main():
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source = json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
    by_id = {row["id"]: row for row in data["attractions"]}
    updates = 0
    for item in source["rows"]:
        row = by_id[item["kb_id"]]
        if (row.get("name") != item["kb_name"] or row.get("city") != item["kb_city"]
                or row.get("province") != "湖南省"):
            raise ValueError(f"Identity changed for {item['kb_id']}")
        if not is_missing_value(row.get("ticket")):
            expected = f"{item['price']}（湖南省政府管价景区目录2025，数据日期2025-04-03）"
            if row.get("ticket") != expected:
                raise ValueError(f"Refusing to overwrite existing ticket: {item['kb_id']}")
            continue
        row["ticket"] = f"{item['price']}（湖南省政府管价景区目录2025，数据日期2025-04-03）"
        row["ticket_status"] = "unverified"
        row["ticket_source"] = source["source_url"]
        row["ticket_checked_at"] = source["retrieved_at"]
        updates += 1
    data["with_ticket"] = sum(not is_missing_value(row.get("ticket")) for row in data["attractions"])
    data["ticket_status_counts"] = {
        status: sum(row.get("ticket_status") == status for row in data["attractions"])
        for status in ("verified", "unverified", "pending")
    }
    DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Imported {updates} Hunan ticket values; status remains unverified.")


if __name__ == "__main__":
    main()
