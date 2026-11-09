#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外部美食推荐 Provider：配置式 HTTP Agent 的可运行参考实现。

职责边界
========
- 只实现《Agent 接入规范》的 ``POST /v1/invoke`` 与 ``GET /healthz``，无状态、不写库。
- 候选来自**实时**高德 /v3/place/around 与百度 /place/v2/search；密钥缺失、超时、
  配额耗尽或接口报错时转 DEGRADED、零候选并如实标注，**不编造 POI**。
- 忌口 / 过敏是硬约束：命中整条剔除；不回显枚举原文，不进日志、不进缓存。
- 外部 facts 一律 ``unverified``；评分、人均、营业时段未知写 null，不写「免费」。
- POI 原始字段不落盘、不进知识库、不进日志正文（地图服务条款的再分发限制）。

用法
====
    python -B tools/agent/food_provider_server.py --self-test   # 离线自测，不联网
    python -B tools/agent/food_provider_server.py --serve --port 8765
    PY_AGENT_TOKEN=... AMAP_KEY=... BAIDU_AK=... python ... --serve

部署说明
========
本仓库的 G0 只允许 https 且 host 在 endpointAllowlist 内，显式拒绝环回 / IP / 端口。
因此本机 ``127.0.0.1`` 服务**不能**直接写进运行态配置参与 G1～G4 路由；要进注册表，
需部署到公网 https 域名并把域名加入 tools/agent/provider-contract.json 的
runtime.endpointAllowlist。本地验证走 --self-test 与 Java 侧 HttpAgentAdapter 桩测试，
两者都不假装在注册表里已 ACTIVE。
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from kb.models import bd09_to_gcj02, gcj02_to_bd09, wgs84_to_gcj02  # noqa: E402

PROVIDER_ID = "py.food.recommender"
SCHEMA_VERSION = "1.0"
MAX_FACTS = 50
MAX_NOTICES = 20
MAX_UNKNOWN = 20
ADVICE = "已整理附近餐饮候选，评分与人均价格以候选字段为准；营业时段与忌口信息请到店核实"
AMAP_BASE = "https://restapi.amap.com/v3/place/around"
BAIDU_BASE = "https://api.map.baidu.com/place/v2/search"

ALLERGEN_HINTS = (
    ("海鲜", "SEAFOOD"), ("海产", "SEAFOOD"), ("虾", "SEAFOOD"), ("蟹", "SEAFOOD"),
    ("花生", "PEANUT"), ("猪肉", "PORK"), ("卤肉", "PORK"),
    ("川菜", "SPICY"), ("湘菜", "SPICY"), ("辣", "SPICY"),
    ("面", "GLUTEN"), ("麸", "GLUTEN"), ("奶", "DAIRY"), ("乳", "DAIRY"),
    ("酒", "ALCOHOL"),
)


# ---------------------------------------------------------------- 工具


def now_iso():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def as_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def as_float(value):
    if value is None or value == "" or value == "[]":
        return None
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def normalize_name(value):
    return "".join(str(value or "").split()).lower()


def clip(value, limit):
    if value is None:
        return None
    text = str(value)
    return text if len(text) <= limit else text[:limit]


def haversine(lat1, lon1, lat2, lon2):
    radius = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(min(1.0, h)))


def allergen_flags(text):
    flags = []
    for keyword, label in ALLERGEN_HINTS:
        if keyword in text and label not in flags:
            flags.append(label)
    return flags


# ---------------------------------------------------------------- 数据源


