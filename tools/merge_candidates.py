#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
合并跨源同名强疑似重复记录（配套 docs/cross_source_review.md 的 candidate 处置）。

用户授权合并 4 组（每组删 1 条、留 1 条主记录，不合并亚龙湾）：
  拓荒牛 / 郑成功塑像 / 凤凰岭森林公园 / 孔子像

动作：
  - 删除被合并的 4 条记录（破坏性，仅 --apply 执行）
  - 主记录清除 merge_review / same_name_risk / same_name_group（合并后组内不再重复）
  - 写 F:/wb_tmp/merge_log.json 追溯（保留 id、删除 id、名称、被清字段）

幂等：被删 id 已不存在 / 主记录标记已清空时自动跳过。KB 保持 compact 格式。
"""
import json, argparse

KB_PATH = r"F:\repository\Travel-Assistant-Agent\data\china_attractions.json"
LOG_PATH = r"F:\wb_tmp\merge_log.json"

# (keep_id, del_id, name)
MERGE = [
    ("osm-node-5129313898", "osm-node-7293706193", "拓荒牛"),
    ("osm-node-1762860406", "osm-node-11561846235", "郑成功塑像"),
    ("osm-way-675580207",   "osm-way-675580208",   "凤凰岭森林公园"),
    ("osm-node-4336476909", "osm-node-6721129809", "孔子像"),
]
CLEAR_FIELDS = ("merge_review", "same_name_risk", "same_name_group")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="落库；默认仅 dry-run 打印")
    args = ap.parse_args()

    kb = json.load(open(KB_PATH, encoding="utf-8"))
    recs = kb["attractions"]
    byid = {r["id"]: r for r in recs}
    n0 = len(recs)
    log, to_del = [], set()

    for keep, delid, name in MERGE:
        if delid not in byid:
            print(f"[SKIP] {name}: 删除目标 {delid} 已不存在")
            continue
        k = byid[keep]
        cleared = {f: k.get(f) for f in CLEAR_FIELDS if k.get(f)}
        for f in CLEAR_FIELDS:
            if k.get(f) is not None:
                k[f] = None
        to_del.add(delid)
        log.append({"name": name, "keep": keep, "del": delid, "cleared": cleared})
        print(f"[{'APPLY' if args.apply else 'DRY'}] {name}: 保留 {keep} / 删除 {delid} / 清除 {cleared}")

    print(f"(dry?={not args.apply}) 将删 {len(to_del)} 条，总数 {n0} -> {n0 - len(to_del)}")
    if not args.apply:
        print("（dry-run，未落库；加 --apply 落库）")
        return

    kb["attractions"] = [r for r in recs if r["id"] not in to_del]
    json.dump(kb, open(KB_PATH, "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    json.dump(log, open(LOG_PATH, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"已落库。删除后总数: {len(kb['attractions'])}")


if __name__ == "__main__":
    main()
