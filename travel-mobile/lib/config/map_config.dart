/// 百度地图 Android 端 AK（仅真地图用）。
///
/// 两处注入、必须同源：
/// 1. Dart 侧（本常量）：构建期 `--dart-define=BAIDU_MAP_AK=...`。
/// 2. 原生侧：[android/app/build.gradle.kts] 从 `local.properties` 的
///    `baiduMapAk` 读入，填入 Manifest `com.baidu.lbsapi.API_KEY` meta-data。
///
/// 密钥绝不硬编码进仓库（`local.properties` 已被 gitignore）。
/// 为空时 [MapPanel] 走 SVG 示意底图降级。
const String baiduMapAk =
    String.fromEnvironment('BAIDU_MAP_AK', defaultValue: '');
