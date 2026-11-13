import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 规划页顶部的「行程地图」示意面板（对应 Web `planner.html` 的 `#viewPlan` 上半屏）。
///
/// 不接真实地图 SDK——与 Web 端一致，未注入 AK 时就是 SVG 示意底图，
/// 点位按当日顺序沿示意路线分布，明确标注「示意底图 · 非导航用」。
/// 当本次对话已产出草案时，按天数渲染 D1…Dn 的路线点位。
class MapPanel extends StatelessWidget {
  const MapPanel({
    super.key,
    this.height = 200,
    this.pinCount = 0,
    this.city,
  });

  final double height;
  final int pinCount;
  final String? city;

  @override
  Widget build(BuildContext context) {
    final hasPlan = pinCount > 0;
    return Container(
      height: height,
      margin: const EdgeInsets.fromLTRB(T.sp4, T.sp4, T.sp4, 0),
      decoration: BoxDecoration(
        color: T.surface2,
        borderRadius: BorderRadius.circular(T.rCard),
        border: Border.all(color: T.line),
        boxShadow: T.shadow1,
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        children: [
          // 底图：浅色等高线 + 一块水域，呼应品牌「等高线 / 方印」语言
          const _MapBackdrop(),
          // 路线与点位
          if (hasPlan) _RouteLayer(pinCount: pinCount),
          // 左上：城市标签
          Positioned(
            left: T.sp3,
            top: T.sp3,
            child: Container(
              padding: const EdgeInsets.symmetric(
                horizontal: T.sp3,
                vertical: T.sp1,
              ),
              decoration: BoxDecoration(
                color: T.surface,
                borderRadius: BorderRadius.circular(T.rPill),
                border: Border.all(color: T.lineStrong),
                boxShadow: T.shadow1,
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    hasPlan ? Icons.place_outlined : Icons.map_outlined,
                    size: 14,
                    color: T.primary,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    hasPlan ? (city ?? '行程地图') : '行程地图',
                    style: const TextStyle(
                      fontSize: T.fsXs,
                      height: 1.4,
                      color: T.ink,
                      fontWeight: T.wStrong,
                    ),
                  ),
                ],
              ),
            ),
          ),
          // 右上：地图形态徽标。这里永远是手绘示意底图，
          // 不写「未配置 AK」——AK 是后端服务侧配置，与本面板无关，
          // 写成那样会让人误以为是密钥没配好导致地图加载失败。
          Positioned(
            right: T.sp3,
            top: T.sp3,
            child: Container(
              padding: const EdgeInsets.symmetric(
                horizontal: T.sp2,
                vertical: T.sp1,
              ),
              decoration: BoxDecoration(
                color: T.bg,
                borderRadius: BorderRadius.circular(T.rPill),
                border: Border.all(color: T.line),
              ),
              child: const Text(
                '示意底图 · 非导航用',
                style: TextStyle(fontSize: 10, height: 1.3, color: T.text3),
              ),
            ),
          ),
          // 未生成草案时的居中占位
          if (!hasPlan)
            const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.map_outlined, size: 30, color: T.text3),
                  SizedBox(height: T.sp2),
                  Text('尚未生成草案', style: T.tLabel),
                  SizedBox(height: 2),
                  Text(
                    '在下方说出目的地，会自动标出路线',
                    style: TextStyle(fontSize: 10, height: 1.4, color: T.text3),
                  ),
                ],
              ),
            ),
          // 左下：图例
          Positioned(
            left: T.sp3,
            bottom: T.sp3,
            child: Container(
              padding: const EdgeInsets.symmetric(
                horizontal: T.sp2,
                vertical: T.sp1,
              ),
              decoration: BoxDecoration(
                color: T.surface,
                borderRadius: BorderRadius.circular(T.rCtl),
                border: Border.all(color: T.line),
                boxShadow: T.shadow1,
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _LegendDot(color: T.clay),
                  const SizedBox(width: 4),
                  const Text('路线', style: TextStyle(fontSize: 10, color: T.text2)),
                  const SizedBox(width: T.sp2),
                  _LegendDot(color: T.ok, hollow: true),
                  const SizedBox(width: 4),
                  const Text('点位', style: TextStyle(fontSize: 10, color: T.text2)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _LegendDot extends StatelessWidget {
  const _LegendDot({required this.color, this.hollow = false});
  final Color color;
  final bool hollow;
  @override
  Widget build(BuildContext context) {
    return Container(
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        color: hollow ? T.surface : color,
        shape: BoxShape.circle,
        border: Border.all(color: color, width: 2),
      ),
    );
  }
}

/// 浅色底图：等高线 + 一块水域，纯装饰，不承载地理信息。
class _MapBackdrop extends StatelessWidget {
  const _MapBackdrop();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (_, c) {
        final w = c.maxWidth;
        final h = c.maxHeight;
        return CustomPaint(
          size: Size(w, h),
          painter: _BackdropPainter(),
        );
      },
    );
  }
}

class _BackdropPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    // 等高线：由外向内收拢的三道弧（呼应品牌印框）
    final line = Paint()
      ..color = T.line
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    for (var i = 0; i < 3; i++) {
      final inset = 18.0 + i * 14;
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTRB(inset, inset, w - inset, h - inset),
          Radius.circular(20 - i * 4),
        ),
        line,
      );
    }
    // 一块水域：只占右下角，避免压住居中的「尚未生成草案」占位文案。
    // 纯装饰，不承载地理信息；真实地图接入后这一层会被替换掉。
    final water = Paint()..color = T.paper3;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(w * 0.82, h * 0.72, w * 0.16, h * 0.22),
        const Radius.circular(12),
      ),
      water,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter old) => false;
}

