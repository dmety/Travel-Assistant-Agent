#!/usr/bin/env bash
# 构建 travel-mobile APK，并让百度地图 AK 在「原生 Manifest」与「Dart 侧」同源注入。
#
# 密钥来源（绝不进 git）：android/local.properties 里的 `baiduMapAk=...`
#   （该文件已被 android/.gitignore 忽略）。
#
# 本脚本做两件事：
#   1. gradle 已自动读 local.properties 的 baiduMapAk -> Manifest com.baidu.lbsapi.API_KEY
#   2. 本脚本把同一个值经 --dart-define=BAIDU_MAP_AK=... 传给 Dart（MapPanel 据此决定是否走真地图）
#
# 用法（在仓库根目录或 travel-mobile/ 下执行）：
#   bash tools/build_mobile.sh            # 打 debug APK
#   bash tools/build_mobile.sh --release  # 打 release APK
#
# 不配置 baiduMapAk 也能正常构建：Dart 侧检测到 AK 为空会走 SVG 示意底图降级，
# 原生端 meta-data 值为空、SDK 不鉴权，二者视觉一致。
set -euo pipefail

cd "$(dirname "$0")/../travel-mobile"

LP="android/local.properties"
AK=""
if [[ -f "$LP" ]]; then
  # 兼容 CRLF：tr 掉回车
  AK="$(grep -E '^baiduMapAk=' "$LP" | head -1 | cut -d= -f2- | tr -d '\r')"
fi

FLUTTER="${FLUTTER:-flutter}"
EXTRA=()
if [[ -n "$AK" ]]; then
  EXTRA+=(--dart-define="BAIDU_MAP_AK=$AK")
  echo "[build_mobile] 检测到 baiduMapAk，已注入 --dart-define=BAIDU_MAP_AK (len=${#AK})"
else
  echo "[build_mobile] 未配置 baiduMapAk -> 真地图关闭，MapPanel 走 SVG 示意底图"
fi

if [[ "${1:-}" == "--release" ]]; then
  "$FLUTTER" build apk --release "${EXTRA[@]}"
else
  "$FLUTTER" build apk "${EXTRA[@]}"
fi
