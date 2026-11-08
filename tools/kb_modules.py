# -*- coding: utf-8 -*-
"""知识库字段视图：按需从单体主库生成按职责分层的本地视图。

2026-11-08 的 data 目录优化后，``data/attractions/`` 是唯一编辑源，
``data/kb_modules/`` 不再入库，也不再由 ``kb_shards.py --sync`` 自动生成，
``tools/gate.sh`` 也不再校验它。本脚本保留为可选工具：需要按字段浏览主库时，
跑一次 ``--split`` 生成本地视图，用完可删；生成的目录受 ``/data/*`` 黑名单
约束，不会被提交。日常改动请走分片与 ``kb_shards.py --sync``。

用法::

    python -B tools/kb_modules.py --split     # 从当前主库生成本地字段视图
    python -B tools/kb_modules.py --check     # 仅当视图已生成时，校验与主库逐字节一致
    python -B tools/kb_modules.py --assemble  # 从视图写回主库（随后需重建索引）

``--split`` 和 ``--assemble`` 都先做一次内存往返校验：合成出的文档必须能
逐字节复现当前主库。任何字段漏分组、记录 id 不对齐都会直接报错，不静默丢数据。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KB = ROOT / "data" / "china_attractions.json"
DEFAULT_MODULES = ROOT / "data" / "kb_modules"
SCHEMA = "kb-module-v1"

# 字段分组即记录模型。新增字段必须在这里登记，否则 --split / --assemble 会拒绝执行。
LAYERS = (
    ("identity", ("id", "source_ids", "same_name_group", "same_name_risk", "merge_review")),
    ("geo", ("city", "province", "address", "lat", "lon", "coordinateSystem")),
    ("content", ("name", "name_zh", "type", "tags", "description", "image",
                 "photos", "wikidata", "tel", "website")),
    ("facts", ("level", "rating", "open_time", "ticket", "ticket_status", "open_time_status")),
    ("evidence", ("source", "sources", "ticket_source", "ticket_checked_at",
                  "ticket_supporting_source", "open_time_source", "open_time_checked_at",
                  "open_time_supporting_source")),
)
LAYER_NAMES = tuple(name for name, _ in LAYERS)
LAYER_FIELDS = {name: tuple(fields) for name, fields in LAYERS}
ALL_FIELDS = {f for fields in LAYER_FIELDS.values() for f in fields}
SINGLE_ROW_MODULES = ("meta", "city_introductions")
MODULE_FILES = LAYER_NAMES + SINGLE_ROW_MODULES + ("layout",)
SERIALIZATION = {"ensureAscii": False, "separators": [",", ":"], "trailingNewline": False}


class ModuleError(Exception):
    """模块缺失、字段漂移或 id 不对齐。"""


def serialize(doc: dict) -> str:
    """按主库既有格式序列化，保证产物逐字节稳定。"""
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"))


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ModuleError(f"无法读取 {path}: {e}") from e


def write_atomic(path: Path, text: str) -> None:
    """先写同目录临时文件再替换，避免留下半个模块文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".kb_module", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        os.replace(tmp, path)
    except Exception:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise


def load_modules(modules_dir: Path) -> dict:
    mods = {}
    for name in MODULE_FILES:
        path = modules_dir / f"{name}.json"
        if not path.exists():
            raise ModuleError(f"缺少模块文件 {path}，请先运行 --split")
        doc = read_json(path)
        if not isinstance(doc, dict) or doc.get("schema") != SCHEMA or doc.get("layer") != name:
            raise ModuleError(f"{path} 的 schema/layer 不正确")
        mods[name] = doc
    return mods


