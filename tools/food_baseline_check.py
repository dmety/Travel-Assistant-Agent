# -*- coding: utf-8 -*-
"""餐饮本地底账校验：结构合法性与「不编造」红线。

底账的作用是让无地图密钥时还能说出「本地收录了这几家」，所以它最容易变成
编造数据的入口——随手填一堆店名就能让链路「看起来有数据」。本脚本就是拦这个的：

* 每条必须有 ``source``，来路不明一律不通过；
* 有经纬度就必须有 ``coordinateSystem``，缺了不算坐标；
* ``lat`` / ``lon`` 必须成对出现，单边视为无坐标；
* ``id`` 全表唯一；
* ``verifiedAt`` 必须是 ``YYYY-MM-DD``。

底账为空是**合法状态**（项目尚未收录开放数据），脚本会明确报告空库而不是报错。
数据文件优先读 ``data/food_baseline.json``；不存在时读种子
``data/food_baseline.example.json``，避免 CI 在没有本地数据时失败。

用法::

    python -B tools/food_baseline_check.py
    python -B tools/food_baseline_check.py --file data/food_baseline.json

退出码：0 通过（含空库）；1 有结构错误；2 文件不可读。
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "data" / "food_baseline.json"
SEED = ROOT / "data" / "food_baseline.example.json"

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
KNOWN_CRS = {"GCJ-02", "GCJ02", "WGS-84", "WGS84", "BD-09", "BD09", "BD-09LL", "BD09LL"}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def check_entries(entries):
    """返回 (错误列表, 警告列表)。错误阻断，警告只提示。"""
    errors, warnings = [], []
    seen_ids = {}
    for index, entry in enumerate(entries):
        where = "第 %d 条（%s）" % (index + 1, entry.get("name") or "无名")
        if not isinstance(entry, dict):
            errors.append("%s：不是对象" % where)
            continue
        eid = entry.get("id")
        if not eid:
            errors.append("%s：缺 id" % where)
        elif eid in seen_ids:
            errors.append("%s：id「%s」与第 %d 条重复" % (where, eid, seen_ids[eid] + 1))
        else:
            seen_ids[eid] = index
        if not entry.get("name"):
            errors.append("%s：缺 name" % where)
        if not entry.get("city"):
            errors.append("%s：缺 city" % where)
        if not entry.get("source"):
            errors.append("%s：缺 source，来路不明的条目不收" % where)
        lat, lon = entry.get("lat"), entry.get("lon")
        has_lat, has_lon = lat is not None, lon is not None
        if has_lat != has_lon:
            errors.append("%s：lat / lon 必须成对，只给了一个视为无坐标" % where)
        if has_lat and has_lon:
            crs = entry.get("coordinateSystem")
            if not crs:
                errors.append("%s：有经纬度但缺 coordinateSystem，加载时会被跳过" % where)
            elif str(crs).strip().upper() not in KNOWN_CRS:
                errors.append("%s：coordinateSystem「%s」不认识" % (where, crs))
            if not (-90 <= float(lat) <= 90 and -180 <= float(lon) <= 180):
                errors.append("%s：经纬度超出合理范围" % where)
        verified = entry.get("verifiedAt")
        if verified and not DATE_RE.match(str(verified)):
            errors.append("%s：verifiedAt 必须是 YYYY-MM-DD" % where)
        if not verified:
            warnings.append("%s：没有 verifiedAt，视为未核对" % where)
    return errors, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description="餐饮本地底账校验")
    parser.add_argument("--file", help="底账 JSON 路径，缺省用 data/food_baseline.json")
    args = parser.parse_args(argv)

    path = Path(args.file) if args.file else DEFAULT_DATA
    fallback = False
    if not path.exists():
        if args.file:
            print("文件不存在：%s" % path, file=sys.stderr)
            return 2
        path = SEED
        fallback = True
    try:
        payload = load(path)
    except (OSError, ValueError) as exc:
        print("读不了 %s：%s" % (path, exc), file=sys.stderr)
        return 2

    entries = payload.get("entries")
    if not isinstance(entries, list):
        print("entries 必须是数组", file=sys.stderr)
        return 1

    print("底账文件：%s%s" % (path.relative_to(ROOT) if path.is_relative_to(ROOT) else path,
                             "（种子，非本地数据）" if fallback else ""))
    if not entries:
        print("条目数：0")
        print("空库属预期状态：项目尚未收录可公开引用的餐饮开放数据。")
        print("此时美食链路在无地图密钥时返回零候选并如实标注，不编造候选。")
        return 0

    errors, warnings = check_entries(entries)
    print("条目数：%d" % len(entries))
    for warn in warnings:
        print("  [WARN] %s" % warn)
    for err in errors:
        print("  [FAIL] %s" % err)
    if errors:
        print("-> %d 条结构错误" % len(errors))
        return 1
    print("-> 结构检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
