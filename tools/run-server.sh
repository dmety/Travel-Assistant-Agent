#!/usr/bin/env bash
# 启动 travel-server（从 gitignored .env 注入密钥，绝不回显值）。
# 背景：Spring Boot 不读 .env；.env 高德键名为 AMAP_API_KEY，应用读 AMAP_KEY。
# application.yml 已加回退 ${AMAP_KEY:${AMAP_API_KEY:}}，本脚本再做同名导出双保险。
# 用法：bash tools/run-server.sh [端口，默认 8080]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${1:-8080}"

# 逐行解析 .env（容忍 CRLF / 行尾注释 / 引号），导出为同名 OS 变量
if [ -f "$ROOT/.env" ]; then
  while IFS='=' read -r k v || [ -n "${k:-}" ]; do
    k="$(printf '%s' "$k" | tr -d '\r' | sed 's/^ *//;s/ *$//')"
    v="$(printf '%s' "$v" | tr -d '\r' | sed 's/ *$//;s/^"//;s/"$//;s/^'\''//;s/'\''$//')"
    case "$k" in ''|\#*) continue ;; esac
    export "$k=$v"
  done < "$ROOT/.env"
fi

# .env 的 AMAP_API_KEY 映射为应用读取的 AMAP_KEY（yml 回退之外的双保险）
if [ -z "${AMAP_KEY:-}" ] && [ -n "${AMAP_API_KEY:-}" ]; then
  export AMAP_KEY="$AMAP_API_KEY"
fi

JAR="$ROOT/travel-server/target/travel-server-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "jar 不存在：先构建 mvn -pl travel-server -am package" >&2
  exit 1
fi

export SERVER_PORT="$PORT"
export TRAVEL_DATA_DIR="${TRAVEL_DATA_DIR:-$ROOT/data}"

echo "starting travel-server on port $PORT (keys: amap=$([ -n "${AMAP_KEY:-}" ] && echo yes || echo no), baidu=$([ -n "${BAIDU_AK:-}" ] && echo yes || echo no), deepseek=$([ -n "${DEEPSEEK_API_KEY:-}" ] && echo yes || echo no))"
exec java -jar "$JAR"