/// 路线 + 点位：D1…Dn 沿正弦示意路线分布。
class _RouteLayer extends StatelessWidget {
  const _RouteLayer({required this.pinCount});
  final int pinCount;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (_, c) {
        final w = c.maxWidth;
        final h = c.maxWidth == 0 ? 0.0 : c.maxHeight;
        final padX = 36.0;
        final usableW = w - padX * 2;
        final cy = h * 0.56;
        final amp = h * 0.16;
        final pos = <Offset>[];
        for (var i = 0; i < pinCount; i++) {
          final t = pinCount == 1 ? 0.5 : i / (pinCount - 1);
          final x = padX + t * usableW;
          final y = cy + amp * (i.isEven ? -1 : 1) * 0.8;
          pos.add(Offset(x, y));
        }
        return Stack(
          children: [
            CustomPaint(
              size: Size(w, h),
              painter: _RoutePainter(points: pos),
            ),
            for (var i = 0; i < pos.length; i++)
              Positioned(
                left: pos[i].dx - 11,
                top: pos[i].dy - 11,
                child: Container(
                  width: 22,
                  height: 22,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: T.primary,
                    shape: BoxShape.circle,
                    border: Border.all(color: T.surface, width: 2),
                    boxShadow: T.shadow1,
                  ),
                  child: Text(
                    'D${i + 1}',
                    style: const TextStyle(
                      fontSize: 9,
                      height: 1,
                      color: Colors.white,
                      fontWeight: T.wStrong,
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _RoutePainter extends CustomPainter {
  const _RoutePainter({required this.points});
  final List<Offset> points;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.length < 2) return;
    final paint = Paint()
      ..color = T.clay
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    final path = Path()..moveTo(points.first.dx, points.first.dy);
    for (var i = 1; i < points.length; i++) {
      // 用二阶曲线把相邻点连成柔和折线
      final p0 = points[i - 1];
      final p1 = points[i];
      final mid = Offset((p0.dx + p1.dx) / 2, (p0.dy + p1.dy) / 2);
      path.quadraticBezierTo(p0.dx, p0.dy, mid.dx, mid.dy);
    }
    path.lineTo(points.last.dx, points.last.dy);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter old) => false;
}
