"""Offline scan of OSM node names near mixed-coordinate KB rows; evidence only."""
import tempfile
import json
import math
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from .models import gcj02_to_wgs84

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
OUTPUT_PATH=Path(tempfile.gettempdir()) / "travel-assistant-kb-work" / "mixed-osm-name-candidates-2026-11-04.json"
PBF_PATH=Path(os.environ.get("TEMP","."))/"travel-assistant-china-latest.osm.pbf"
FEATURE_KEYS=("tourism","leisure","amenity","historic","natural","boundary","railway")

def distance_m(a,b):
    p=math.pi/180; dlat=(b[0]-a[0])*p; dlon=(b[1]-a[1])*p
    h=math.sin(dlat/2)**2+math.cos(a[0]*p)*math.cos(b[0]*p)*math.sin(dlon/2)**2
    return 6371000*2*math.asin(math.sqrt(h))

def main():
    import osmium
    data=json.loads(DATA_PATH.read_text(encoding="utf-8"))
    targets=[r for r in data["attractions"] if r.get("coordinateSystem")=="mixed"]
    by_name={}
    for row in targets: by_name.setdefault(row["name"],[]).append(row)
    candidates=[]
    class Handler(osmium.SimpleHandler):
        def node(self,obj):
            if not obj.location.valid(): return
            names={obj.tags.get("name"),obj.tags.get("name:zh")}
            for name in names:
                for row in by_name.get(name,[]):
                    point=gcj02_to_wgs84(float(row["lat"]),float(row["lon"]))
                    d=distance_m(point,(obj.location.lat,obj.location.lon))
                    if d<=10000:
                        candidates.append({"kb_id":row["id"],"kb_name":row["name"],"osm_type":"node","osm_id":obj.id,"distance_m":round(d),"osm_name":name,"tags":{k:obj.tags.get(k) for k in FEATURE_KEYS if obj.tags.get(k)}})
    if not PBF_PATH.is_file(): raise SystemExit(f"PBF not found: {PBF_PATH}")
    Handler().apply_file(str(PBF_PATH),locations=False)
    output={"source":"Geofabrik China OSM PBF","source_url":"https://download.geofabrik.de/asia/china-latest.osm.pbf","retrieved_at":datetime.now(timezone.utc).isoformat(),"scope":"exact-name OSM nodes within 10 km after GCJ-02 to WGS-84 conversion; evidence only","mixed_rows":len(targets),"candidate_count":len(candidates),"candidates":sorted(candidates,key=lambda x:(x["kb_id"],x["distance_m"]))}
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Scanned node names for {len(targets)} mixed rows; found {len(candidates)} nearby exact-name candidates.")
    print(OUTPUT_PATH)

if __name__=="__main__": main()
