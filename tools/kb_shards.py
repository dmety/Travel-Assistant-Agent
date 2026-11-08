# -*- coding: utf-8 -*-
"""知识库体积拆分：按省分片与单体主库之间的双向同步。

上一版 ``kb_modules.py`` 是按字段分层，虽然改一条记录只 diff 几行，但
``content.json`` 仍有 4 MiB，6.4 MiB 的单体主库也还在。本脚本把
``data/china_attractions.json`` 的 ``attractions`` 数组按省切成多个小文件，
放在 ``data/attractions/`` 下，每个文件一条记录一行，既能直接打开看，
也让 git diff 只落在改动的那一行。

数据来源：分片是唯一编辑源；单体主库、``kb_modules/`` 和运行索引都是派生产物。
主库记录原本不按省聚簇，因此每个分片用 ``positions`` 记录各条记录在
原数组中的下标，合成时按下标归位，保证逐字节复现历史主库。

用法::

    python -B tools/kb_shards.py --check     # 门禁：分片合成结果是否与主库逐字节一致
    python -B tools/kb_shards.py --split     # 主库被旧脚本改动后，反向重建分片
    python -B tools/kb_shards.py --assemble  # 改完分片后写回主库
    python -B tools/kb_shards.py --sync      # 一键：分片 -> 主库 -> 模块 -> 运行索引

分片目录里除 ``manifest.json`` 外的每个 .json 都必须在清单里登记；
多文件、缺文件、位置重复或漏号、记录省份与分片不符，一律拒绝合成。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KB = ROOT / "data" / "china_attractions.json"
DEFAULT_SHARDS = ROOT / "data" / "attractions"
SCHEMA = "kb-shard-v1"
MANIFEST_FILE = "manifest.json"
SERIALIZATION = {"ensureAscii": False, "separators": [",", ":"], "trailingNewline": False}
BAD_FILENAME_CHARS = set('/\\:*?"<>|')


class ShardError(Exception):
    """分片缺失、位置漂移或记录归属错误。"""


def serialize(doc: dict) -> str:
    """按主库既有格式序列化，保证产物逐字节稳定。"""
    return json.dumps(doc, ensure_ascii=False, separators=(",", ":"))


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ShardError(f"无法读取 {path}: {e}") from e


def write_atomic(path: Path, text: str) -> None:
    """先写同目录临时文件再替换，避免留下半个分片。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".kb_shard", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        os.replace(tmp, path)
    except Exception:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise


def shard_filename(province: str) -> str:
    if any(ch in BAD_FILENAME_CHARS for ch in province) or province in (".", ".."):
        raise ShardError(f"省名不可用作文件名：{province!r}")
    return f"{province}.json"


def render_positions(positions: list) -> str:
    """每 20 个下标折一行，便于 diff。"""
    lines = ["    " + ", ".join(str(p) for p in positions[i:i + 20])
             for i in range(0, len(positions), 20)]
    return ",\n".join(lines)


def render_shard(shard: dict) -> str:
    """一条记录一行，不做缩进展开，兼顾体积与 diff 粒度。"""
    records = ",\n".join(serialize(row) for row in shard["records"])
    return (
        "{\n"
        f'  "schema": "{SCHEMA}",\n'
        f'  "province": {json.dumps(shard["province"], ensure_ascii=False)},\n'
        f'  "positions": [\n{render_positions(shard["positions"])}\n  ],\n'
        f'  "records": [\n{records}\n  ]\n'
        "}\n"
    )


def render_manifest(manifest: dict) -> str:
    return json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def load_shards(shards_dir: Path):
    """读取清单与全部分片，返回 (manifest, 分片内容列表)。"""
    manifest_path = shards_dir / MANIFEST_FILE
    if not manifest_path.exists():
        raise ShardError(f"缺少清单 {manifest_path}，请先运行 --split")
    manifest = read_json(manifest_path)
    if not isinstance(manifest, dict) or manifest.get("schema") != SCHEMA:
        raise ShardError(f"{manifest_path} 的 schema 不正确")
    declared = manifest.get("shards")
    if not isinstance(declared, list) or not declared:
        raise ShardError("manifest.shards 必须是非空数组")

    files = set()
    for item in declared:
        if not isinstance(item, dict) or not isinstance(item.get("file"), str):
            raise ShardError(f"manifest.shards 项缺 file：{item!r}")
        files.add(item["file"])
    if len(files) != len(declared):
        raise ShardError("manifest.shards 里有重复文件名")
    on_disk = {p.name for p in shards_dir.glob("*.json")} - {MANIFEST_FILE}
    missing, extra = files - on_disk, on_disk - files
    if missing:
        raise ShardError(f"清单登记但磁盘缺少分片：{sorted(missing)}")
    if extra:
        raise ShardError(f"磁盘存在未登记的分片：{sorted(extra)}；请 --split 重建或手工清理")

    shards = []
    for item in declared:
        name, province, count = item.get("file"), item.get("province"), item.get("count")
        if not isinstance(name, str) or not isinstance(province, str) or not isinstance(count, int):
            raise ShardError(f"manifest.shards 项字段不合法：{item}")
        if name == MANIFEST_FILE or shard_filename(province) != name:
            raise ShardError(f"分片文件名与省名不匹配：{name} / {province}")
        doc = read_json(shards_dir / name)
        if not isinstance(doc, dict) or doc.get("schema") != SCHEMA:
            raise ShardError(f"{name} 的 schema 不正确")
        if doc.get("province") != province:
            raise ShardError(f"{name} 的 province 与清单不一致：{doc.get('province')} != {province}")
        positions, records = doc.get("positions"), doc.get("records")
        if not isinstance(positions, list) or not isinstance(records, list):
            raise ShardError(f"{name} 缺 positions 或 records")
        if len(positions) != len(records) or len(records) != count:
            raise ShardError(f"{name} 条数不符：positions={len(positions)} records={len(records)} count={count}")
        if any(not isinstance(p, int) or isinstance(p, bool) for p in positions):
            raise ShardError(f"{name} 的 positions 含非整数")
        for i, row in enumerate(records):
            if not isinstance(row, dict) or not row.get("id"):
                raise ShardError(f"{name} 第 {i} 条缺 id")
            if row.get("province") != province:
                raise ShardError(f"{name} 第 {i} 条（{row.get('id')}）的 province 是 {row.get('province')!r}")
        shards.append({"file": name, "province": province,
                       "positions": positions, "records": records})
    return manifest, shards


