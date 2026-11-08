#!/usr/bin/env bash
# 完整交付检查：内容分层一致性、索引一致性、知识库治理门禁、Maven测试与覆盖率、真实服务和重启回归。
# 用法：bash tools/gate.sh [--skip-tests]
# MVN、PYTHON 可指定可执行文件；--skip-tests 仅跳过Maven测试及覆盖率。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    -h|--help) echo 'Usage: bash tools/gate.sh [--skip-tests] (MVN / PYTHON optional)'; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python}"
# 本机 Maven 常装在 PATH 之外（例如 IDEA 内置或独立解压目录）。
# 优先用显式 MVN；其次自动探测 MAVEN_HOME；都没有才报错并给出可操作的提示。
if ! command -v "$MVN" >/dev/null 2>&1; then
  if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
    MVN="$MAVEN_HOME/bin/mvn"
  else
    echo 'Maven unavailable: set MVN=/path/to/mvn or MAVEN_HOME=/path/to/maven' >&2
    exit 2
  fi
fi
command -v "$PYTHON" >/dev/null 2>&1 || { echo 'Python unavailable: set PYTHON' >&2; exit 2; }
# 内容分层门禁：主库必须由 data/kb_modules/ 合成。绕过模块直接改主库、或改了模块没合成，都会在此失败。
"$PYTHON" -B tools/kb_modules.py --check
"$PYTHON" -B tools/build_attraction_index.py --check
# 知识库治理门禁：存量数据欠账必须在 tools/kb_quality_waivers.json 逐项登记理由与证据；
# 未登记的新失败、欠账恶化或豁免过期一律 exit=1。kb 包在 tools/ 下，
# Windows 版 Python 的 PYTHONPATH 用分号分隔，因此保留调用方原有的 PYTHONPATH。
PYTHONPATH="tools;${PYTHONPATH:-}" "$PYTHON" -B tools/kb_quality_gate.py
if [ "$SKIP_TESTS" -eq 0 ]; then
  "$MVN" -B clean verify
else
  "$MVN" -B package -DskipTests
fi
# Python统一管理仓库外临时数据、日志、进程启动与退出清理，避免两套启动逻辑。
"$PYTHON" -B tools/acceptance.py