def fetch_amap(lat, lon, radius, limit):
    key = os.environ.get("AMAP_KEY", "").strip()
    if not key:
        raise RuntimeError("AMAP_KEY 未配置")
    query = urllib.parse.urlencode({
        "key": key, "location": f"{lon:.6f},{lat:.6f}", "types": "050000",
        "radius": radius, "sortrule": "distance", "extensions": "all",
        "offset": min(max(limit, 1), 25), "page": 1,
    })
    with urllib.request.urlopen(AMAP_BASE + "?" + query, timeout=4) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("status") != "1":
        raise RuntimeError("高德返回状态 " + str(payload.get("infocode")))
    places = []
    for poi in payload.get("pois") or []:
        location = (poi.get("location") or "").split(",")
        if len(location) != 2:
            continue
        biz = poi.get("biz_ext") or {}
        hours = poi.get("opentime2") or biz.get("opentime2")
        places.append({
            "poiId": poi.get("id"), "name": poi.get("name"),
            "lat": as_float(location[1]), "lon": as_float(location[0]),
            "tags": ";".join(x for x in [poi.get("type"), poi.get("tag")] if isinstance(x, str)),
            "rating": as_float(biz.get("rating")),
            "pricePerPersonCny": as_float(biz.get("cost")),
            "openHours": hours if isinstance(hours, str) and hours.strip() else None,
            "source": "AMAP", "fetchedAt": now_iso(),
        })
    return places


def fetch_baidu(lat, lon, radius, limit):
    ak = os.environ.get("BAIDU_AK", "").strip()
    if not ak:
        raise RuntimeError("BAIDU_AK 未配置")
    bd_lat, bd_lon = gcj02_to_bd09(lat, lon)
    query = urllib.parse.urlencode({
        "query": "美食", "location": f"{bd_lat:.6f},{bd_lon:.6f}",
        "radius": radius, "scope": 2, "output": "json",
        "page_size": min(max(limit, 1), 20), "page_num": 0, "ak": ak,
    })
    with urllib.request.urlopen(BAIDU_BASE + "?" + query, timeout=4) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("status") != 0:
        raise RuntimeError("百度返回状态 " + str(payload.get("status")))
    places = []
    for poi in payload.get("results") or []:
        position = poi.get("location") or {}
        bd_y, bd_x = as_float(position.get("lat")), as_float(position.get("lng"))
        gcj_lat = gcj_lon = None
        if bd_y is not None and bd_x is not None:
            gcj_lat, gcj_lon = bd09_to_gcj02(bd_y, bd_x)
        detail = poi.get("detail_info") or {}
        places.append({
            "poiId": poi.get("uid"), "name": poi.get("name"),
            "lat": gcj_lat, "lon": gcj_lon,
            "tags": detail.get("tag") if isinstance(detail.get("tag"), str) else "",
            "rating": as_float(detail.get("overall_rating")),
            "pricePerPersonCny": as_float(detail.get("price")),
            "openHours": detail.get("shop_hours") if isinstance(
                detail.get("shop_hours"), str) else None,
            "source": "EXTERNAL", "fetchedAt": now_iso(),
        })
    return places


# ---------------------------------------------------------------- 业务装配


def anchors_from_facts(facts):
    centers, skipped = [], 0
    for fact in facts or []:
        if fact.get("type") != "ATTRACTION":
            continue
        value = fact.get("value") or {}
        lat, lon = as_number(value.get("lat")), as_number(value.get("lon"))
        crs = str(value.get("coordinateSystem") or "").upper()
        if lat is None or lon is None:
            skipped += 1
        elif crs in ("GCJ-02", "GCJ02"):
            centers.append((lat, lon))
        elif crs in ("WGS-84", "WGS84"):
            centers.append(wgs84_to_gcj02(lat, lon))
        elif crs in ("BD-09", "BD-09LL", "BD09", "BD09LL"):
            centers.append(bd09_to_gcj02(lat, lon))
        else:
            skipped += 1
    return centers, skipped


def dedupe(places):
    seen, result = set(), []
    for place in places:
        name = normalize_name(place.get("name"))
        if not name:
            continue
        key = (place.get("source"), place.get("poiId") or name)
        if key in seen:
            continue
        seen.add(key)
        duplicate = False
        for kept in result:
            if normalize_name(kept.get("name")) != name:
                continue
            if all(place.get(k) is not None and kept.get(k) is not None
                   for k in ("lat", "lon")) and haversine(
                       place["lat"], place["lon"], kept["lat"], kept["lon"]) < 50:
                duplicate = True
                break
        if not duplicate:
            result.append(place)
    return result