def assemble_doc(manifest: dict, shards: list) -> dict:
    """按 positions 把分片记录放回原数组下标，再按原顶层键顺序组装。"""
    total = sum(len(s["records"]) for s in shards)
    rows = [None] * total
    for s in shards:
        for pos, row in zip(s["positions"], s["records"]):
            if not 0 <= pos < total or rows[pos] is not None:
                raise ShardError(f"{s['file']} 的 positions 越界或重复：{pos}")
            rows[pos] = row
    if any(r is None for r in rows):
        holes = [i for i, r in enumerate(rows) if r is None][:5]
        raise ShardError(f"positions 未覆盖全部下标，缺口示例：{holes}")

    top_keys = manifest.get("topLevelKeys")
    fields = manifest.get("fields")
    if not isinstance(top_keys, list) or not isinstance(fields, dict):
        raise ShardError("manifest 缺 topLevelKeys 或 fields")
    doc = {}
    for key in top_keys:
        if key == "attractions":
            doc[key] = rows
        elif key == "city_introductions":
            doc[key] = manifest.get("cityIntroductions", {})
        elif key in fields:
            doc[key] = fields[key]
        else:
            raise ShardError(f"顶层键 {key} 在 manifest.fields 里找不到")
    extra = set(fields) - set(top_keys)
    if extra:
        raise ShardError(f"manifest.fields 有未写入的顶层键：{sorted(extra)}")
    return doc


def split_doc(doc: dict):
    """从单体主库生成清单与分片内容；保留原数组下标以便逐字节复现。"""
    rows = doc.get("attractions")
    if not isinstance(rows, list):
        raise ShardError("主库缺少 attractions 数组")
    groups = {}
    for i, row in enumerate(rows):
        province = row.get("province")
        if not isinstance(province, str) or not province:
            raise ShardError(f"attractions[{i}] id={row.get('id')!r} 缺 province")
        slot = groups.setdefault(province, {"positions": [], "records": []})
        slot["positions"].append(i)
        slot["records"].append(row)

    shards = []
    declared = []
    for province in sorted(groups):
        name = shard_filename(province)
        slot = groups[province]
        shards.append({"file": name, "province": province, **slot})
        declared.append({"file": name, "province": province, "count": len(slot["records"])})
    manifest = {
        "schema": SCHEMA,
        "note": "分片按省切分，records 一条一行；positions 是该条在主库 attractions 中的原始下标",
        "topLevelKeys": list(doc.keys()),
        "fields": {k: v for k, v in doc.items()
                   if k not in ("attractions", "city_introductions")},
        "cityIntroductions": doc.get("city_introductions", {}),
        "shards": declared,
        "serialization": SERIALIZATION,
    }
    return manifest, shards


def first_difference(expected: str, actual: str) -> str:
    """字节不一致时定位到第一条有差异的记录，方便修分片。"""
    try:
        a, b = json.loads(expected), json.loads(actual)
    except ValueError:
        return "JSON 解析失败"
    if a == b:
        return "语义一致，仅字段顺序／序列化格式不同"
    ra, rb = a.get("attractions", []), b.get("attractions", [])
    for i, (x, y) in enumerate(zip(ra, rb)):
        if x != y:
            fields = sorted({f for f in dict.fromkeys(list(x) + list(y)) if x.get(f) != y.get(f)})
            return f"attractions[{i}] id={x.get('id')!r} 差异字段={fields}"
    if len(ra) != len(rb):
        return f"attractions 条数 {len(ra)} != {len(rb)}"
    for key in dict.fromkeys(list(a) + list(b)):
        if a.get(key) != b.get(key):
            return f"顶层键 {key} 不同"
    return "未知差异"


