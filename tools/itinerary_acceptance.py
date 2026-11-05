#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UC-ITIN-001「杭州西湖三日游」—— 行程管理端到端回归用例（由 acceptance.py 在隔离服务上调用）。

只用标准库，无第三方依赖。自动完成：注册 → 登录 → 建行程 → 填满五大板块 →
邀请成员并授权 → 验证板块级越权 → 验证乐观锁冲突 → 验证三类非法输入 → 清理。

用法（服务需先启动）：
    python tools/itinerary_acceptance.py --base http://localhost:8080
    python tools/itinerary_acceptance.py --base http://localhost:8080 --keep

--keep 表示跑完不删除行程，便于在 /app 页面里查看结果。
退出码：0 = 全部断言通过；1 = 有用例失败。
"""

import argparse
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

OWNER = "alice"
GUEST = "bob"
PASSWORD = "Passw0rd123"

# ---------------------------------------------------------------- 用例数据

CREATE_BODY = {
    "title": "杭州西湖三日游",
    "plan": {
        "destination": "杭州",
        "summary": "西湖—灵隐—西溪，三天两晚",
        "notes": "11 月下旬红叶季，灵隐飞来峰需提前预约；西湖游船末班 16:30",
    },
}

PLAN = {
    "destination": "杭州",
    "summary": "西湖—灵隐—西溪，三天两晚（已订票订房）",
    "notes": "11 月下旬红叶季，灵隐飞来峰已预约 09:00 场；西湖游船末班 16:30",
}

TRANSPORTS = [
    {
        "mode": "高铁",
        "from": "上海虹桥",
        "to": "杭州东",
        "date": "2026-11-20",
        "departTime": "08:05",
        "arriveTime": "09:12",
        "flightNo": "G7501",
        "seat": "05车12F",
        "note": "提前 20 分钟检票",
    },
    {
        "mode": "高铁",
        "from": "杭州东",
        "to": "上海虹桥",
        "date": "2026-11-22",
        "departTime": "18:40",
        "arriveTime": "19:47",
        "flightNo": "G7598",
        "seat": "08车07A",
        "note": "",
    },
]

LODGINGS = [
    {
        "name": "西湖边民宿",
        "address": "杭州市西湖区北山街 27 号",
        "checkIn": "2026-11-20",
        "checkOut": "2026-11-22",
        "roomType": "湖景大床房",
        "note": "含早餐，可寄存行李",
    }
]

DAILY = [
    {
        "date": "2026-11-20",
        "note": "抵达日，节奏放缓",
        "activities": [
            {"time": "12:00", "title": "湖滨午餐", "location": "湖滨银泰 in77", "note": "杭帮菜"},
            {"time": "14:30", "title": "断桥—白堤散步", "location": "断桥残雪", "note": "日落前到"},
            {"time": "19:00", "title": "西湖音乐喷泉", "location": "湖滨三公园", "note": "19:00/20:00 两场"},
        ],
    },
    {
        "date": "2026-11-21",
        "note": "灵隐 + 西溪",
        "activities": [
            {"time": "08:30", "title": "灵隐寺 + 飞来峰", "location": "灵隐飞来峰景区", "note": "已预约 09:00 场"},
            {"time": "13:30", "title": "西溪湿地摇橹船", "location": "西溪湿地周家村", "note": "约 1.5 小时"},
            {"time": "18:30", "title": "河坊街晚餐", "location": "清河坊历史街区", "note": ""},
        ],
    },
    {
        "date": "2026-11-22",
        "note": "返程日",
        "activities": [
            {"time": "09:30", "title": "苏堤骑行", "location": "苏堤", "note": "租车 2 小时"},
            {"time": "12:00", "title": "退房 + 午餐", "location": "北山街", "note": "12:00 前退房"},
            {"time": "17:30", "title": "前往杭州东站", "location": "杭州东站", "note": "地铁 1 号线"},
        ],
    },
]

EXPENSES = [
    {"category": "交通", "amount": 146.00, "currency": "CNY", "date": "2026-11-20", "note": "高铁往返 73×2"},
    {"category": "住宿", "amount": 1376.00, "currency": "CNY", "date": "2026-11-20", "note": "688×2 晚"},
    {"category": "门票", "amount": 90.00, "currency": "CNY", "date": "2026-11-21", "note": "灵隐飞来峰 45×2"},
    {"category": "门票", "amount": 160.00, "currency": "CNY", "date": "2026-11-21", "note": "西溪湿地 80×2"},
    {"category": "餐饮", "amount": 520.00, "currency": "CNY", "date": "2026-11-21", "note": "三日餐饮估算"},
]


# ---------------------------------------------------------------- HTTP 客户端

class Client:
    """带 Cookie 会话的极简 JSON 客户端（urllib 标准库）。"""

    def __init__(self, base):
        self.base = base.rstrip("/")
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def call(self, method, path, body=None, form=None):
        url = self.base + path
        data, headers = None, {}
        if form is not None:
            data = urllib.parse.urlencode(form).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=20) as resp:
                return resp.status, dict(resp.headers), _parse(resp.read())
        except urllib.error.HTTPError as err:
            return err.code, dict(err.headers), _parse(err.read())

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None, form=None):
        return self.call("POST", path, body=body, form=form)

    def put(self, path, body=None):
        return self.call("PUT", path, body=body)

    def delete(self, path):
        return self.call("DELETE", path)


def _parse(raw):
    text = raw.decode("utf-8", "replace").strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


# ---------------------------------------------------------------- 断言与报告

RESULTS = []

# --fail-fast：首个断言失败立即中止。跑门禁时用它，避免一个前置步骤失败后
# 后续几十条断言连锁报错，把真正的原因埋在一片 FAIL 里。
FAIL_FAST = False


class AssertionFailed(Exception):
    """--fail-fast 下首个失败的断言。"""


def check(no, name, expect, actual, body=None, note=""):
    expects = expect if isinstance(expect, (tuple, list, set)) else (expect,)
    ok = actual in expects
    shown = "/".join(str(e) for e in expects)
    RESULTS.append((no, name, shown, actual, ok, body, note))
    flag = "OK  " if ok else "FAIL"
    print(f"[{flag}] {no} {name}  期望 {shown} / 实际 {actual}" + (f"  {note}" if note else ""))
    if not ok:
        print(f"       响应: {json.dumps(body, ensure_ascii=False) if isinstance(body, (dict, list)) else body}")
        if FAIL_FAST:
            raise AssertionFailed(f"{no} {name}：期望 {shown}，实际 {actual}")
    return ok


def expect(no, name, cond, detail=""):
    RESULTS.append((no, name, "断言", "成立" if cond else "不成立", cond, None, detail))
    print(f"[{'OK  ' if cond else 'FAIL'}] {no} {name}  {detail}" + ("" if cond else "  ← 断言不成立"))
    if not cond and FAIL_FAST:
        raise AssertionFailed(f"{no} {name}：{detail}")
    return cond


# ---------------------------------------------------------------- 用例主体

def main():
    global FAIL_FAST
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="UC-ITIN-001 杭州西湖三日游端到端用例")
    parser.add_argument("--base", default="http://localhost:8080", help="服务基址")
    parser.add_argument("--keep", action="store_true", help="跑完不删除行程")
    parser.add_argument("--quiet-json", action="store_true", help="不打印最终行程 JSON")
    parser.add_argument("--fail-fast", action="store_true",
                        help="首个断言失败即中止（门禁用；默认跑完全部断言）")
    args = parser.parse_args()
    FAIL_FAST = args.fail_fast

    base = args.base.rstrip("/")
    print(f"=== UC-ITIN-001 杭州西湖三日游 @ {base} ===\n")

    # --- 0. 可用性
    anon = Client(base)
    st, _, body = anon.get("/api/health")
    if not check("00", "健康检查", 200, st, body):
        print("\n服务不可用，请先启动：java -jar travel-server/target/*.jar --server.port=8080")
        return 1

    # --- 1. 账号准备（重复注册容忍 409，便于反复执行）
    for user in (OWNER, GUEST):
        st, _, body = anon.post("/api/account/register", {"username": user, "password": PASSWORD})
        check(f"01.{user}", f"注册 {user}", (201, 409), st, body,
              "新建" if st == 201 else "已存在，复用")

    # --- 2. owner 登录
    owner = Client(base)
    st, _, body = owner.post("/api/login", form={"username": OWNER, "password": PASSWORD})
    check("02", f"{OWNER} 登录", 200, st, body)
    if st != 200:
        return 1

    st, _, body = anon.get("/api/itineraries")
    check("02.401", "未登录访问受保护接口 → 401", 401, st, body)

    # --- 3. 创建行程（version = 1）
    st, hdr, body = owner.post("/api/itineraries", CREATE_BODY)
    check("03", "创建行程", 201, st, body)
    if st != 201:
        return 1
    itin_id = body["id"]
    ver = body["version"]
    expect("03.a", "创建后 version = 1", ver == 1, f"version={ver}")
    expect("03.b", "owner 为当前用户", body["owner"] == OWNER, f"owner={body['owner']}")
    print(f"        行程 id = {itin_id}\n")

    # --- 4~8. 依次填充五大板块，每次写操作 version +1
    steps = [
        ("04", "更新方案 PLAN", f"/api/itineraries/{itin_id}/plan", PLAN),
        ("05", "更新交通 TRANSPORT", f"/api/itineraries/{itin_id}/transports", TRANSPORTS),
        ("06", "更新住宿 LODGING", f"/api/itineraries/{itin_id}/lodgings", LODGINGS),
        ("07", "更新每日安排 DAILY", f"/api/itineraries/{itin_id}/daily", DAILY),
        ("08", "更新费用 EXPENSE", f"/api/itineraries/{itin_id}/expenses", EXPENSES),
    ]
    for no, name, path, payload in steps:
        st, _, body = owner.put(f"{path}?version={ver}", payload)
        if not check(no, name, 200, st, body):
            return 1
        expect(f"{no}.v", f"{name.split()[-1]} version {ver}→{ver + 1}",
               body["version"] == ver + 1, f"version={body['version']}")
        ver = body["version"]

    # 子项 id 由服务端补全
    st, _, detail = owner.get(f"/api/itineraries/{itin_id}")
    check("09", "读取行程详情", 200, st, detail)
    expect("09.a", "交通子项 id 已补全",
           all(t.get("id") for t in detail["transports"]), f"{len(detail['transports'])} 条交通")
    expect("09.b", "每日活动 id 已补全",
           all(a.get("id") for d in detail["dailySchedules"] for a in d["activities"]),
           f"{len(detail['dailySchedules'])} 天")
    expect("09.c", "五大板块均已落库",
           bool(detail["plan"]["summary"]) and detail["transports"] and detail["lodgings"]
           and detail["dailySchedules"] and detail["expenses"],
           "plan/transport/lodging/daily/expense 均非空")
    ver = detail["version"]

    # --- 10. 邀请成员，授予「交通 + 住宿」编辑权
    st, _, body = owner.post(
        f"/api/itineraries/{itin_id}/members?version={ver}",
        {"username": GUEST, "editSections": ["TRANSPORT", "LODGING"]},
    )
    check("10", "邀请 bob（授予 TRANSPORT+LODGING）", 200, st, body)
    if st != 200:
        return 1
    ver = body["version"]
    expect("10.a", "成员列表含 bob", any(m["username"] == GUEST for m in body["members"]),
           f"members={[m['username'] for m in body['members']]}")

    # --- 11. 被邀请者登录，可看可改授权板块
    guest = Client(base)
    st, _, body = guest.post("/api/login", form={"username": GUEST, "password": PASSWORD})
    check("11", f"{GUEST} 登录", 200, st, body)

    st, _, body = guest.get(f"/api/itineraries/{itin_id}")
    check("11.a", "bob 可查看行程（成员默认查看权）", 200, st, body)

    st, _, body = guest.put(
        f"/api/itineraries/{itin_id}/transports?version={ver}",
        [
            {"mode": "高铁", "from": "上海虹桥", "to": "杭州东", "date": "2026-11-20",
             "departTime": "07:35", "arriveTime": "08:44", "flightNo": "G7305",
             "seat": "05车09C", "note": "bob 改签，提前半小时"},
            TRANSPORTS[1],
        ],
    )
    check("12", "bob 有 TRANSPORT 权限 → 改签成功", 200, st, body)
    if st == 200:
        ver = body["version"]
        expect("12.a", "改签已生效", body["transports"][0]["flightNo"] == "G7305",
               f"flightNo={body['transports'][0]['flightNo']}")

    # --- 13. 板块级越权：bob 无 EXPENSE 权限
    st, _, body = guest.put(f"/api/itineraries/{itin_id}/expenses?version={ver}", EXPENSES)
    check("13", "bob 无 EXPENSE 权限 → 403", 403, st, body)
    _, _, detail = guest.get(f"/api/itineraries/{itin_id}")
    expect("13.a", "越权写不改变 version", detail["version"] == ver,
           f"version={detail['version']}（期望 {ver}）")

    # --- 14. 乐观锁：owner 拿着过期版本提交
    stale = ver - 3
    st, hdr, body = owner.put(f"/api/itineraries/{itin_id}/plan?version={stale}", PLAN)
    check("14", f"过期版本 v{stale} 提交 → 409", 409, st, body)
    expect("14.a", "409 响应头带回最新版本",
           hdr.get("X-Version") == str(ver), f"X-Version={hdr.get('X-Version')}")

    # --- 15. 三类非法输入（均 400，且不改变版本）
    st, _, body = owner.put(
        f"/api/itineraries/{itin_id}/expenses?version={ver}",
        [{"category": "交通", "amount": -999.99, "currency": "CNY", "date": "2026-11-20", "note": "负数"}],
    )
    check("15", "费用金额为负 → 400", 400, st, body)

    st, _, body = owner.put(
        f"/api/itineraries/{itin_id}/lodgings?version={ver}",
        [{"name": "西湖边民宿", "address": "北山街 27 号", "checkIn": "2026-11-22",
          "checkOut": "2026-11-20", "roomType": "湖景大床房", "note": "离店早于入住"}],
    )
    check("16", "离店早于入住 → 400", 400, st, body)

    st, _, body = owner.post(
        f"/api/itineraries/{itin_id}/members?version={ver}",
        {"username": "ghost_user_999", "editSections": ["PLAN"]},
    )
    check("17", "邀请未注册用户 → 400", 400, st, body)
    _, _, detail = owner.get(f"/api/itineraries/{itin_id}")
    expect("17.a", "三次非法请求后版本未变", detail["version"] == ver,
           f"version={detail['version']}（期望 {ver}）")

    # --- 17b/17c. 补齐 D-2（空标题）与 D-3（改不存在的成员）
    st, _, body = owner.post("/api/itineraries", {"title": "   "})
    check("17.b", "标题为空白 → 400", 400, st, body)

    st, _, body = owner.put(
        f"/api/itineraries/{itin_id}/members/nobody?version={ver}",
        {"username": "nobody", "editSections": ["PLAN"]},
    )
    check("17.c", "修改不存在的成员 → 404", 404, st, body)
    expect("17.d", "404 明确是「成员不存在」",
           isinstance(body, dict) and body.get("error") == "成员不存在",
           f"error={body.get('error') if isinstance(body, dict) else body}")

    # --- 18. 最终快照
    st, hdr, final = owner.get(f"/api/itineraries/{itin_id}")
    check("18", "最终读取行程", 200, st, final)
    if st == 200:
        total = sum(float(e["amount"] or 0) for e in final["expenses"])
        print(f"        version={final['version']}  板块: 交通{len(final['transports'])} "
              f"住宿{len(final['lodgings'])} 日程{len(final['dailySchedules'])} "
              f"费用{len(final['expenses'])}  合计 {total:.2f} CNY")
        if not args.quiet_json:
            print("\n--- 最终行程 JSON ---")
            print(json.dumps(final, ensure_ascii=False, indent=2))

    # --- 19. 清理
    if args.keep:
        print(f"\n已保留行程：{base}/app  → id={itin_id}（version={final.get('version')}）")
    else:
        st, _, body = owner.delete(f"/api/itineraries/{itin_id}")
        check("19", "删除行程（owner）", 200, st, body)

    # --- 汇总
    passed = sum(1 for r in RESULTS if r[4])
    total_n = len(RESULTS)
    print(f"\n=== 结果：{passed}/{total_n} 通过 ===")
    return 0 if passed == total_n else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionFailed as failure:
        print(f"\n=== --fail-fast：首个失败断言即中止 → {failure} ===")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n已中断")
        sys.exit(130)