def food_fact(place, index, distance, radius, budget, meal_slot,
              show_reviews, show_reasons):
    constraints = []
    if budget is not None and place.get("pricePerPersonCny") is not None \
            and place["pricePerPersonCny"] > budget:
        constraints.append("超出预算")
    if distance > radius:
        constraints.append("超出半径")
    tags = [t.strip() for t in str(place.get("tags") or "").replace("，", ";")
            .replace("；", ";").replace(",", ";").split(";") if t.strip()][:8]
    flags = allergen_flags(str(place.get("name") or "") + ";" + str(place.get("tags") or ""))
    value = {
        "category": "FOOD", "kind": "RESTAURANT", "name": place.get("name"),
        "mealSlot": meal_slot, "pricePerPersonCny": place.get("pricePerPersonCny"),
        "openHours": place.get("openHours"), "distanceMeters": distance,
        "poiId": place.get("poiId"), "tags": tags, "allergenFlags": flags,
        "sourceLayer": "FACT", "constraintNotes": constraints,
        "reasonTags": ["位于所选半径内"] if show_reasons and distance <= radius else [],
    }
    if show_reviews:
        value["rating"] = place.get("rating")
        value["ratingSource"] = place.get("source") if place.get("rating") is not None else None
        value["ratingFetchedAt"] = place.get("fetchedAt") if place.get("rating") is not None else None
        value["reviewLinks"] = []
    return {
        "factId": clip("py.food:" + str(index).zfill(2) + ":"
                       + str(place.get("poiId") or normalize_name(place.get("name"))), 64),
        "type": "CONTENT", "value": value,
        "evidence": {
            "sourceKind": place.get("source") if place.get("source") in ("AMAP", "EXTERNAL")
            else "EXTERNAL",
            "sourceId": clip(place.get("poiId"), 128),
            "verificationStatus": "unverified",
            "fetchedAt": place.get("fetchedAt") or now_iso(),
            "excerpt": "地图 POI 快照，未核验",
        },
    }


