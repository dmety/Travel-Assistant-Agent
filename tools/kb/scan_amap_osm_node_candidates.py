"""Find reviewable exact-name Amap-to-OSM node candidates in local PBF; evidence only."""
import tempfile
import json
import math
import os
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from .models import gcj02_to_wgs84

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
OUTPUT_PATH=Path(tempfile.gettempdir()) / "travel-assistant-kb-work" / "amap-osm-node-candidates-2026-11-04.json"
PBF_PATH=Path(os.environ.get("TEMP","."))/"travel-assistant-china-latest.osm.pbf"
FEATURE_KEYS=("tourism","leisure","amenity","historic","natural","boundary","railway","name:en","addr:city","addr:province","addr:state")

def norm(s): return "".join(unicodedata.normalize("NFKC",str(s or "")).casefold().split())

def distance_m(a,b):
    p=math.pi/180; dlat=(b[0]-a[0])*p; dlon=(b[1]-a[1])*p
    h=math.sin(dlat/2)**2+math.cos(a[0]*p)*math.cos(b[0]*p)*math.sin(dlon/2)**2
    return 6371000*2*math.asin(math.sqrt(h))

def main():
    import osmium
    data=json.loads(DATA_PATH.read_text(encoding="utf-8"))
    targets=[]
    for row in data["attractions"]:
        ids=set(row.get("source_ids") or [])
        if row.get("id"): ids.add(row["id"])
        if (str(row.get("id","")).startswith("amap-") and "GCJ-02"==row.get("coordinateSystem")
                and not any(str(x).startswith("osm-") for x in ids)
                and not row.get("same_name_risk") and not row.get("merge_review")
                and row.get("name") and row.get("lat") is not None and row.get("lon") is not None):
            lat,lon=gcj02_to_wgs84(float(row["lat"]),float(row["lon"]))
            targets.append({"id":row["id"],"name":row["name"],"city":row.get("city"),"province":row.get("province"),"point":[lat,lon]})
    target_by_name={}
    for row in targets: target_by_name.setdefault(norm(row["name"]),[]).append(row)
    candidates=[]
    class Handler(osmium.SimpleHandler):
        def node(self,obj):
            if not obj.location.valid(): return
            name=obj.tags.get("name") or obj.tags.get("name:zh")
            key=norm(name)
            if not key or key not in target_by_name: return
            for row in target_by_name[key]:
                d=distance_m(row["point"],(obj.location.lat,obj.location.lon))
                if d<=100:
                    tags={k:obj.tags.get(k) for k in FEATURE_KEYS if obj.tags.get(k)}
                    candidates.append({"kb_id":row["id"],"kb_name":row["name"],"city":row["city"],"province":row["province"],"osm_type":"node","osm_id":obj.id,"distance_m":round(d,1),"osm_name":name,"tags":tags})
    if not PBF_PATH.is_file(): raise SystemExit(f"PBF not found: {PBF_PATH}")
    Handler().apply_file(str(PBF_PATH),locations=False)
    by_kb={}
    by_osm={}
    for c in candidates:
        by_kb.setdefault(c["kb_id"],[]).append(c)
        by_osm.setdefault(c["osm_id"],set()).add(c["kb_id"])
    one_to_one=[]
    for kb_id,items in by_kb.items():
        if len(items)==1 and len(by_osm[items[0]["osm_id"]])==1:
            c=items[0]
            if c["distance_m"]<=50 and any(k in c["tags"] for k in ("tourism","leisure","amenity","historic","natural")):
                one_to_one.append(c)
    output={"source":"Geofabrik China OSM PBF","source_url":"https://download.geofabrik.de/asia/china-latest.osm.pbf","retrieved_at":datetime.now(timezone.utc).isoformat(),"scope":"Amap-primary GCJ-02 rows only; exact normalized name, <=100m converted distance, no existing OSM ID/risk flags; evidence only","eligible_amap_rows":len(targets),"raw_candidates_within_100m":len(candidates),"eligible_rows_with_candidate":len(by_kb),"unique_one_to_one_at_50m_with_feature_tag":len(one_to_one),"candidates":sorted(candidates,key=lambda x:(x["kb_id"],x["distance_m"]))}
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:output[k] for k in ("eligible_amap_rows","raw_candidates_within_100m","eligible_rows_with_candidate","unique_one_to_one_at_50m_with_feature_tag")},ensure_ascii=False))
    print(OUTPUT_PATH)

if __name__=="__main__": main()
