"""Link only one-to-one, exact-name Amap/OSM objects that pass strict review."""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
REVIEW_PATH=ROOT/"reports"/"amap-osm-node-candidate-review-2026-11-04.json"
APPLIED_PATH=ROOT/"reports"/"amap-osm-approved-node-links-2026-11-04.json"
PBF_SOURCE="https://download.geofabrik.de/asia/china-latest.osm.pbf"
MAX_DISTANCE_M=25
FEATURES={
    "tourism":{"attraction","museum","viewpoint","aquarium","zoo","theme_park","gallery","artwork","archaeological_site"},
    "leisure":{"park","garden","nature_reserve","marina"},
    "historic":{"monument","memorial","building","city_gate","archaeological_site"},
    "natural":{"peak","valley","saddle","cave_entrance","spring","beach"},
    "amenity":{"place_of_worship","school","library","college","university","theatre","animal_breeding","planetarium","ferry_terminal","museum"},
}

def has_approved_feature(tags):
    return any(tags.get(key) in values for key,values in FEATURES.items())

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--apply",action="store_true",help="write approved source IDs; default is dry-run")
    parser.add_argument("--review",type=Path,default=REVIEW_PATH,help="reviewed candidate JSON")
    parser.add_argument("--output",type=Path,default=APPLIED_PATH,help="applied-link evidence JSON")
    args=parser.parse_args()
    data=json.loads(DATA_PATH.read_text(encoding="utf-8"))
    report=json.loads(args.review.read_text(encoding="utf-8"))
    by_id={r["id"]:r for r in data["attractions"]}
    owners={}
    for row in data["attractions"]:
        ids=set(row.get("source_ids") or [])
        if row.get("id"): ids.add(row["id"])
        for source_id in ids: owners.setdefault(str(source_id),set()).add(row["id"])
    accepted=[]
    for item in report["candidates"]:
        if (item["distance_m"]>MAX_DISTANCE_M or item["already_claimed_by"]
                or item["admin_tag_conflicts"] or not has_approved_feature(item["tags"])):
            continue
        row=by_id[item["kb_id"]]
        osm_id=item["osm_source_id"]
        if (not row["id"].startswith("amap-") or row.get("name")!=item["kb_name"]
                or row.get("city")!=item["city"] or row.get("province")!=item["province"]
                or row.get("coordinateSystem")!="GCJ-02" or row.get("same_name_risk")
                or row.get("merge_review") or not item["osm_name"]):
            raise ValueError(f"Candidate identity/safety check changed: {item['kb_id']}")
        # Candidate list contains only normalized exact-name matches from the scan.
        if osm_id in owners and owners[osm_id]!={row["id"]}:
            raise ValueError(f"OSM ID became claimed by another record: {osm_id}")
        accepted.append(item)
    if not args.apply:
        print(f"DRY RUN: {len(accepted)} source IDs pass; no KB edits.")
        for item in accepted:
            print(f"{item['kb_id']} | {item['kb_name']} | {item['osm_source_id']} | {item['distance_m']} m | {item['tags']}")
        return
    added=0
    for item in accepted:
        row=by_id[item["kb_id"]]
        osm_id=item["osm_source_id"]
        ids=row.setdefault("source_ids",[])
        if osm_id not in ids:
            ids.append(osm_id); added+=1
        sources=row.setdefault("sources",[])
        if "OpenStreetMap" not in sources: sources.append("OpenStreetMap")
        evidence={
            "method":f"exact normalized name + unique OSM {item.get('osm_type', 'node')} within 25 m after GCJ-02 to WGS-84 conversion",
            "distance_m":item["distance_m"],
            "osm_object_url":f"https://www.openstreetmap.org/{item.get('osm_type', 'node')}/{item['osm_id']}",
            "pbf_source":PBF_SOURCE,
            "pbf_retrieved_at":report["retrieved_at"],
            "tags":item["tags"],
        }
        evidence_list=row.setdefault("source_id_match_evidence",[])
        if not any(e.get("osm_object_url")==evidence["osm_object_url"] for e in evidence_list):
            evidence_list.append(evidence)
    applied_report={"source":"Geofabrik China OSM PBF","source_url":PBF_SOURCE,"retrieved_at":report["retrieved_at"],"criteria":{"exact_normalized_name":True,"max_distance_m":MAX_DISTANCE_M,"unique_candidate_within_100m":True,"unique_source_id_across_kb":True,"no_existing_risk_flags":True,"feature_tag_allowlist":{k:sorted(v) for k,v in FEATURES.items()},"no_conflicting_admin_tags":True},"linked_count":len(accepted),"records":[{"kb_id":item["kb_id"],"kb_name":item["kb_name"],"osm_source_id":item["osm_source_id"],"distance_m":item["distance_m"],"tags":item["tags"]} for item in accepted]}
    DATA_PATH.write_text(json.dumps(data,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(applied_report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Applied {added} unique OSM object source IDs; Amap IDs and GCJ-02 coordinates remain primary.")

if __name__=="__main__": main()