def handle_invoke(request, fetchers=None):
    """纯函数：请求 dict -> 契约形状响应 dict。fetchers 只在自测里注入。"""
    started = time.time()
    fetchers = fetchers or (fetch_amap, fetch_baidu)
    context = request.get("context") or {}
    facts_in = request.get("facts") or []
    notices, unknown = [], []

    def done(status, facts, advice, extra_notices=None):
        notices.extend(extra_notices or [])
        return {
            "schemaVersion": SCHEMA_VERSION, "providerId": PROVIDER_ID,
            "status": status, "facts": facts[:MAX_FACTS], "advice": advice,
            "unknownFields": list(dict.fromkeys(unknown))[:MAX_UNKNOWN],
            "notices": list(dict.fromkeys(notices))[:MAX_NOTICES],
            "usage": {"elapsedMs": int((time.time() - started) * 1000), "modelCalls": 0},
        }

    if request.get("schemaVersion", "").split(".")[0] != "1":
        unknown.append("schemaVersion")
        return done("REFUSED", [], None, ["请求契约主版本不支持"])
    if not str(context.get("city") or "").strip():
        unknown.append("city")
        return done("REFUSED", [], None, ["缺少城市，餐饮推荐无法定位"])

    centers, skipped = anchors_from_facts(facts_in)
    if skipped:
        notices.append("部分锚点坐标系标签缺失或无法识别，已跳过，不猜坐标系")
    if not centers:
        unknown.append("anchor")
        return done("REFUSED", [], None, ["缺少带坐标系标签的锚点，未默认按城市中心计算"])

    for key in ("budgetCny", "mealSlot", "dietaryFlags"):
        if key not in context:
            unknown.append(key)
    if any(k in unknown for k in ("budgetCny", "mealSlot", "dietaryFlags")):
        notices.append("预算、时段或忌口缺项未替用户填空，请补填或明确选择不限制")

    radius = int(context.get("radiusMeters") or 1000)
    radius = min((500, 1000, 3000), key=lambda level: abs(level - radius))
    budget = as_number(context.get("budgetCny"))
    meal_slot = context.get("mealSlot") if isinstance(context.get("mealSlot"), str) else None
    dietary = {str(x).upper() for x in (context.get("dietaryFlags") or [])
               if isinstance(x, str) and x.strip()}
    show_reviews = context.get("showReviews") is not False
    show_reasons = context.get("showReasons") is not False
    if context.get("includeInspiration") is True:
        notices.append("灵感层未接入，本轮不召回内容源候选")

    lat, lon = centers[0]
    places, failures = [], 0
    for fetcher in fetchers:
        try:
            places.extend(fetcher(lat, lon, radius, 25))
        except Exception:  # 不回显异常文本与密钥
            failures += 1

    filtered = 0
    safe = []
    for place in dedupe(places):
        if not place.get("name") or place.get("lat") is None or place.get("lon") is None:
            continue
        flags = allergen_flags(str(place.get("name")) + ";" + str(place.get("tags") or ""))
        if dietary and any(flag in dietary for flag in flags):
            filtered += 1
            continue
        safe.append(place)
    if filtered:
        notices.append(f"按安全约束剔除 {filtered} 条候选")
    if dietary:
        notices.append("忌口判定仅依据店名与类别关键词，不能替代商户确认，请到店核实")
    if failures:
        notices.append("附近实时数据部分不可用，本次候选覆盖可能不完整")
    notices += ["距离按直线计算，不是步行距离或步行时间",
                "评分与人均是地图快照，未经商户核验",
                "评价平台外链白名单未确认，本轮不生成外链"]

    safe.sort(key=lambda p: (p.get("poiId") is None, str(p.get("poiId") or ""),
                             str(p.get("name") or "")))
    truncated = len(safe) > MAX_FACTS
    selected = safe[:MAX_FACTS]
    if truncated:
        notices.append("候选超过契约上限，按固定顺序截取，可缩小半径或勾选软筛条件查看其余候选")

    facts = []
    for index, place in enumerate(selected):
        distance = int(round(min(haversine(c[0], c[1], place["lat"], place["lon"])
                                 for c in centers)))
        facts.append(food_fact(place, index, distance, radius, budget,
                               meal_slot, show_reviews, show_reasons))
        for field, key in (("pricePerPersonCny", "pricePerPersonCny"),
                           ("openHours", "openHours"), ("rating", "rating")):
            if place.get(field) is None:
                unknown.append(key)
    if not facts:
        notices.append("本次没有拿到可用的附近餐饮候选，未编造候选")

    status = "DEGRADED" if (not facts or failures or truncated) else "OK"
    return done(status, facts, ADVICE if facts else None)


# ---------------------------------------------------------------- HTTP


