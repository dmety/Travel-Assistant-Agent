# -*- coding: utf-8 -*-
"""知识库治理门禁：把结构/血缘检查与逐项豁免清单核对后决定交付是否放行。

与 ``kb.quality_report --strict`` 的区别：后者只要有任何非零失败就 ``exit=1``，
无法区分「已登记的存量欠账」与「新增回归」。本脚本允许登记在册、理由齐全且
未恶化的存量欠账通过，但以下情况一律 ``exit=1``：

* 出现未登记的非零严格检查项；
* 已登记项超过 ``maxAllowed``（欠账恶化）；
* 豁免条目缺少理由、证据、处置计划或复核日期；
* 豁免已过 ``reviewBy`` 仍未复核。

**豁免只表示「暂不阻断交付」，不表示「已核验」或「已修复」。**
用法：``PYTHONPATH="tools;$PYTHONPATH" python -B tools/kb_quality_gate.py``
"""
import argparse
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WAIVERS = ROOT / "tools" / "kb_quality_waivers.json"
REQUIRED_TEXT_FIELDS = ("reason", "evidence", "plan", "reviewBy")


def load_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    parser = argparse.ArgumentParser(description="Gate KB structural/data-lineage checks against waivers.")
    parser.add_argument("--path", type=Path, default=None,
                        help="knowledge-base JSON path (default: repository data file)")
    parser.add_argument("--waivers", type=Path, default=DEFAULT_WAIVERS,
                        help="waiver registry path")
    args = parser.parse_args()

    try:
        from kb.quality_report import STRICT_FAILURE_FIELDS, DEFAULT_PATH, build_report
    except ImportError as error:
        print(f"KB GATE FAIL: 无法导入 kb.quality_report（{error}）。"
              " 需要 PYTHONPATH 含 tools 目录。", file=sys.stderr)
        return 2

    try:
        waiver_doc = load_json(args.waivers)
    except (OSError, ValueError) as error:
        print(f"KB GATE FAIL: 豁免清单不可读：{args.waivers}（{error}）", file=sys.stderr)
        return 1

    kb_path = args.path or DEFAULT_PATH
    try:
        report = build_report(load_json(kb_path))
    except (OSError, ValueError) as error:
        print(f"KB GATE FAIL: 知识库不可读：{kb_path}（{error}）", file=sys.stderr)
        return 1

    entries = waiver_doc.get("waivers")
    if not isinstance(entries, list):
        print("KB GATE FAIL: 豁免清单缺少 waivers 数组", file=sys.stderr)
        return 1

    registered = {}
    errors = []
    for entry in entries:
        metric = entry.get("metric")
        if not isinstance(metric, str) or metric in registered:
            errors.append(f"豁免条目 metric 缺失或重复：{metric!r}")
            continue
        registered[metric] = entry

    failures = {key: report.get(key, 0)
                for key in STRICT_FAILURE_FIELDS if report.get(key, 0)}
    today = date.today()
    warnings = []

    for metric, entry in registered.items():
        if metric not in STRICT_FAILURE_FIELDS:
            errors.append(f"豁免登记了非严格检查项：{metric}")
            continue
        for field in REQUIRED_TEXT_FIELDS:
            if not str(entry.get(field) or "").strip():
                errors.append(f"豁免 {metric} 缺少 {field}")
        maximum = entry.get("maxAllowed")
        if not isinstance(maximum, int) or isinstance(maximum, bool) or maximum < 0:
            errors.append(f"豁免 {metric} 的 maxAllowed 必须是非负整数")
        try:
            review_by = date.fromisoformat(str(entry.get("reviewBy")))
        except (TypeError, ValueError):
            errors.append(f"豁免 {metric} 的 reviewBy 不是 ISO 日期")
            review_by = None
        if review_by is not None and review_by < today:
            errors.append(f"豁免 {metric} 已于 {review_by} 到期，必须复核并更新或销项")
        current = report.get(metric, 0)
        if current == 0:
            warnings.append(f"{metric} 已归零，请从豁免清单删除该条目")
        elif isinstance(maximum, int) and current > maximum:
            errors.append(f"豁免 {metric} 恶化：当前 {current} > 允许上限 {maximum}")
        elif isinstance(entry.get("observed"), int) and current < entry["observed"]:
            warnings.append(f"{metric} 已改善（{entry['observed']} → {current}），建议同步下调 maxAllowed")

    for metric, value in sorted(failures.items()):
        if metric not in registered:
            errors.append(f"未登记的新失败：{metric}={value}")

    print(f"KB QUALITY GATE: {kb_path.name} records={report.get('record_count')} "
          f"strict_failures={len(failures)} registered={len(registered)}")
    for metric, value in sorted(failures.items()):
        state = "WAIVED" if metric in registered else "UNREGISTERED"
        print(f"  {state:13s} {metric}={value}")
    for warning in warnings:
        print(f"  WARN         {warning}")

    if errors:
        for error in errors:
            print(f"KB GATE FAIL: {error}", file=sys.stderr)
        return 1
    print("KB QUALITY GATE PASS: 存量欠账均已登记且未恶化，无新增结构/血缘失败。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
