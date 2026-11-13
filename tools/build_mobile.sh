#!/usr/bin/env bash
# 构建 travel-mobile APK，并让百度地图 AK 在「原生 Manifest」与「Dart 侧」同源注入。
#
# 密钥来源（按优先级，绝不进 git）：
#   1. android/local.properties 的 `baiduMapAk=...`（android/.gitignore 已忽略）
#   2. 仓库根 .env 的 `BAIDU_AK_MOBILE=...`（.gitignore 已忽略，后端也在用同一把）
#   3. 环境变量 BAIDU_MAP_AK
#
# 本脚本做两件事：
#   1. export BAIDU_MAP_AK -> gradle 已读该环境变量注入 Manifest 的 com.baidu.lbsapi.API_KEY
#   2. 把同一个值经 --dart-define=BAIDU_MAP_AK=... 传给 Dart（MapPanel 据此决定是否走真地图）
#
# 用法（仓库根目录执行）：
#   bash tools/build_mobile.sh            # 打 debug APK
#   bash tools/build_mobile.sh --release  # 打 release APK
#
# 不配置 AK 也能正常构建：Dart 侧检测到 AK 为空会走 SVG 示意底图降级，
# 原生端 meta-data 值为空、SDK 不鉴权，二者视觉一致。
set -euo pipefail

cd "$(dirname "$0")/../travel-mobile"

# --- 取 AK：local.properties > .env(BAIDU_AK_MOBILE) > 环境变量 ---
AK=""
LP="android/local.properties"
if [[ -f "$LP" ]]; then
  AK="$(grep -aE '^baiduMapAk=' "$LP" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r')" || true
fi

if [[ -z "$AK" ]]; then
  ENV_FILE="../.env"
  if [[ -f "$ENV_FILE" ]]; then
    # 兼容 BOM/CRLF：去首行 BOM、去回车、按键取值
    AK="$(grep -aE 'BAIDU_AK_MOBILE=' "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- | sed '1s/^\xEF\xBB\xBF//' | tr -d '\r')" || true
  fi
fi

if [[ -z "$AK" && -n "${BAIDU_MAP_AK:-}" ]]; then
  AK="$BAIDU_MAP_AK"
fi

export BAIDU_MAP_AK="$AK"

FLUTTER="${FLUTTER:-flutter}"
EXTRA=()
if [[ -n "$AK" ]]; then
  EXTRA+=(--dart-define="BAIDU_MAP_AK=$AK")
  echo "[build_mobile] 检测到百度 AK，已注入 Manifest + --dart-define=BAIDU_MAP_AK (len=${#AK})"
else
  echo "[build_mobile] 未配置 AK -> 真地图关闭，MapPanel 走 SVG 示意底图"
fi

if [[ "${1:-}" == "--release" ]]; then
  "$FLUTTER" build apk --release "${EXTRA[@]}"
else
  "$FLUTTER" build apk "${EXTRA[@]}"
fi
