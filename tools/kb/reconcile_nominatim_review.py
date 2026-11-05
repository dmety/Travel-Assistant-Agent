"""Apply narrowly reviewed OSM lineage from the cached one-time Nominatim audit."""

import json
import math
import unicodedata
from pathlib import Path

from .models import gcj02_to_wgs84


ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
CACHE_PATH = ROOT / "reports" / "nominatim-review-2026-11-04.json"
REVERSE_CACHE_PATH = ROOT / "reports" / "nominatim-reverse-review-2026-11-04.json"

# Only exact-name results, within 1 km after converting the Amap point from
# GCJ-02 to WGS-84, with a unique plausible OSM feature are admitted.
REVIEWED_OSM_IDS = {
    "amap-B01C301198": ("osm-way-705753731", 34),
    "amap-B0FFKQ4KWA": ("osm-way-1454939507", 69),
    "amap-B013704B02": ("osm-way-158030760", 131),
    "amap-B0FFGJR069": ("osm-way-500514715", 2),
}

# Reverse-lookup matches are admitted separately because the OSM labels are
# historical/institutional variants of the Amap names. Their feature types,
# administrative areas and short converted-coordinate distances were checked.
REVERSE_REVIEWED_OSM_IDS = {
    "amap-B03670VA8J": ("osm-way-248183776", 38, "云南大学滇池学院"),
    "amap-B036702A40": ("osm-way-248184021", 18, "昆明医学院海源学院"),
}


def distance_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    p = math.pi / 180
    dlat, dlon = (b[0] - a[0]) * p, (b[1] - a[1]) * p
    h = math.sin(dlat / 2) ** 2 + math.cos(a[0] * p) * math.cos(b[0] * p) * math.sin(dlon / 2) ** 2
    return 6_371_000 * 2 * math.asin(math.sqrt(h))


def normalize_hint(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", str(value)).casefold().split())


def main() -> None:
    data = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    cache = json.loads(CACHE_PATH.read_text(encoding="utf-8-sig"))
    reverse_cache = json.loads(REVERSE_CACHE_PATH.read_text(encoding="utf-8-sig"))
    rows = {row["id"]: row for row in data["attractions"]}
    cached = {row["input_id"]: row for row in cache}
    reverse_cached = {row["input_id"]: row for row in reverse_cache}

    for record_id, (osm_id, expected_distance) in REVIEWED_OSM_IDS.items():
        row, audit = rows[record_id], cached[record_id]
        gcj_point = gcj02_to_wgs84(float(row["lat"]), float(row["lon"]))
        city_hint = normalize_hint(row["city"]).removesuffix("市")
        province_hint = normalize_hint(row["province"])
        candidates = [
            result for result in audit["response"]
            if (
                result.get("name") == row["name"]
                or result.get("name", "").startswith(row["name"] + "(")
            )
            and city_hint in normalize_hint(
                result.get("display_name", "") + " " + " ".join(
                    str(value) for value in (result.get("address") or {}).values()
                )
            )
            and province_hint in normalize_hint(
                result.get("display_name", "") + " " + " ".join(
                    str(value) for value in (result.get("address") or {}).values()
                )
            )
            and result.get("osm_type")
            and result.get("osm_id")
            and distance_m(gcj_point, (float(result["lat"]), float(result["lon"]))) < 1000
        ]
        assert len(candidates) == 1, (
            record_id,
            row["name"],
            [(result.get("name"), distance_m(gcj_point, (float(result["lat"]), float(result["lon"]))))
             for result in audit["response"]],
        )
        candidate = candidates[0]
        actual_id = f"osm-{candidate['osm_type']}-{candidate['osm_id']}"
        actual_distance = round(distance_m(gcj_point, (float(candidate["lat"]), float(candidate["lon"]))))
        assert actual_id == osm_id and abs(actual_distance - expected_distance) <= 1
        assert row["id"].startswith("amap-")
        if osm_id not in row.setdefault("source_ids", []):
            row["source_ids"].append(osm_id)
        if "OpenStreetMap" not in row.setdefault("sources", []):
            row["sources"].append("OpenStreetMap")
        row["coordinateSystem"] = "GCJ-02"

    for record_id, (osm_id, expected_distance, expected_name) in REVERSE_REVIEWED_OSM_IDS.items():
        row, audit = rows[record_id], reverse_cached[record_id]
        result = audit["response"]
        gcj_point = gcj02_to_wgs84(float(row["lat"]), float(row["lon"]))
        actual_distance = round(distance_m(gcj_point, (float(result["lat"]), float(result["lon"]))))
        location_text = normalize_hint(
            result.get("display_name", "") + " " + " ".join(
                str(value) for value in (result.get("address") or {}).values()
            )
        )
        assert result.get("name") == expected_name
        assert result.get("category") == "amenity" and result.get("type") == "university"
        assert "昆明市" in result.get("display_name", "")
        assert "云南省" in location_text
        assert actual_distance == expected_distance
        assert f"osm-{result['osm_type']}-{result['osm_id']}" == osm_id
        assert row["name"] in {"滇池学院", "昆明医科大学海源学院"}
        assert row["city"] == "昆明市" and row["province"] == "云南省"
        if osm_id not in row.setdefault("source_ids", []):
            row["source_ids"].append(osm_id)
        if "OpenStreetMap" not in row.setdefault("sources", []):
            row["sources"].append("OpenStreetMap")
        row["coordinateSystem"] = "GCJ-02"

    DATA_PATH.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(
        f"Updated {len(REVIEWED_OSM_IDS) + len(REVERSE_REVIEWED_OSM_IDS)} records; "
        "other mixed-coordinate records remain unchanged."
    )


if __name__ == "__main__":
    main()
