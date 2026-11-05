"""Apply additional uniqueness, lineage, same-name and admin-tag screens."""
import tempfile
import argparse
import json
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
CANDIDATE_PATH=Path(tempfile.gettempdir())/"travel-assistant-kb-work"/"amap-osm-node-candidates-2026-11-04.json"
OUTPUT_PATH=Path(tempfile.gettempdir())/"travel-assistant-kb-work"/"amap-osm-candidate-review.json"

def norm(s): return "".join(unicodedata.normalize("NFKC",str(s or "")).casefold().split())
def norm_admin(s): return norm(s).replace("自治区","").replace("特别行政区","").replace("自治州","").replace("省","").replace("市","").replace("地区","")

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--candidates",type=Path,default=CANDIDATE_PATH)
    parser.add_argument("--output",type=Path,default=OUTPUT_PATH)
    args=parser.parse_args()
    data=json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source=json.loads(args.candidates.read_text(encoding="utf-8"))
    by_id={r["id"]:r for r in data["attractions"]}
    name_owners=defaultdict(set)
    for row in data["attractions"]:
        name_owners[norm(row.get("name"))].add(row["id"])
    owners=defaultdict(set)
    for row in data["attractions"]:
        ids=set(row.get("source_ids") or [])
        if row.get("id"): ids.add(row["id"])
        for x in ids: owners[str(x)].add(row["id"])
    by_kb=defaultdict(list)
    for item in source["candidates"]: by_kb[item["kb_id"]].append(item)
    reviewed=[]
    for kb_id,items in by_kb.items():
        row=by_id[kb_id]
        if len(items)!=1 or items[0]["distance_m"]>50: continue
        item=items[0]
        if not any(k in item["tags"] for k in ("tourism","leisure","amenity","historic","natural")): continue
        if len(name_owners[norm(row.get("name"))]) != 1: continue
        if row.get("same_name_risk") or row.get("merge_review"): continue
        osm_type=item.get("osm_type","node")
        osm_id=f"osm-{osm_type}-{item['osm_id']}"
        claimed_by=sorted(owners.get(osm_id,set()))
        tag_conflicts=[]
        for key in ("addr:province","addr:state"):
            val=item["tags"].get(key)
            if val and norm_admin(val) not in {norm_admin(row.get("province")),norm_admin(row.get("city"))}:
                tag_conflicts.append({"key":key,"value":val,"expected":[row.get("province"),row.get("city")]})
        city_value=item["tags"].get("addr:city")
        if city_value:
            compact=norm_admin(city_value)
            expected={norm_admin(row.get("city")),norm_admin(row.get("province"))}
            city_like=any(str(city_value).endswith(x) for x in ("市","自治州","地区","县"))
            if city_like and compact not in expected:
                tag_conflicts.append({"key":"addr:city","value":city_value,"expected":[row.get("city"),row.get("province")]})
        reasons=[]
        if claimed_by: reasons.append("OSM ID already claimed by KB record(s): "+", ".join(claimed_by))
        if tag_conflicts: reasons.append("conflicting OSM administrative tags")
        reviewed.append({**item,"osm_source_id":osm_id,"already_claimed_by":claimed_by,"admin_tag_conflicts":tag_conflicts,"review_status":"reject" if reasons else "candidate_review_required","reasons":reasons})
    output={"source":"candidate evidence only; no KB edits","retrieved_at":source["retrieved_at"],"candidate_file":str(args.candidates),"unique_within_100m_and_under_50m_with_feature_tag_and_unique_kb_name":len(reviewed),"already_claimed":sum(bool(x["already_claimed_by"]) for x in reviewed),"admin_tag_conflicts":sum(bool(x["admin_tag_conflicts"]) for x in reviewed),"remaining_manual_review_candidates":sum(x["review_status"]=="candidate_review_required" for x in reviewed),"candidates":sorted(reviewed,key=lambda x:(x["review_status"],x["distance_m"],x["kb_id"]))}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:output[k] for k in ("unique_within_100m_and_under_50m_with_feature_tag_and_unique_kb_name","already_claimed","admin_tag_conflicts","remaining_manual_review_candidates")},ensure_ascii=False))
    print(args.output)

if __name__=="__main__": main()
