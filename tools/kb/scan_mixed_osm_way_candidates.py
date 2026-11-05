"""Inspect exact-name OSM ways around mixed-coordinate rows; evidence only."""
import tempfile
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path

from .models import gcj02_to_wgs84

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
OUTPUT_PATH=Path(tempfile.gettempdir()) / "travel-assistant-kb-work" / "mixed-osm-way-candidates-2026-11-04.json"
PBF_PATH=Path(os.environ.get("TEMP","."))/"travel-assistant-china-latest.osm.pbf"
FEATURE_KEYS=("tourism","leisure","amenity","historic","natural","boundary","addr:city","addr:province","addr:state")

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
    ways={}
    class WayHandler(osmium.SimpleHandler):
        def way(self,obj):
            name=obj.tags.get("name") or obj.tags.get("name:zh")
            if name not in by_name: return
            ways[obj.id]={"name":name,"refs":[n.ref for n in obj.nodes],"tags":{k:obj.tags.get(k) for k in FEATURE_KEYS if obj.tags.get(k)}}
    WayHandler().apply_file(str(PBF_PATH),locations=False)
    required={ref for way in ways.values() for ref in way["refs"]}
    locations={}
    class NodeHandler(osmium.SimpleHandler):
        def node(self,obj):
            if obj.id in required and obj.location.valid(): locations[obj.id]=(obj.location.lat,obj.location.lon)
    NodeHandler().apply_file(str(PBF_PATH),locations=False)
    candidates=[]
    for way_id,way in ways.items():
        coords=[locations[n] for n in way["refs"] if n in locations]
        if not coords: continue
        for row in by_name[way["name"]]:
            point=gcj02_to_wgs84(float(row["lat"]),float(row["lon"]))
            nearest=min(distance_m(point,coord) for coord in coords)
            center=(sum(x[0] for x in coords)/len(coords),sum(x[1] for x in coords)/len(coords))
            candidates.append({"kb_id":row["id"],"kb_name":row["name"],"osm_type":"way","osm_id":way_id,"nearest_vertex_distance_m":round(nearest),"vertex_mean_distance_m":round(distance_m(point,center)),"node_count":len(coords),"osm_name":way["name"],"tags":way["tags"]})
    output={"source":"Geofabrik China OSM PBF","source_url":"https://download.geofabrik.de/asia/china-latest.osm.pbf","retrieved_at":datetime.now(timezone.utc).isoformat(),"scope":"exact-name ways; nearest referenced-node distance after GCJ-02 to WGS-84 conversion; evidence only","mixed_rows":len(targets),"exact_name_ways":len(ways),"candidate_count":len(candidates),"candidates":sorted(candidates,key=lambda x:(x["kb_id"],x["nearest_vertex_distance_m"]))}
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Scanned exact-name OSM ways for {len(targets)} mixed rows; found {len(candidates)} way candidates.")
    print(OUTPUT_PATH)

if __name__=="__main__": main()
