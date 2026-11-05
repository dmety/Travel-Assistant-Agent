"""Apply missing fields from exact, unique matches on Beijing tourism pages."""
import json
from pathlib import Path

from .models import is_missing_value

ROOT=Path(__file__).resolve().parents[2]
DATA_PATH=ROOT/"data"/"china_attractions.json"
SOURCE_PATH=ROOT/"reports" / "evidence" / "beijing-official-detail-snapshot-2026-11-04.json"

def main():
    data=json.loads(DATA_PATH.read_text(encoding="utf-8"))
    source=json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
    by_id={r["id"]:r for r in data["attractions"]}
    tickets=hours=0
    for item in source["records"]:
        row=by_id[item["kb_id"]]
        if row.get("province")!="北京市" or row.get("name")!=item["kb_name"] or row.get("city") not in ("北京","北京市"):
            raise ValueError(f"KB identity changed for {item['kb_id']}")
        for field, value_field, source_field, checked_field, counter in (
            ("ticket","ticket_text","ticket_source","ticket_checked_at","ticket"),
            ("open_time","open_time_text","open_time_source","open_time_checked_at","hours"),
        ):
            value=item[value_field].strip()
            if not value:
                continue
            if not is_missing_value(row.get(field)):
                continue
            row[field]=value
            row[f"{field}_status"]="unverified"
            row[source_field]=item["source_url"]
            row[checked_field]=source["retrieved_at"]
            if counter=="ticket": tickets+=1
            else: hours+=1
    data["with_ticket"]=sum(not is_missing_value(r.get("ticket")) for r in data["attractions"])
    data["with_open_time"]=sum(not is_missing_value(r.get("open_time")) for r in data["attractions"])
    for field in ("ticket","open_time"):
        data[f"{field}_status_counts"]={status:sum(r.get(f"{field}_status")==status for r in data["attractions"]) for status in ("verified","unverified","pending")}
    DATA_PATH.write_text(json.dumps(data,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8")
    print(f"Imported {tickets} ticket fields and {hours} open-time fields; all remain unverified.")

if __name__=="__main__": main()
