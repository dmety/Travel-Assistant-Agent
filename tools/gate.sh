#!/usr/bin/env bash
# 完整交付检查：索引一致性、Maven测试与覆盖率、真实服务和重启回归。
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
"$PYTHON" -B tools/build_attraction_index.py --check
if [ "$SKIP_TESTS" -eq 0 ]; then
  "$MVN" -B clean verify
else
  "$MVN" -B package -DskipTests
fi
# Python统一管理仓库外临时数据、日志、进程启动与退出清理，避免两套启动逻辑。
"$PYTHON" -B tools/acceptance.py
