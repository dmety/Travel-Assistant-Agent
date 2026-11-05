"""Import the exact-name Wuhan Art Museum public hours from the city tourism bureau."""
import json
from pathlib import Path

from .models import is_missing_value

ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
SOURCE_PATH = ROOT / "reports" / "evidence" / "wuhan-art-museum-hours-2026-11-05.json"


def main():
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source = json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
    rows = {row["id"]: row for row in data["attractions"]}
    imported = {"ticket": 0, "open_time": 0}
    for item in source["records"]:
        row = rows[item["kb_id"]]
        identity = (row.get("name"), row.get("province"), row.get("city"))
        expected = (item["kb_name"], item["province"], item["city"])
        if identity != expected:
            raise ValueError(f"KB identity changed for {item['kb_id']}: {identity!r}")
        for field in ("ticket", "open_time"):
            value = item.get(f"{field}_text")
            if not value or not is_missing_value(row.get(field)):
                continue
            row[field] = value
            row[f"{field}_status"] = "unverified"
            row[f"{field}_source"] = item.get("source_url", source["source_url"])
            row[f"{field}_checked_at"] = source["retrieved_at"]
            imported[field] += 1
    data["with_ticket"] = sum(not is_missing_value(row.get("ticket")) for row in data["attractions"])
    data["with_open_time"] = sum(not is_missing_value(row.get("open_time")) for row in data["attractions"])
    for field in ("ticket", "open_time"):
        data[f"{field}_status_counts"] = {
            status: sum(row.get(f"{field}_status") == status for row in data["attractions"])
            for status in ("verified", "unverified", "pending")
        }
    DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Imported {imported['ticket']} ticket and {imported['open_time']} opening-time fields; status remains unverified.")


if __name__ == "__main__":
    main()
