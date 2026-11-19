import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 品牌印章「出逃计划」：圆角方印 + 右下 45° 斜切 + 三道收拢的等高线 +
/// 一条赤陶山径（纸色刻槽 + 赤陶细线）+ 起点空心环 / 终点实心落点。
///
/// 复刻 `design/mobile.html` 的 `mark-full` 符号（viewBox 64×64 自适应），
/// 移动端登录英雄区与导航头像复用，保证三端品牌一致。
class BrandMark extends StatelessWidget {
  const BrandMark({super.key, this.size = 64, this.tile = false});

  final double size;
  /// 是否包一层暖底圆角 tile（登录英雄区用）。
  final bool tile;

  @override
  Widget build(BuildContext context) {
    final mark = SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: const _MarkPainter()),
    );
    if (!tile) return mark;
    return Container(
      width: size + 20,
      height: size + 20,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: T.pineSoft,
        borderRadius: BorderRadius.circular(T.rCard),
        border: Border.all(color: T.pineLine),
      ),
      child: mark,
    );
  }
}

class _MarkPainter extends CustomPainter {
  const _MarkPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.shortestSide / 64;
    canvas.save();
    canvas.scale(s, s);

    // 印框：圆角方印，右下 45° 斜切
    final stamp = Path()
      ..moveTo(15, 3.5)
      ..lineTo(49, 3.5)
      ..arcToPoint(const Offset(60.5, 15), radius: const Radius.circular(11.5))
      ..lineTo(60.5, 40)
      ..lineTo(40, 60.5)
      ..lineTo(15, 60.5)
      ..arcToPoint(const Offset(3.5, 49), radius: const Radius.circular(11.5))
      ..lineTo(3.5, 15)
      ..arcToPoint(const Offset(15, 3.5), radius: const Radius.circular(11.5))
      ..close();
    canvas.drawPath(
      stamp,
      Paint()
        ..color = T.paper
        ..style = PaintingStyle.fill,
    );
    canvas.drawPath(
      stamp,
      Paint()
        ..color = T.pine
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3.2
        ..strokeJoin = StrokeJoin.round,
    );

    // 切口处的第二道刻线
    _stroke(
      canvas,
      'M44.5 55.5 L55.5 44.5',
      T.pine,
      strokeWidth: 1.3,
      alpha: .45,
    );

    // 三道等高线：由外向内收拢，线宽与透明度递减
    _stroke(canvas, 'M11 48 C14 32 22 21 31 21 C40 21 48 32 51 48', T.pine,
        strokeWidth: 2.7);
    _stroke(canvas, 'M18 48 C20 37 25 30 31 30 C37 30 42 37 44 48', T.pine,
        strokeWidth: 2.2, alpha: .82);
    _stroke(canvas, 'M25 48 C26 42 28 38 31 38 C34 38 36 42 37 48', T.pine,
        strokeWidth: 1.8, alpha: .62);

    // 山径：先纸色刻槽，再赤陶细线
    const ridge = 'M14 16 L22 24 L18 33 L30 38 L40 48';
    _stroke(canvas, ridge, T.paper, strokeWidth: 7, strokeCap: StrokeCap.round);
    _stroke(canvas, ridge, T.clay, strokeWidth: 3, strokeCap: StrokeCap.round);

    // 起点：空心小环
    _dot(canvas, 14, 16, 2.5, fill: T.paper, stroke: T.clay, strokeWidth: 1.8);
    // 终点：实心落点 + 纸色内点
    _dot(canvas, 40, 48, 4.6, fill: T.clay, stroke: null);
    _dot(canvas, 40, 48, 1.6, fill: T.paper, stroke: null);

    canvas.restore();
  }

  void _stroke(Canvas canvas, String svgPath, Color color,
      {double strokeWidth = 1,
      double alpha = 1,
      StrokeCap strokeCap = StrokeCap.round}) {
    final p = _parsePath(svgPath);
    if (p == null) return;
    canvas.drawPath(
      p,
      Paint()
        ..color = color.withOpacity(alpha)
        ..style = PaintingStyle.stroke
        ..strokeWidth = strokeWidth
        ..strokeCap = strokeCap
        ..strokeJoin = StrokeJoin.round,
    );
  }

  void _dot(Canvas canvas, double cx, double cy, double r,
      {required Color fill, Color? stroke, double strokeWidth = 0}) {
    if (stroke != null) {
      canvas.drawCircle(
        Offset(cx, cy),
        r,
        Paint()
          ..color = stroke
          ..style = PaintingStyle.stroke
          ..strokeWidth = strokeWidth,
      );
    }
    canvas.drawCircle(
      Offset(cx, cy),
      r - (stroke != null ? strokeWidth : 0),
      Paint()..color = fill,
    );
  }

  /// 只支持本组件用到的「M / L / C」三段式路径（设计稿 mark 仅此一种写法）。
  Path? _parsePath(String d) {
    final path = Path();
    final tokens = d.split(RegExp(r'[ ,]+')).where((e) => e.isNotEmpty).toList();
    var i = 0;
    while (i < tokens.length) {
      final cmd = tokens[i++];
      switch (cmd) {
        case 'M':
          path.moveTo(_num(tokens, i), _num(tokens, i + 1));
          i += 2;
        case 'L':
          path.lineTo(_num(tokens, i), _num(tokens, i + 1));
          i += 2;
        case 'C':
          path.cubicTo(
            _num(tokens, i),
            _num(tokens, i + 1),
            _num(tokens, i + 2),
            _num(tokens, i + 3),
            _num(tokens, i + 4),
            _num(tokens, i + 5),
          );
          i += 6;
        default:
          return null;
      }
    }
    return path;
  }

  double _num(List<String> t, int idx) => double.tryParse(t[idx]) ?? 0;

  @override
  bool shouldRepaint(covariant CustomPainter old) => false;
}
