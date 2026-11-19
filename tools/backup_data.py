#!/usr/bin/env python3
"""运行态数据备份（P6）：把 data/ 下的业务 JSON 复制到项目外的时间戳目录。

只输出文件数与目标路径，绝不打印文件内容——accounts.json 含 BCrypt 哈希、
itineraries.json 含用户行程，备份不得进 Git、日志或交付清单（见 AGENTS.md 与数据库设计 §10）。
备份目标必须落在仓库外，脚本会拒绝仓库内路径。知识库与购票规则已在 Git，不在备份范围。

用法：
  python tools/backup_data.py <仓库外目标目录> [--data-dir ./data]
恢复：停写后把备份目录里的 JSON 覆盖回 data/，再启动服务。
"""
import argparse
import shutil
import sys
from datetime import datetime
from pathlib import Path

RUNTIME_FILES = (
    "accounts.json", "itineraries.json", "invitations.json",
    "comments.json", "changelog.json", "candidates.json", "votes.json",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="备份运行态业务 JSON 到项目外目录")
    parser.add_argument("dest", help="仓库外的备份根目录，按时间戳建子目录")
    parser.add_argument("--data-dir", default="./data", help="运行态数据目录，默认 ./data")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    data_dir = Path(args.data_dir).resolve()
    dest_root = Path(args.dest).resolve()
    if dest_root == repo_root or repo_root in dest_root.parents:
        print("拒绝：备份目标在仓库内，请指定项目外目录（备份含账号哈希与用户行程，不得进 Git）",
              file=sys.stderr)
        return 2
    if not data_dir.is_dir():
        print(f"数据目录不存在：{data_dir}", file=sys.stderr)
        return 2

    target = dest_root / datetime.now().strftime("%Y%m%d-%H%M%S")
    target.mkdir(parents=True, exist_ok=True)
    copied = 0
    for name in RUNTIME_FILES:
        src = data_dir / name
        if src.is_file():
            shutil.copy2(src, target / name)
            copied += 1
    print(f"备份完成：{copied} 个文件 → {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())