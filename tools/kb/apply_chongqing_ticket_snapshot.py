"""Apply four explicitly matched ticket rows from Chongqing's official list.

The source snapshot is dated 2025-12, so imported values remain unverified.
Only empty ticket fields are filled; existing values are never overwritten.
"""
import json
from pathlib import Path

from .models import is_missing_value


ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
SOURCE_PATH = ROOT / "reports" / "evidence" / "chongqing-official-ticket-source-2026-11-04.json"


def render_ticket(row: dict, snapshot_date: str) -> str:
    high, low = row["high_season_ticket"], row["low_season_ticket"]
    if row["fee"] == "否" and high == "0" and low == "0":
        return f"免费（官方名录标注不收费；数据{snapshot_date}）"
    if row["fee"] == "是" and high and low:
        return f"旺季门票{high}；淡季门票{low}（数据{snapshot_date}；原表未注明单位）"
    raise ValueError(f"Unsupported fee/price shape: {row}")


def main() -> None:
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source = json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
    attractions = {row["id"]: row for row in data["attractions"]}
    updates = 0

    for source_row in source["rows"]:
        row = attractions[source_row["kb_id"]]
        if (row.get("name") != source_row["kb_name"]
                or row.get("city") != "重庆"
                or row.get("province") != "重庆市"):
            raise ValueError(f"KB identity changed for {source_row['kb_id']}")
        if not is_missing_value(row.get("ticket")):
            # Safe re-run only when the exact source-derived value is already present.
            expected = render_ticket(source_row, source["source_snapshot_date"])
            if row["ticket"] != expected:
                raise ValueError(f"Refusing to overwrite existing ticket for {row['id']}")
            continue
        row["ticket"] = render_ticket(source_row, source["source_snapshot_date"])
        row["ticket_status"] = "unverified"
        row["ticket_source"] = source["source_url"]
        row["ticket_checked_at"] = source["retrieved_at"]
        updates += 1

    data["with_ticket"] = sum(not is_missing_value(row.get("ticket")) for row in data["attractions"])
    data["ticket_status_counts"] = {
        status: sum(row.get("ticket_status") == status for row in data["attractions"])
        for status in ("verified", "unverified", "pending")
    }
    DATA_PATH.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(f"Imported {updates} source-linked ticket values; status remains unverified.")


if __name__ == "__main__":
    main()
