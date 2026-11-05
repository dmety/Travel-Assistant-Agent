"""Apply exact-ID OSM tags from a reviewed local Geofabrik evidence cache."""
import json
import argparse
from pathlib import Path

from .models import is_missing_value


ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
EVIDENCE_PATH = ROOT / "reports" / "evidence" / "osm-attraction-tag-import-2026-11-04.json"


def osm_url(osm_id: str) -> str:
    _, kind, number = osm_id.split("-", 2)
    return f"https://www.openstreetmap.org/{kind}/{number}"


def ticket_from_tags(tags: dict) -> tuple[str | None, bool]:
    fee = str(tags.get("fee") or "").strip().casefold()
    charge = str(tags.get("charge") or "").strip()
    if fee == "no":
        return "免费（OpenStreetMap 标注）", False
    if fee == "yes" and charge:
        return f"收费：{charge}（OpenStreetMap 标注）", False
    if fee == "yes":
        return "收费（金额未标注）", True
    if charge:
        return f"收费：{charge}（OpenStreetMap 标注）", False
    if fee == "unknown":
        return "收费状态未知（金额未标注；OpenStreetMap 标注）", True
    if fee:
        # Preserve non-standard OSM values verbatim without assuming currency
        # or interpreting them as a confirmed admission price.
        return f"费用标注：{fee}（单位未标注；OpenStreetMap）", False
    return None, False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write eligible empty-field updates; default is preview only")
    args = parser.parse_args()
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    evidence = json.loads(EVIDENCE_PATH.read_text(encoding="utf-8"))
    rows = {row["id"]: row for row in data["attractions"]}
    ticket_updates = open_updates = unknown_amount = conflicts = skipped_links = 0
    checked_at = evidence["retrieved_at"][:10]

    for obj in evidence["records"]:
        kb_ids = obj.get("kb_record_ids") or []
        if len(kb_ids) != 1 or kb_ids[0] not in rows:
            skipped_links += 1
            continue
        row = rows[kb_ids[0]]
        osm_id = obj["osm_id"]
        row_ids = set(row.get("source_ids") or [])
        row_ids.add(row.get("id"))
        if osm_id not in row_ids:
            raise ValueError(f"Evidence ID is not linked to KB row {row.get('id')}: {osm_id}")
        url = osm_url(osm_id)
        tags = obj.get("tags") or {}
        fee = str(tags.get("fee") or "").strip().casefold()
        charge = str(tags.get("charge") or "").strip()

        if fee == "no" and charge:
            conflicts += 1
        ticket_value, amount_unknown = ticket_from_tags(tags)
        if ticket_value and is_missing_value(row.get("ticket")):
            row["ticket"] = ticket_value
            row["ticket_status"] = "unverified"
            row["ticket_source"] = url
            row["ticket_checked_at"] = checked_at
            ticket_updates += 1
            unknown_amount += int(amount_unknown)

        opening_hours = str(tags.get("opening_hours") or "").strip()
        if opening_hours and is_missing_value(row.get("open_time")):
            row["open_time"] = opening_hours
            row["open_time_status"] = "unverified"
            row["open_time_source"] = url
            row["open_time_checked_at"] = checked_at
            open_updates += 1

    data["with_ticket"] = sum(not is_missing_value(row.get("ticket")) for row in data["attractions"])
    data["with_open_time"] = sum(not is_missing_value(row.get("open_time")) for row in data["attractions"])
    for field, key in (("ticket_status", "ticket_status_counts"), ("open_time_status", "open_time_status_counts")):
        data[key] = {
            status: sum(row.get(field) == status for row in data["attractions"])
            for status in ("verified", "unverified", "pending")
        }
    if args.apply:
        DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(json.dumps({
        "ticket_updates": ticket_updates,
        "open_time_updates": open_updates,
        "paid_but_amount_unknown": unknown_amount,
        "fee_charge_tag_conflicts": conflicts,
        "skipped_ambiguous_source_links": skipped_links,
        "mode": "apply" if args.apply else "preview",
        "status": "all imported values remain unverified",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
