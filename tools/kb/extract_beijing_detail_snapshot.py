"""Extract displayed ticket/opening text for exact matched Beijing KB rows."""
from html.parser import HTMLParser
import json
import os
import re
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMP = Path(os.environ["TEMP"]) / "beijing-attractions"
MATCH_PATH = ROOT / "reports" / "beijing-official-list-match-2026-11-04.json"
OUT_PATH = ROOT / "reports" / "beijing-official-detail-snapshot-2026-11-04.json"
LIST_URL = "https://whlyj.beijing.gov.cn/ggfw/ly/202511/t20251119_4287815.html"


class DetailParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.current=None; self.capture=False; self.skip_shadow=False; self.values={}
    def handle_starttag(self, tag, attrs):
        attrs=dict(attrs); classes=attrs.get("class", "").split()
        if tag == "li" and "price" in classes:
            self.current="ticket"; self.values.setdefault(self.current,[]); self.capture=True
        elif tag == "li" and "open" in classes:
            self.current="open_time"; self.values.setdefault(self.current,[]); self.capture=True
        elif self.capture and tag in ("i", "span") and "price-shadow" in classes:
            self.skip_shadow=True
        elif self.capture and tag == "br" and not self.skip_shadow:
            self.values[self.current].append("\n")
    def handle_data(self, data):
        if self.capture and not self.skip_shadow and self.current:
            self.values[self.current].append(data)
    def handle_endtag(self, tag):
        if tag in ("i", "span") and self.skip_shadow: self.skip_shadow=False
        if tag == "li" and self.capture:
            self.capture=False; self.current=None


def clean(parts):
    s="".join(parts)
    s=re.sub(r"[ \t\u3000]+", " ", s)
    s=re.sub(r" *\n *", "\n", s)
    return s.strip(" \n\r\t")


def main():
    matches=json.loads(MATCH_PATH.read_text(encoding="utf-8"))
    records=[]
    for row in matches:
        if not (row["ticket_missing"] or row["open_time_missing"]): continue
        path=TEMP/f"{row['number']}.html"
        if not path.exists(): continue
        parser=DetailParser(); parser.feed(path.read_text(encoding="utf-8-sig",errors="replace"))
        item={**row,"ticket_text":clean(parser.values.get("ticket",[])),"open_time_text":clean(parser.values.get("open_time",[]))}
        records.append(item)
    snapshot={"source_title":"北京旅游网景点链接列表及对应景点详情","list_url":LIST_URL,"retrieved_at":str(date.today()),"records":records}
    OUT_PATH.write_text(json.dumps(snapshot,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"records":len(records),"ticket_texts":sum(bool(x["ticket_text"]) for x in records),"open_time_texts":sum(bool(x["open_time_text"]) for x in records)},ensure_ascii=False))
    for row in records:
        print(json.dumps({"number":row["number"],"kb_id":row["kb_id"],"ticket":row["ticket_text"][:220],"hours":row["open_time_text"][:180]},ensure_ascii=False))

if __name__=="__main__": main()
