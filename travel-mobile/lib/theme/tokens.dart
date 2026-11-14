import 'package:flutter/material.dart';

/// 设计令牌：唯一来源是 Web 端 `design/tokens.css`（松绿 + 赤陶 + 暖纸）。
///
/// 移动端不另造取值——颜色、字号、间距、圆角、阴影全部从这里取，
/// 保证 Web / App / CLI 三端视觉同一套语言。改令牌先改 tokens.css 再同步这里。
///
/// 主题桥见 [buildAppTheme]：`ColorScheme` 与 `scaffoldBackgroundColor` 等
/// 都从本类取值，因此翻主题只需改这里的常量。
class T {
  T._();

  // ---------- 品牌色板（design/tokens.css 第一版：暖纸 + 墨绿 + 赤陶）----------
  /// 深墨绿（松绿）：主行动、当前选中、链接、品牌。
  static const pine = Color(0xFF1E4A3E);
  static const pineHover = Color(0xFF163A30);
  static const pineSoft = Color(0xFFE4EDE7);
  static const pineLine = Color(0xFFBBD0C4);

  /// 赤陶：只给「需要人决断」的地方（待投票、待定）。
  static const clay = Color(0xFFB4542A);
  static const clayHover = Color(0xFF98441F);
  static const claySoft = Color(0xFFF6E8DF);
  static const clayLine = Color(0xFFE3C4B1);

  /// 暖纸面与文字。
  static const paper = Color(0xFFF7F4EE);
  static const paper2 = Color(0xFFEFEAE0);
  static const paper3 = Color(0xFFE7E0D2);
  static const surfaceWarm = Color(0xFFFCFAF5);

  static const ink = Color(0xFF20241E); // 主文字（墨）
  static const ink2 = Color(0xFF4A4F46); // 次级文字
  static const text2 = Color(0xFF4A4F46); // ink2 的语义别名
  static const text3 = Color(0xFF6E6A5E); // 辅助文字（muted）
  static const muted = Color(0xFF6E6A5E);
  static const muted2 = Color(0xFF8A8577);

  static const line = Color(0xFFDED7C9); // 描边（line-warm）
  static const lineStrong = Color(0xFFC4BAA6); // 强描边（line-strong-warm）
  static const fieldLine = Color(0xFF9C9282); // 输入框描边
  /// 浮在地图上的半透明底板（glass）。
  static const glass = Color(0xF2FCFAF5);

  // ---------- 语义层：组件只用这些（主色映射松绿）----------
  static const primary = pine;
  static const primaryHover = pineHover;
  static const primarySoft = pineSoft;
  static const primaryLine = pineLine;

  static const surface = Color(0xFFFFFFFF);
  static const surface2 = paper2;
  static const bg = paper;

  static const ok = Color(0xFF2E6B52);
  static const okSoft = Color(0xFFE7F0EA);
  static const okLine = Color(0xFFBBD0C4);

  static const warn = Color(0xFF8A6212);
  static const warnSoft = Color(0xFFF8EFDC);
  static const warnLine = Color(0xFFE8D6AE);

  static const err = Color(0xFFA03A2C);
  static const errSoft = Color(0xFFF8E7E3);
  static const errLine = Color(0xFFE8C6BE);

  static const gold = Color(0xFF8A6D2B);

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

  // ---------- 圆角（4 级，Web --r-*：有机柔和梯度）----------
  static const rSm = 3.0;
  static const rCtl = 6.0;
  static const rCard = 10.0;
  static const rLg = 16.0;
  static const rPill = 999.0;

  // ---------- 阴影（3 级，Web --shadow-*：带环境绿褐，不用纯黑）----------
  static const shadow1 = [
    BoxShadow(color: Color(0x0F2E2A20), blurRadius: 2, offset: Offset(0, 1)),
    BoxShadow(color: Color(0x0D2E2A20), blurRadius: 3, offset: Offset(0, 1)),
  ];
  static const shadow2 = [
    BoxShadow(color: Color(0x142E2A20), blurRadius: 14, offset: Offset(0, 4)),
    BoxShadow(color: Color(0x0A2E2A20), blurRadius: 4, offset: Offset(0, 2)),
  ];
  static const shadow3 = [
    BoxShadow(color: Color(0x1F2E2A20), blurRadius: 48, offset: Offset(0, 18)),
    BoxShadow(color: Color(0x0D2E2A20), blurRadius: 12, offset: Offset(0, 6)),
  ];

  // ---------- 复用样式 ----------
  /// 卡片：白底 + 1px 描边 + 一级阴影（Web `.card` 同款）。
  static BoxDecoration cardDeco({Color? color, double radius = rCard}) =>
      BoxDecoration(
        color: color ?? surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: line),
        boxShadow: shadow1,
      );

  /// 次要块：暖纸底、无阴影（Web `.panel` 用 surface-2）。
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

  /// 小标签：12px + 600（Web `.section-label`/`.lbl`）。
  static const tLabel = TextStyle(
    fontSize: fsXs,
    height: 1.4,
    color: text3,
    fontWeight: wStrong,
  );
}