def build_rows(mods: dict) -> list:
    """按 layout 的字段顺序模板，把各层字段重新拼成记录。"""
    layout = mods["layout"]
    variants = layout.get("fieldOrderVariants")
    indexes = layout.get("variantIndex")
    if not isinstance(variants, list) or not isinstance(indexes, list):
        raise ModuleError("layout.json 缺少 fieldOrderVariants / variantIndex")

    sizes = {name: len(mods[name]["records"]) for name in LAYER_NAMES}
    if len(set(sizes.values())) != 1:
        raise ModuleError(f"各层记录数不一致：{sizes}")
    count = sizes["identity"]
    if len(indexes) != count:
        raise ModuleError(f"layout.variantIndex 有 {len(indexes)} 项，记录有 {count} 条")

    rows = []
    seen_ids = set()
    for i in range(count):
        ident = mods["identity"]["records"][i]
        rid = ident.get("id")
        if not rid:
            raise ModuleError(f"identity 第 {i} 条缺 id")
        if rid in seen_ids:
            raise ModuleError(f"景点 id 重复：{rid}")
        seen_ids.add(rid)

        avail = {}
        for name, fields in LAYERS:
            rec = mods[name]["records"][i]
            if rec.get("_id") != rid:
                raise ModuleError(f"{name} 第 {i} 条的 _id 与 identity.id 不一致：{rec.get('_id')} != {rid}")
            for key, value in rec.items():
                if key == "_id":
                    continue
                if key not in LAYER_FIELDS[name]:
                    raise ModuleError(f"{name} 第 {i} 条出现未分组字段 {key}，请先更新 tools/kb_modules.py 的 LAYERS")
                if key in avail:
                    raise ModuleError(f"第 {i} 条字段 {key} 在多个模块里重复出现")
                avail[key] = value

        variant_index = indexes[i]
        if not isinstance(variant_index, int) or not 0 <= variant_index < len(variants):
            raise ModuleError(f"第 {i} 条的 variantIndex 越界：{variant_index}")
        row = {}
        for field in variants[variant_index]:
            if field not in ALL_FIELDS:
                raise ModuleError(f"layout 模板含未知字段 {field}")
            if field not in avail:
                raise ModuleError(f"第 {i} 条（{rid}）缺字段 {field}")
            row[field] = avail.pop(field)
        # 模块里新增的字段按分组顺序追加，让新增记录也能合成。
        for _, fields in LAYERS:
            for field in fields:
                if field in avail:
                    row[field] = avail.pop(field)
        if avail:
            raise ModuleError(f"第 {i} 条有字段未写入：{sorted(avail)}")
        rows.append(row)
    return rows


def assemble_doc(mods: dict) -> dict:
    layout = mods["layout"]
    top_keys = layout.get("topLevelKeys")
    if not isinstance(top_keys, list):
        raise ModuleError("layout.json 缺少 topLevelKeys")
    meta = mods["meta"].get("fields", {})
    doc = {}
    for key in top_keys:
        if key == "attractions":
            doc[key] = build_rows(mods)
        elif key == "city_introductions":
            doc[key] = mods["city_introductions"].get("introductions", {})
        elif key in meta:
            doc[key] = meta[key]
        else:
            raise ModuleError(f"顶层键 {key} 在 meta.json 里找不到")
    extra = set(meta) - set(top_keys)
    if extra:
        raise ModuleError(f"meta.json 有未写入的顶层键：{sorted(extra)}")
    return doc


def split_doc(doc: dict) -> dict:
    """从单体主库生成模块内容；layout 只用于逐字节复现历史字段顺序。"""
    rows = doc.get("attractions")
    if not isinstance(rows, list):
        raise ModuleError("主库缺少 attractions 数组")

    variants, variant_map, indexes = [], {}, []
    for row in rows:
        key = tuple(row.keys())
        if key not in variant_map:
            unknown = sorted(set(key) - ALL_FIELDS)
            if unknown:
                raise ModuleError(f"主库出现未分组字段 {unknown}，请先更新 tools/kb_modules.py 的 LAYERS")
            variant_map[key] = len(variants)
            variants.append(list(key))
        indexes.append(variant_map[key])

    mods = {
        "meta": {
            "schema": SCHEMA, "layer": "meta",
            "fields": {k: v for k, v in doc.items()
                       if k not in ("attractions", "city_introductions")},
        },
        "city_introductions": {
            "schema": SCHEMA, "layer": "city_introductions",
            "introductions": doc.get("city_introductions", {}),
        },
        "layout": {
            "schema": SCHEMA, "layer": "layout",
            "topLevelKeys": list(doc.keys()),
            "fieldOrderVariants": variants,
            "variantIndex": indexes,
            "serialization": SERIALIZATION,
            "note": "兼容层：只记录历史字段顺序，由 --split 维护，不要手工编辑",
        },
    }
    for name, fields in LAYERS:
        records = []
        for row in rows:
            rec = {"_id": row["id"]}
            for field in fields:
                if field in row:
                    rec[field] = row[field]
            records.append(rec)
        mods[name] = {"schema": SCHEMA, "layer": name,
                      "fields": list(fields), "records": records}
    return mods


