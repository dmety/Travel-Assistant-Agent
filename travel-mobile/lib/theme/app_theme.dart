import 'package:flutter/material.dart';

import 'tokens.dart';

/// 应用主题：把 [T] 的令牌接到 Material 3 上。
///
/// 刻意只覆盖必要项（配色 / 背景 / 字体 / 文本层级），
/// 组件级样式一律在各自的 Widget 里用令牌拼装——
/// 这样升级 Flutter 时不会被 `CardTheme`/`InputDecorationTheme`
/// 这类随版本改签名的主题字段拖死。
ThemeData buildAppTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: T.primary,
    brightness: Brightness.light,
  ).copyWith(
    primary: T.primary,
    onPrimary: Colors.white,
    primaryContainer: T.primarySoft,
    onPrimaryContainer: T.ink,
    secondary: T.text2,
    surface: T.surface,
    onSurface: T.ink,
    error: T.err,
    onError: Colors.white,
    outline: T.lineStrong,
    outlineVariant: T.line,
  );

  const textTheme = TextTheme(
    // 12 / 14 / 16 / 20 / 25，与 Web --fs-* 严格对齐
    labelSmall: T.tXs,
    bodySmall: T.tXs,
    bodyMedium: T.tSmInk,
    bodyLarge: T.tMd,
    titleMedium: TextStyle(
      fontSize: T.fsMd,
      height: 1.5,
      color: T.ink,
      fontWeight: T.wStrong,
    ),
    titleLarge: T.tLgStrong,
    headlineSmall: T.tLgStrong,
    headlineMedium: T.tXlStrong,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: T.bg,
    canvasColor: T.bg,
    textTheme: textTheme,
    fontFamily: null, // 跟随系统中文字体（Android 上为思源/苹方回退）
    appBarTheme: const AppBarTheme(
      backgroundColor: T.surface,
      foregroundColor: T.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(
        fontSize: T.fsMd,
        color: T.ink,
        fontWeight: T.wStrong,
      ),
    ),
    dividerTheme: const DividerThemeData(
      color: T.line,
      thickness: 1,
      space: 1,
    ),
    splashFactory: InkSparkle.splashFactory,
  );
}