def describe(shards: list, shards_dir: Path) -> str:
    sizes = [(s["file"], (shards_dir / s["file"]).stat().st_size) for s in shards]
    biggest = max(sizes, key=lambda item: item[1])
    total = sum(size for _, size in sizes)
    return (f"{len(shards)} 个分片 / 合计 {total / 1024 / 1024:.2f} MiB / "
            f"最大 {biggest[0]} {biggest[1] / 1024:.0f} KiB")


def cmd_check(kb: Path, shards_dir: Path) -> int:
    if not kb.exists():
        print(f"[失败] 主库不存在：{kb}", file=sys.stderr)
        return 2
    manifest, shards = load_shards(shards_dir)
    produced = serialize(assemble_doc(manifest, shards))
    current = kb.read_text(encoding="utf-8")
    if produced != current:
        print("[失败] 分片合成结果与主库不一致。", file=sys.stderr)
        print(f"       原因：{first_difference(current, produced)}", file=sys.stderr)
        print("       改过分片请运行 --sync；主库被旧脚本改动请运行 --split。", file=sys.stderr)
        return 1
    print(f"[通过] 分片与主库逐字节一致：{len(json.loads(current)['attractions'])} 条 / "
          f"{describe(shards, shards_dir)}")
    return 0


def cmd_split(kb: Path, shards_dir: Path) -> int:
    current = kb.read_text(encoding="utf-8")
    manifest, shards = split_doc(json.loads(current))
    produced = serialize(assemble_doc(manifest, shards))
    if produced != current:
        print("[拒绝] 内存往返未能逐字节复现主库，未写入任何分片。", file=sys.stderr)
        print(f"       原因：{first_difference(current, produced)}", file=sys.stderr)
        return 1
    existing = {p.name for p in shards_dir.glob("*.json")} - {MANIFEST_FILE} if shards_dir.exists() else set()
    stale = existing - {s["file"] for s in shards}
    if stale:
        print(f"[拒绝] 分片目录存在新清单之外的文件，请先手工确认：{sorted(stale)}", file=sys.stderr)
        return 1
    for shard in shards:
        write_atomic(shards_dir / shard["file"], render_shard(shard))
    write_atomic(shards_dir / MANIFEST_FILE, render_manifest(manifest))
    print(f"[完成] 已从主库拆出 {describe(shards, shards_dir)} 到 {shards_dir}")
    return 0


def cmd_assemble(kb: Path, shards_dir: Path) -> int:
    manifest, shards = load_shards(shards_dir)
    doc = assemble_doc(manifest, shards)
    write_atomic(kb, serialize(doc))
    print(f"[完成] 已从分片写回主库：{kb}，{len(doc['attractions'])} 条")
    return 0


def run_step(args: list) -> int:
    print(f"[同步] {' '.join(args)}")
    return subprocess.call([sys.executable, "-B", *args], cwd=ROOT)


def cmd_sync(kb: Path, shards_dir: Path) -> int:
    manifest, shards = load_shards(shards_dir)
    doc = assemble_doc(manifest, shards)
    text = serialize(doc)
    if kb.exists() and kb.read_text(encoding="utf-8") == text:
        print(f"[同步] 主库已与分片一致，跳过写回：{len(doc['attractions'])} 条")
    else:
        write_atomic(kb, text)
        print(f"[同步] 分片 -> 主库：{len(doc['attractions'])} 条")
    code = run_step(["tools/kb_modules.py", "--split"])
    if code != 0:
        print(f"[失败] kb_modules.py --split 返回 {code}，流水线中止。", file=sys.stderr)
        return code
    # 索引带构建时间戳 generated_at，无脑重写会让该文件每次都产生字节变化。
    # --check 明确忽略该字段，因此只在内容确实过期时才重建。
    index_check = subprocess.call(
        [sys.executable, "-B", "tools/build_attraction_index.py", "--check"], cwd=ROOT)
    if index_check == 0:
        print("[同步] 运行索引已与源库一致，跳过重建")
    else:
        code = run_step(["tools/build_attraction_index.py"])
        if code != 0:
            print(f"[失败] build_attraction_index.py 返回 {code}，流水线中止。", file=sys.stderr)
            return code
    print("[完成] 分片 -> 主库 -> kb_modules -> attractions_index 全链路已同步")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="知识库体积拆分：按省分片 <-> 单体主库")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="校验分片与主库逐字节一致（默认）")
    group.add_argument("--split", action="store_true", help="从主库重新生成分片")
    group.add_argument("--assemble", action="store_true", help="从分片写回主库")
    group.add_argument("--sync", action="store_true", help="一键同步主库、模块与运行索引")
    parser.add_argument("--kb", type=Path, default=DEFAULT_KB, help="单体主库路径")
    parser.add_argument("--shards-dir", type=Path, default=DEFAULT_SHARDS, help="分片目录")
    args = parser.parse_args()
    try:
        if args.split:
            return cmd_split(args.kb, args.shards_dir)
        if args.assemble:
            return cmd_assemble(args.kb, args.shards_dir)
        if args.sync:
            return cmd_sync(args.kb, args.shards_dir)
        return cmd_check(args.kb, args.shards_dir)
    except ShardError as e:
        print(f"[错误] {e}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
