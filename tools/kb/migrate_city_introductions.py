"""Move repeated attraction city_intro text into one root-level city map.

Dry-run by default. Pass --apply to rewrite the knowledge-base JSON.
"""
import argparse
import json
from pathlib import Path


DATA_PATH = Path(__file__).resolve().parents[2] / "data" / "china_attractions.json"


def migrate(doc: dict) -> tuple[int, dict[str, str]]:
    rows = doc.get("attractions")
    if not isinstance(rows, list):
        raise ValueError("Knowledge base has no attractions list")
    introductions = dict(doc.get("city_introductions") or {})
    moved_rows = 0
    for row in rows:
        if "city_intro" not in row:
            continue
        intro = row.get("city_intro")
        city = str(row.get("city") or "").strip()
        province = str(row.get("province") or "").strip()
        if not intro or not city or not province:
            raise ValueError(f"Cannot safely map city introduction for {row.get('id')}")
        key = f"{province}|{city}"
        existing = introductions.get(key)
        if existing is not None and existing != intro:
            raise ValueError(f"Conflicting city introductions for {key}")
        introductions[key] = intro
        moved_rows += 1
    doc["city_introductions"] = introductions
    for row in rows:
        row.pop("city_intro", None)
    return moved_rows, introductions


def main() -> None:
    parser = argparse.ArgumentParser(description="Deduplicate city introductions in the attraction KB.")
    parser.add_argument("--apply", action="store_true", help="write the migrated knowledge base")
    parser.add_argument("--path", type=Path, default=DATA_PATH)
    args = parser.parse_args()

    doc = json.loads(args.path.read_text(encoding="utf-8"))
    moved_rows, introductions = migrate(doc)
    remaining = sum("city_intro" in row for row in doc["attractions"])
    print(f"{'Applying' if args.apply else 'Preview'}: move {moved_rows} row values into {len(introductions)} city keys; remaining row values={remaining}.")
    if not args.apply:
        return
    args.path.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Written: {args.path}")


if __name__ == "__main__":
    main()
