import 'package:flutter/material.dart';

/// 设计令牌：唯一来源是 Web 端 `chat.html` 的 `:root`。
///
/// 移动端不另造取值——颜色、字号、间距、圆角、阴影全部从这里取，
/// 保证 Web / App / CLI 三端视觉同一套语言。改令牌先改 Web 再同步这里。
class T {
  T._();

  // ---------- 颜色（Web `:root` 原值）----------
  /// 主色 blue-600：主行动、当前选中、链接。
  static const primary = Color(0xFF2563EB);
  static const primaryHover = Color(0xFF1D4ED8);

  /// 主色淡背景与描边：用于强调块，而不是给文字上灰（Law 7）。
  static const primarySoft = Color(0xFFEFF6FF);
  static const primaryLine = Color(0xFFBFDBFE);

  static const ok = Color(0xFF15803D);
  static const okSoft = Color(0xFFECFDF5);
  static const okLine = Color(0xFFA7F3D0);

  static const err = Color(0xFFB91C1C);
  static const errSoft = Color(0xFFFEF2F2);
  static const errLine = Color(0xFFFECACA);

  static const warn = Color(0xFFB45309);
  static const warnSoft = Color(0xFFFFFBEB);
  static const warnLine = Color(0xFFFDE68A);

  /// 三级文本：正文 / 次要 / 辅助。整个 App 只用这三种文字色 + 白。
  static const ink = Color(0xFF0F172A);
  static const text2 = Color(0xFF475569);
  static const text3 = Color(0xFF64748B);

  static const line = Color(0xFFE2E8F0);
  static const lineStrong = Color(0xFFCBD5E1);

  static const surface = Color(0xFFFFFFFF);
  static const surface2 = Color(0xFFF8FAFC);
  static const bg = Color(0xFFF1F5F9);

  // ---------- 字号（5 级，Web --fs-*）----------
  static const fsXs = 12.0;
  static const fsSm = 14.0;
  static const fsMd = 16.0;
  static const fsLg = 20.0;
  static const fsXl = 25.0;

  /// 字重只有两档（Law 6）：正文 400 / 强调 600。
  static const wBody = FontWeight.w400;
  static const wStrong = FontWeight.w600;

  // ---------- 间距（8 级，Web --sp-*）----------
  static const sp1 = 4.0;
  static const sp2 = 8.0;
  static const sp3 = 12.0;
  static const sp4 = 16.0;
  static const sp6 = 24.0;
  static const sp8 = 32.0;
  static const sp12 = 48.0;
  static const sp16 = 64.0;

  // ---------- 圆角（4 级，Web --r-*）----------
  static const rSm = 6.0;
  static const rCtl = 8.0;
  static const rCard = 12.0;
  static const rPill = 999.0;

  // ---------- 阴影（3 级，Web --shadow-*）----------
  static const shadow1 = [
    BoxShadow(color: Color(0x0F0F172A), blurRadius: 2, offset: Offset(0, 1)),
  ];
  static const shadow2 = [
    BoxShadow(color: Color(0x0D0F172A), blurRadius: 2, offset: Offset(0, 1)),
    BoxShadow(color: Color(0x0F0F172A), blurRadius: 24, offset: Offset(0, 8)),
  ];
  static const shadow3 = [
    BoxShadow(color: Color(0x0F0F172A), blurRadius: 4, offset: Offset(0, 2)),
    BoxShadow(color: Color(0x1A0F172A), blurRadius: 40, offset: Offset(0, 16)),
  ];

  // ---------- 复用样式 ----------
  /// 卡片：白底 + 1px 描边 + 一级阴影（Web `.fold`/`.plan` 同一套）。
  static BoxDecoration cardDeco({Color? color, double radius = rCard}) =>
      BoxDecoration(
        color: color ?? surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: line),
        boxShadow: shadow1,
      );

  /// 次要块：浅灰底、无阴影（Web `.cands`/`.opts` 用 surface-2）。
  static BoxDecoration panelDeco({Color? color, double radius = rCard}) =>
      BoxDecoration(
        color: color ?? surface2,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: line),
      );

  // ---------- 文本样式 ----------
  static const tXs = TextStyle(
    fontSize: fsXs,
    height: 1.5,
    color: text3,
    fontWeight: wBody,
  );
  static const tSm = TextStyle(
    fontSize: fsSm,
    height: 1.6,
    color: text2,
    fontWeight: wBody,
  );
  static const tSmInk = TextStyle(
    fontSize: fsSm,
    height: 1.6,
    color: ink,
    fontWeight: wBody,
  );
  static const tMd = TextStyle(
    fontSize: fsMd,
    height: 1.6,
    color: ink,
    fontWeight: wBody,
  );
  static const tLgStrong = TextStyle(
    fontSize: fsLg,
    height: 1.4,
    color: ink,
    fontWeight: wStrong,
  );
  static const tXlStrong = TextStyle(
    fontSize: fsXl,
    height: 1.3,
    color: ink,
    fontWeight: wStrong,
  );

  /// 小标签：12px + 600（Web `.plan-tag`/`.glabel`）。
  static const tLabel = TextStyle(
    fontSize: fsXs,
    height: 1.4,
    color: text3,
    fontWeight: wStrong,
  );
}
