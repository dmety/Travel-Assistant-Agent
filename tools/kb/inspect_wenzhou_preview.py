"""Persist and exact-match the ten-row public preview of Wenzhou's KB dataset."""
import tempfile
import json
import os
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
PREVIEW=Path(os.environ["TEMP"])/"wenzhou-preview-data.json"
DATA_PATH=ROOT/"data"/"china_attractions.json"
OUT_PATH=Path(tempfile.gettempdir()) / "travel-assistant-kb-work" / "wenzhou-public-preview-2026-11-04.json"

def main():
    raw=json.loads(PREVIEW.read_text(encoding="utf-8-sig"))
    columns=raw[0]
    records=[dict(zip(columns,row)) for row in raw[1:]]
    kb=json.loads(DATA_PATH.read_text(encoding="utf-8"))["attractions"]
    matches=[]
    for source in records:
        candidates=[r for r in kb if r.get("province")=="浙江省" and r.get("name")==source.get("景区名称")]
        if len(candidates)==1:
            matches.append({"source_name":source.get("景区名称"),"kb_id":candidates[0]["id"],"kb_name":candidates[0]["name"]})
    evidence={
        "dataset_title":"温州城市大脑文旅_景区信息",
        "dataset_page":"https://data.wenzhou.gov.cn/jdop_front/detail/data.do?iid=9307",
        "public_preview_endpoint":"https://data.wenzhou.gov.cn/jdop_front/data_opr/data_pre_list.do",
        "api_documentation":"https://data.wenzhou.gov.cn/jdop_front/detail/api.do?iid=9308",
        "dataset_updated_at":"2026-11-02",
        "retrieved_at":"2026-11-04",
        "declared_total_records":91,
        "public_preview_records":len(records),
        "preview_columns":columns,
        "exact_name_kb_matches":matches,
        "imported":False,
        "reason":"Public preview returns ten rows only; none exactly match a KB attraction. Full paginated data API requires registered application appsecret. Preview has average_price but no ticket_price field, so zero average price is not evidence of free admission.",
        "preview_records":records,
    }
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"preview_rows":len(records),"exact_name_matches":len(matches),"imported":False},ensure_ascii=False))

if __name__=="__main__": main()
