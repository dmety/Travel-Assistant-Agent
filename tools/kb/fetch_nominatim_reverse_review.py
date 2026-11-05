"""Fetch a one-time, cached reverse-geocoding audit for the 11 unresolved rows."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .models import gcj02_to_wgs84


ROOT = Path(__file__).resolve().parents[2]
DATA_PATH = ROOT / "data" / "china_attractions.json"
CACHE_PATH = ROOT / "reports" / "evidence" / "nominatim-reverse-review-2026-11-04.json"
REVIEW_IDS = {
    "amap-B0FFGXTU5E", "amap-B03670VA8J", "amap-B036702A40",
    "amap-B0378008FA", "amap-B038300GWH", "amap-B037B0OCLA",
    "amap-B01C317HS4", "amap-B0FFGA0LN4", "amap-B0137024B7",
    "amap-B0IDORK7B2", "amap-B0K2A7ZHHK",
}


def main() -> None:
    if CACHE_PATH.exists():
        cached = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
        print(f"Using existing reverse-query cache ({len(cached)} rows); no requests sent.")
        return

    document = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    rows = [row for row in document["attractions"] if row.get("id") in REVIEW_IDS]
    if len(rows) != len(REVIEW_IDS) or any(row.get("coordinateSystem") != "mixed" for row in rows):
        raise RuntimeError("The reviewed IDs or coordinate state changed; refusing to query.")

    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    output = []
    for index, row in enumerate(rows):
        lat, lon = gcj02_to_wgs84(float(row["lat"]), float(row["lon"]))
        params = urllib.parse.urlencode({
            "format": "jsonv2", "lat": f"{lat:.8f}", "lon": f"{lon:.8f}",
            "zoom": 18, "addressdetails": 1, "namedetails": 1,
        })
        url = f"https://nominatim.openstreetmap.org/reverse?{params}"
        item = {
            "input_id": row["id"], "input_name": row["name"],
            "input_city": row.get("city"), "input_province": row.get("province"),
            "input_lat": row["lat"], "input_lon": row["lon"],
            "query_lat_wgs84": lat, "query_lon_wgs84": lon,
        }
        request = urllib.request.Request(
            url, headers={"User-Agent": "TravelAssistantKbAudit/0.1"}
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                item["response"] = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            item["error"] = str(exc)
            item["response"] = None
        output.append(item)
        CACHE_PATH.write_text(
            json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"cached {index + 1}/{len(rows)}: {row['id']}")
        if index + 1 < len(rows):
            time.sleep(1.1)

    print(f"Saved {len(output)} results to {CACHE_PATH}")


if __name__ == "__main__":
    main()
