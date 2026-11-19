#!/usr/bin/env bash
# 完整交付检查：分片一致性、索引一致性、知识库治理门禁、Maven测试与覆盖率、真实服务和重启回归。
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
# 体积拆分门禁：data/attractions/ 是唯一编辑源，按省分片合成结果必须与主库逐字节一致。
# 改了分片没跑 --sync、绕过分片直接改主库、分片缺失或位置漂移，都会在此失败。
"$PYTHON" -B tools/kb_shards.py --check
"$PYTHON" -B tools/build_attraction_index.py --check
# Agent Provider G0 静态准入：规范源 tools/agent/provider-contract.json 是单一判定依据，
# 先跑规则自测（防止实现退化），再校验脱敏种子与本机运行态配置。失败即终止，不进 Maven。
"$PYTHON" -B tools/agent/check_provider_contract.py --self-test
"$PYTHON" -B tools/agent/check_provider_contract.py
# 外部美食 Provider 参考实现的离线自测：不联网、不写盘，验证响应契约、忌口硬过滤、
# 无 key 降级与坐标系换算。真实部署需要公网 https 域名并入 allowlist，见脚本头注释。
"$PYTHON" -B tools/agent/food_provider_server.py --self-test
# 多 Agent 投票计票器自测：plan_vote.py 是 Java PlanVoteTally 的 Python 原型，
# 22 条规则逐例等价；每次交付前跑一遍，防止 Java 计票器与已定案规则漂移。
"$PYTHON" -B tools/agent/plan_vote.py
# 知识库治理门禁：存量数据欠账必须在 tools/kb_quality_waivers.json 逐项登记理由与证据；
# 未登记的新失败、欠账恶化或豁免过期一律 exit=1。kb 包在 tools/ 下，需把 tools 加入 PYTHONPATH。
# 分隔符由解释器自身决定（Windows 分号 / POSIX 冒号）：写死分号会让 Linux/macOS import 失败。
# 用 os.pathsep 取真实分隔符，并保留调用方原有的 PYTHONPATH。
PP_SEP="$("$PYTHON" -c 'import os; print(os.pathsep)')"
PYTHONPATH="tools${PP_SEP}${PYTHONPATH:-}" "$PYTHON" -B tools/kb_quality_gate.py
# 坐标转换回归：BD-09 系数、往返一致性、链路自洽（零依赖 unittest）
PYTHONPATH="tools${PP_SEP}${PYTHONPATH:-}" "$PYTHON" -B -m unittest kb.test_coordinates -v
if [ "$SKIP_TESTS" -eq 0 ]; then
  "$MVN" -B clean verify
else
  "$MVN" -B package -DskipTests
fi
# Python统一管理仓库外临时数据、日志、进程启动与退出清理，避免两套启动逻辑。
"$PYTHON" -B tools/acceptance.py
