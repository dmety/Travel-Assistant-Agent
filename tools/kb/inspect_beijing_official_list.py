"""Inspect Beijing official tourism list against KB; does not modify records."""
from html.parser import HTMLParser
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class Parser(HTMLParser):
    def __init__(self):
        super().__init__(); self.rows=[]; self.row=None; self.cell=None; self.anchor=None
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs)
        if tag == "tr": self.row=[]
        elif tag in ("td","th") and self.row is not None: self.cell={"text":[],"href":None}
        elif tag == "a" and self.cell is not None: self.anchor=[]; self.cell["href"]=attrs.get("href")
    def handle_data(self, data):
        if self.cell is not None: self.cell["text"].append(data)
    def handle_endtag(self, tag):
        if tag in ("td","th") and self.cell is not None:
            self.cell["value"]=re.sub(r"\s+"," ","".join(self.cell["text"])).strip(); self.row.append(self.cell); self.cell=None
        elif tag == "tr" and self.row is not None:
            if self.row: self.rows.append(self.row)
            self.row=None

def canon(s):
    s=re.sub(r"\s+", "", s).strip()
    s=re.sub(r"^(北京市|北京)", "", s)
    s=re.sub(r"(文化旅游景区|旅游景区|风景名胜区|风景区|旅游区|景区)$", "", s)
    return s

def main():
    p=Parser(); p.feed((Path(os.environ["TEMP"])/"beijing-list.html").read_text(encoding="utf-8-sig",errors="replace"))
    kb=json.loads((ROOT/"data/china_attractions.json").read_text(encoding="utf-8"))["attractions"]
    kb=[r for r in kb if r.get("province")=="北京市"]
    results=[]; seen=set()
    for cells in p.rows:
        vals=[c.get("value","") for c in cells]
        if not vals or not vals[0].isdigit() or len(vals)<4: continue
        name=vals[1]; href=next((c.get("href") for c in cells if c.get("href") and "attraction/" in c.get("href")),None)
        if not href or vals[0] in seen: continue
        seen.add(vals[0]); key=canon(name)
        matches=[r for r in kb if canon(r.get("name",""))==key]
        if len(matches)==1:
            r=matches[0]
            results.append({"number":vals[0],"source_name":name,"district":vals[3],"level":vals[2],"source_url":href,"kb_id":r["id"],"kb_name":r["name"],"ticket_missing":not r.get("ticket"),"open_time_missing":not r.get("open_time"),"same_name_risk":r.get("same_name_risk")})
    out=ROOT/"reports" / "archive" / "beijing-official-list-match-2026-11-04.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(results,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"unique_exact_or_suffix_matches":len(results),"ticket_missing":sum(x["ticket_missing"] for x in results),"open_time_missing":sum(x["open_time_missing"] for x in results),"risk_flagged":sum(bool(x["same_name_risk"]) for x in results)},ensure_ascii=False))

if __name__=="__main__": main()