def first_difference(expected: str, actual: str) -> str:
    """字节不一致时，定位到第一条有差异的记录和字段，方便修模块。"""
    try:
        a, b = json.loads(expected), json.loads(actual)
    except ValueError:
        return "JSON 解析失败"
    if a == b:
        return "语义一致，仅字段顺序／序列化格式不同"
    for key in dict.fromkeys(list(a) + list(b)):
        if a.get(key) != b.get(key):
            if key == "attractions":
                ra, rb = a.get(key, []), b.get(key, [])
                for i, (x, y) in enumerate(zip(ra, rb)):
                    if x != y:
                        fields = sorted({f for f in dict.fromkeys(list(x) + list(y)) if x.get(f) != y.get(f)})
                        return f"attractions[{i}] id={x.get('id')!r} 差异字段={fields}"
                return f"attractions 条数 {len(ra)} != {len(rb)}"
            return f"顶层键 {key} 不同"
    return "未知差异"


def cmd_check(kb: Path, modules_dir: Path) -> int:
    mods = load_modules(modules_dir)
    produced = serialize(assemble_doc(mods))
    if not kb.exists():
        print(f"[失败] 主库不存在：{kb}", file=sys.stderr)
        return 2
    current = kb.read_text(encoding="utf-8")
    if produced != current:
        print("[失败] 模块合成结果与主库不一致。", file=sys.stderr)
        print(f"       原因：{first_difference(current, produced)}", file=sys.stderr)
        print("       若刚改过模块，运行 --assemble；若主库被外部脚本改动，运行 --split。", file=sys.stderr)
        return 1
    rows = mods["identity"]["records"]
    print(f"[通过] 模块与主库逐字节一致：{len(rows)} 条 / {len(MODULE_FILES)} 个模块 / {len(current)} 字符")
    return 0


def cmd_split(kb: Path, modules_dir: Path) -> int:
    current = kb.read_text(encoding="utf-8")
    mods = split_doc(json.loads(current))
    produced = serialize(assemble_doc(mods))
    if produced != current:
        print("[拒绝] 内存往返未能逐字节复现主库，未写入任何模块文件。", file=sys.stderr)
        print(f"       原因：{first_difference(current, produced)}", file=sys.stderr)
        return 1
    for name in MODULE_FILES:
        write_atomic(modules_dir / f"{name}.json",
                     json.dumps(mods[name], ensure_ascii=False, indent=2) + "\n")
    print(f"[完成] 已从主库拆出 {len(MODULE_FILES)} 个模块到 {modules_dir}")
    print(f"       {len(mods['identity']['records'])} 条记录，"
          f"{len(mods['layout']['fieldOrderVariants'])} 种字段顺序模板")
    return 0


def cmd_assemble(kb: Path, modules_dir: Path) -> int:
    mods = load_modules(modules_dir)
    doc = assemble_doc(mods)
    write_atomic(kb, serialize(doc))
    print(f"[完成] 已从模块写回主库：{kb}，{len(doc['attractions'])} 条")
    print("       下一步：python -B tools/build_attraction_index.py 重建索引，再跑 --check")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="知识库内容分层：模块 <-> 单体主库")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="校验模块与主库逐字节一致（默认）")
    group.add_argument("--split", action="store_true", help="从主库重新生成模块文件")
    group.add_argument("--assemble", action="store_true", help="从模块写回主库")
    parser.add_argument("--kb", type=Path, default=DEFAULT_KB, help="单体主库路径")
    parser.add_argument("--modules-dir", type=Path, default=DEFAULT_MODULES, help="模块目录")
    args = parser.parse_args()
    try:
        if args.split:
            return cmd_split(args.kb, args.modules_dir)
        if args.assemble:
            return cmd_assemble(args.kb, args.modules_dir)
        return cmd_check(args.kb, args.modules_dir)
    except ModuleError as e:
        print(f"[错误] {e}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
