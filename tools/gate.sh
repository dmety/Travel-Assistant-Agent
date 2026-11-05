#!/usr/bin/env bash
#
# G2 门禁：单元/集成测试 + 覆盖率 + 端到端用例。
#
# 之所以要脚本而不是「先起服务再手动跑一遍」：端到端用例需要一个**干净的**数据目录
# 和一个**空闲的**端口。手工做这两件事最容易出错——忘了换目录，上一轮的行程混进来，
# 断言时有时无；忘了换端口，打到了 IDE 里正在跑的那个实例，把真实数据写脏。
# 这里把三件事固定下来：随机空闲端口、target 下的临时数据目录、退出时清理。
#
# 覆盖率比对只读 G1 写出的 target/jacoco.exec，不重跑测试；阈值在 travel-server/pom.xml。
#
# 用法：
#   tools/gate.sh                # G1 + G2
#   tools/gate.sh --skip-tests   # 只跑端到端（改完前端想快速确认时用）
#   MVN=/path/to/mvn tools/gate.sh
#
# 退出码：0 通过；1 测试/覆盖率/端到端任一项失败；2 环境缺失。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数：$arg（可用：--skip-tests、--help）" >&2
      exit 2
      ;;
  esac
done

MVN="${MVN:-F:/apache-maven-3.9.16/bin/mvn}"
if [ ! -x "$MVN" ]; then
  if command -v mvn >/dev/null 2>&1; then
    MVN=mvn
  else
    echo "找不到 mvn：请用 MVN=/path/to/mvn 指定" >&2
    exit 2
  fi
fi

PYTHON="${PYTHON:-python}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "找不到 python：端到端用例需要 Python 3" >&2
  exit 2
fi

step() { printf '\n=== %s ===\n' "$*"; }

step "G0 景点索引与源库一致性"
if ! "$PYTHON" tools/build_attraction_index.py --check; then
  echo "G0 未通过：请重建景点索引后再交付" >&2
  exit 1
fi

if [ "$SKIP_TESTS" -eq 0 ]; then
  step "G1 单元 + 集成测试"
  if ! "$MVN" -B -o -pl travel-server -am test; then
    echo "G1 未通过：单元/集成测试有失败，不用再跑端到端" >&2
    exit 1
  fi

  # 覆盖率用的是 G1 刚写出的 target/jacoco.exec，不再重跑测试，只做比对
  step "G1.5 覆盖率门禁"
  if ! "$MVN" -B -o -pl travel-server jacoco:check; then
    echo "覆盖率低于 pom 中的阈值。要么补测试，要么按「只升不降」的原则说明为什么该降——" >&2
    echo "阈值口径见 travel-server/pom.xml 里 jacoco 插件的注释，明细在 travel-server/target/site/jacoco/index.html" >&2
    exit 1
  fi
fi

step "打包可执行 jar"
if ! "$MVN" -B -o -pl travel-server -am package -DskipTests; then
  echo "打包失败" >&2
  exit 1
fi

JAR="$(ls -t travel-server/target/travel-server-*.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
  echo "没找到 travel-server 的可执行 jar" >&2
  exit 2
fi

# 端口交给系统分配，避免撞上 8080 上正在跑的开发实例
PORT="$("$PYTHON" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
DATA_DIR="travel-server/target/gate-data-$$-$RANDOM"
LOG="travel-server/target/gate-server.log"
mkdir -p "$DATA_DIR"
: > "$LOG"

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null
    for _ in 1 2 3 4 5; do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$SERVER_PID" 2>/dev/null
  fi
  rm -rf "$DATA_DIR"
}
trap cleanup EXIT

step "启动服务（端口 $PORT，数据目录 $DATA_DIR）"
java -jar "$JAR" --server.port="$PORT" --travel.data-directory="$DATA_DIR" >"$LOG" 2>&1 &
SERVER_PID=$!

ready=0
for _ in $(seq 1 60); do
  if curl -sf -o /dev/null "http://127.0.0.1:$PORT/api/health"; then
    ready=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "服务进程已退出，日志末尾：" >&2
    tail -30 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "服务 60 秒内未就绪，日志末尾：" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

step "G2 端到端用例（杭州西湖三日游，--fail-fast）"
"$PYTHON" examples/itinerary_usecase_hangzhou.py \
  --base "http://127.0.0.1:$PORT" --fail-fast --quiet-json
code=$?

if [ "$code" -ne 0 ]; then
  echo "G2 未通过（退出码 $code）。服务日志：$LOG" >&2
  exit "$code"
fi

step "门禁通过"
