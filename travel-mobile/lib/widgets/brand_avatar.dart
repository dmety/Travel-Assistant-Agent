import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 助手头像：品牌刻石印的 28px 微缩。
///
/// 与 `design/brand-mark.svg` 同源。小尺寸只保留三样东西——
/// 右下 45° 切角、一道纸色等高线、赤陶落点；品牌标里那三道线
/// 在 28px 下会糊成一片，所以不外扩。
///
/// 路径坐标按 28×28 的 viewBox 抄自品牌标（原 64×64 等比压缩），
/// 改动须与 `planner.html` 里 `.msg.ai` 的 data URI 同步。
class BrandAvatar extends StatelessWidget {
  const BrandAvatar({super.key, this.size = 28});

  /// 视觉尺寸。默认 28，与气泡首行形成锚点。
  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _SealPainter(
          ink: T.pine,
          paper: T.paper,
          clay: T.clay,
        ),
      ),
    );
  }
}

class _SealPainter extends CustomPainter {
  const _SealPainter({
    required this.ink,
    required this.paper,
    required this.clay,
  });

  final Color ink;
  final Color paper;
  final Color clay;

  @override
  void paint(Canvas canvas, Size size) {
    final k = size.width / 28;
    Offset p(double x, double y) => Offset(x * k, y * k);

    // 印框：圆角方印，右下切一刀。四角和切角都是手绘路径，
    // 不能用 RRect —— 切角是品牌记忆点，圆角矩形会把它抹掉。
    final frame = Path()
      ..moveTo(6.2 * k, 1.5 * k)
      ..lineTo(21.8 * k, 1.5 * k)
      ..arcToPoint(p(26.5, 6.2), radius: Radius.circular(4.7 * k))
      ..lineTo(26.5 * k, 20.6 * k)
      ..lineTo(20.6 * k, 26.5 * k)
      ..lineTo(6.2 * k, 26.5 * k)
      ..arcToPoint(p(1.5, 21.8), radius: Radius.circular(4.7 * k))
      ..lineTo(1.5 * k, 6.2 * k)
      ..arcToPoint(p(6.2, 1.5), radius: Radius.circular(4.7 * k))
      ..close();

    canvas.drawPath(
      frame,
      Paint()
        ..color = ink
        ..isAntiAlias = true,
    );

    // 等高线：两道三次贝塞尔收成山形，线宽 2（放大到 64 时为 4.6）。
    final ridge = Path()
      ..moveTo(7.4 * k, 20.4 * k)
      ..cubicTo(9 * k, 14.8 * k, 11.3 * k, 11.6 * k, 14 * k, 11.6 * k)
      ..cubicTo(16.7 * k, 11.6 * k, 19 * k, 14.8 * k, 20.6 * k, 20.4 * k);

    canvas.drawPath(
      ridge,
      Paint()
        ..color = paper
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2 * k
        ..strokeCap = StrokeCap.round
        ..isAntiAlias = true,
    );

    // 落点：山径终点，赤陶实心。
    canvas.drawCircle(
      p(20.6, 20.4),
      2.1 * k,
      Paint()
        ..color = clay
        ..isAntiAlias = true,
    );
  }

  @override
  bool shouldRepaint(_SealPainter old) =>
      old.ink != ink || old.paper != paper || old.clay != clay;
}
