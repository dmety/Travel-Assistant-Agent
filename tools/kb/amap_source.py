# -*- coding: utf-8 -*-
"""高德(Amap) Web服务 POI 接入层 —— 补强中国景点的权威字段。

许可说明：高德 Web服务 API 为商业服务（个人开发者有每日免费额度，
约 5000 次/天），需申请 Key。返回字段包含等级(level)、评分(rating)、
开放时间、票价、电话、图片等 OSM 缺失的国内权威信息。
"""
import json
import os
import time
import urllib.parse
import urllib.request

AMAP_ENDPOINT = "https://restapi.amap.com/v3/place/text"

# 旅游相关 POI 大类代码：110000 风景名胜 / 140000 科教文化服务(含博物馆)
AMAP_TOURISM_TYPES = "110000|140000"


def load_api_key() -> str:
    """优先读环境变量，其次读项目根目录 .env。"""
    key = os.environ.get("AMAP_API_KEY")
    if key:
        return key.strip()
    candidates = [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env"),
        ".env",
    ]
    for cand in candidates:
        p = os.path.abspath(cand)
        if os.path.exists(p):
            try:
                with open(p, encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line.startswith("AMAP_API_KEY"):
                            _, _, v = line.partition("=")
                            return v.strip().strip('"').strip("'")
            except Exception:
                pass
    return ""


class AmapClient:
    def __init__(self, key: str = None, qps_sleep: float = 0.25):
        self.key = (key or load_api_key()).strip()
        self.qps_sleep = qps_sleep
        if not self.key:
            raise RuntimeError(
                "未找到 AMAP_API_KEY：请在项目根目录 .env 或环境变量中配置高德 Key"
            )

    def text_search(self, keywords: str = "", city: str = "", types: str = "",
                    page: int = 1, pagesize: int = 25) -> dict:
        params = {
            "key": self.key,
            "keywords": keywords,
            "city": city,
            "types": types,
            "citylimit": "true",
            "extensions": "all",     # 必须，返回 biz_ext(等级/评分/开放时间/票价)
            "page": str(page),
            "offset": str(pagesize),
        }
        url = AMAP_ENDPOINT + "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"User-Agent": "TravelKb/0.1"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode())
        if data.get("status") != "1":
            raise RuntimeError(
                f"高德API错误: {data.get('info')} ({data.get('infocode')})"
            )
        return data

    def fetch_city(self, city: str, max_pages: int = 20, pagesize: int = 25) -> list:
        """拉取某城市旅游类 POI（风景名胜 + 科教文化），分页合并去重。"""
        results = []
        seen_ids = set()
        for page in range(1, max_pages + 1):
            try:
                data = self.text_search(
                    keywords="", city=city, types=AMAP_TOURISM_TYPES, page=page
                )
            except Exception as e:
                print(f"    [Amap][{city}] 第{page}页失败: {e}", flush=True)
                break
            pois = data.get("pois", [])
            if not pois:
                break
            for p in pois:
                pid = p.get("id")
                if pid in seen_ids:
                    continue
                seen_ids.add(pid)
                results.append(p)
            count = int(data.get("count", "0") or 0)
            if len(pois) < pagesize or (page * pagesize) >= count:
                break
            time.sleep(self.qps_sleep)
        return results


def from_amap(p: dict) -> "object":
    """把高德 POI 字典转成 Attraction。"""
    from .models import Attraction
    loc = p.get("location", "") or ""
    lon_s, _, lat_s = loc.partition(",")
    try:
        lon = float(lon_s)
        lat = float(lat_s)
    except ValueError:
        lon, lat = 0.0, 0.0
    biz = p.get("biz_ext", {}) or {}
    photos = p.get("photos", []) or []
    photo_urls = [ph.get("url") for ph in photos if ph.get("url")]
    cost = biz.get("cost")
    if isinstance(cost, list):
        cost = ""
    ticket = cost if cost else ""
    t = p.get("type", "") or ""
    # keytag：高德把 A级(5A景区/4A景区)等权威标签放在 keytag，biz_ext.level 常为空
    kt = p.get("keytag")
    if isinstance(kt, str):
        tags = [kt] if kt else []
    elif isinstance(kt, list):
        tags = [x for x in kt if x]
    else:
        tags = []
    level = biz.get("level") or ""
    if not level:
        for tg in tags:
            if "景区" in tg and any(ch.isdigit() for ch in tg):
                level = tg
                break
    return Attraction(
        id="amap-" + (p.get("id", "") or ""),
        name=p.get("name", ""),
        type=t.split(";")[0] if t else "",
        city=p.get("cityname", "") or p.get("adname", ""),
        province=p.get("pname", ""),
        lat=lat,
        lon=lon,
        address=p.get("address", "") or "",
        level=level,
        tags=tags,
        rating=biz.get("rating", "") or "",
        open_time=biz.get("opentime2", "") or biz.get("open_time", "") or "",
        ticket=ticket,
        tel=p.get("tel", "") or "",
        image=photo_urls[0] if photo_urls else "",
        photos=photo_urls,
        source="Amap",
        sources=["Amap"],
    )