class Handler(BaseHTTPRequestHandler):
    server_version = "FoodProvider/0.1"

    def log_message(self, fmt, *args):
        # 只记方法与状态，不记请求正文（含忌口与坐标），也不记密钥。
        sys.stderr.write("[food-provider] %s\n" % (fmt % args).split(" ")[0])

    def _send(self, code, body):
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if urllib.parse.urlparse(self.path).path == "/healthz":
            self._send(200, {"status": "ok", "providerId": PROVIDER_ID})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/v1/invoke":
            self._send(404, {"error": "not found"})
            return
        token = os.environ.get("PY_AGENT_TOKEN", "").strip()
        if token and self.headers.get("Authorization") != "Bearer " + token:
            self._send(401, {"error": "unauthorized"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length > 65536:
            self._send(413, {"error": "request too large"})
            return
        try:
            request = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send(400, {"error": "invalid json"})
            return
        self._send(200, handle_invoke(request))


def serve(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"[food-provider] listening on 127.0.0.1:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


# ---------------------------------------------------------------- 自测


def self_test():
    passed, total = 0, 0
    anchor = {"factId": "anchor:a1", "type": "ATTRACTION",
              "value": {"lat": 30.259, "lon": 120.13, "coordinateSystem": "GCJ-02"},
              "evidence": {"sourceKind": "KB", "verificationStatus": "unverified",
                           "fetchedAt": now_iso(), "excerpt": "锚点"}}

    def request(extra=None):
        context = {"city": "杭州", "mealSlot": "LUNCH", "budgetCny": 100,
                   "dietaryFlags": []}
        context.update(extra or {})
        return {"schemaVersion": "1.0", "runId": "gc01-run-0001",
                "intent": "FOOD_RECOMMEND", "context": context,
                "facts": [anchor], "hints": {}, "deadlineAt": now_iso()}

    def check(condition, label):
        nonlocal passed, total
        total += 1
        if condition:
            passed += 1
            print("PASS", label)
        else:
            print("FAIL", label)

    def fixture_fetcher(_lat, _lon, _radius, _limit):
        return [{"poiId": "B1", "name": "知味观", "lat": 30.2595, "lon": 120.1305,
                 "tags": "杭帮菜;老字号", "rating": 4.7, "pricePerPersonCny": 68.0,
                 "openHours": None, "source": "AMAP", "fetchedAt": now_iso()},
                {"poiId": "B2", "name": "花生小馆", "lat": 30.2596, "lon": 120.1306,
                 "tags": "花生", "rating": None, "pricePerPersonCny": None,
                 "openHours": None, "source": "EXTERNAL", "fetchedAt": now_iso()}]

    def failing(_lat, _lon, _radius, _limit):
        raise RuntimeError("密钥缺失，不应回显")

    ok = handle_invoke(request(), (fixture_fetcher, fixture_fetcher))
    check(set(ok) == {"schemaVersion", "providerId", "status", "facts", "advice",
                      "unknownFields", "notices", "usage"}, "响应字段与契约一致")
    check(ok["status"] == "OK" and len(ok["facts"]) == 2, "真实 POI 装配成候选")
    check(all(f["value"]["category"] == "FOOD" and f["type"] == "CONTENT"
              and f["evidence"]["verificationStatus"] == "unverified"
              for f in ok["facts"]), "候选承载与证据状态")
    check(ok["usage"]["elapsedMs"] >= 0, "usage.elapsedMs 必填非负")
    check(len(ok["facts"]) <= 50 and len(ok["notices"]) <= 20, "契约上限")

    filtered = handle_invoke(request({"dietaryFlags": ["PEANUT"]}),
                             (fixture_fetcher, fixture_fetcher))
    text = json.dumps(filtered, ensure_ascii=False)
    check(len(filtered["facts"]) == 1 and "花生小馆" not in text
          and "PEANUT" not in text, "忌口整条剔除且不回显枚举原文")

    degraded = handle_invoke(request(), (failing, failing))
    check(degraded["status"] == "DEGRADED" and not degraded["facts"]
          and degraded["advice"] is None, "无 key 时零候选且不编造")
    check("密钥缺失" not in json.dumps(degraded, ensure_ascii=False), "异常文本不外泄")

    refused = handle_invoke(request({"city": ""}), (fixture_fetcher, fixture_fetcher))
    check(refused["status"] == "REFUSED" and "city" in refused["unknownFields"],
          "缺城市直接 REFUSED")
    no_anchor = handle_invoke({**request(), "facts": []},
                              (fixture_fetcher, fixture_fetcher))
    check(no_anchor["status"] == "REFUSED" and "anchor" in no_anchor["unknownFields"],
          "缺锚点不默认按城市中心")

    off = handle_invoke(request({"showReviews": False}), (fixture_fetcher, fixture_fetcher))
    check(all(k not in off["facts"][0]["value"]
              for k in ("rating", "reviewLinks")), "showReviews 关闭时不下发评分外链")

    converted, skipped = anchors_from_facts([{
        "type": "ATTRACTION",
        "value": {"lat": 39.9087, "lon": 116.3975, "coordinateSystem": "WGS-84"}}])
    check(skipped == 0 and abs(converted[0][0] - 39.910103499) < 1e-6
          and abs(converted[0][1] - 116.403743573) < 1e-6,
          "WGS-84 锚点换算到 GCJ-02")

    print(f"FOOD PROVIDER SELF-TEST: {passed}/{total} passed")
    return 0 if passed == total else 1


def main():
    parser = argparse.ArgumentParser(description="外部美食推荐 Provider")
    parser.add_argument("--self-test", action="store_true", help="离线自测，不联网")
    parser.add_argument("--serve", action="store_true", help="启动 HTTP 服务")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.serve:
        serve(args.port)
        return 0
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
