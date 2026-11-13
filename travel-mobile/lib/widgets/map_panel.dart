import 'package:flutter/material.dart';
import 'package:flutter_baidu_mapapi_base/flutter_baidu_mapapi_base.dart';
import 'package:flutter_baidu_mapapi_map/flutter_baidu_mapapi_map.dart';

import '../api/client.dart';
import '../config/map_config.dart';
import '../core/coords.dart';
import '../theme/tokens.dart';

/// 真地图点位请求：由日程条目聚合成 `name + city`，运行时反查坐标。
class MapPinRequest {
  const MapPinRequest({
    required this.name,
    required this.city,
    this.dayIndex,
  });
  final String name;
  final String city;
  final int? dayIndex;
}

/// 规划页顶部的「行程地图」面板。
///
/// - 配置了百度 Android AK（`--dart-define=BAIDU_MAP_AK`，同源见 [baiduMapAk]）
///   且能反查出坐标时，渲染真实百度地图，按日程顺序标点 + 连路线；
/// - 否则（未配置 AK / 网络或坐标缺失）一律降级为 SVG 示意底图，
///   明确标注「示意底图 · 非导航用」，绝不编造地理信息。
class MapPanel extends StatelessWidget {
  const MapPanel({
    super.key,
    this.height = 200,
    this.pinCount = 0,
    this.city,
    this.pins = const [],
    this.client = ApiClient.instance,
  });

  final double height;
  final int pinCount;
  final String? city;
  final List<MapPinRequest> pins;
  final ApiClient client;

  @override
  Widget build(BuildContext context) {
    if (baiduMapAk.isEmpty) {
      return _SchematicMap(height: height, pinCount: pinCount, city: city);
    }
    return _RealMap(
      height: height,
      pins: pins,
      city: city,
      client: client,
    );
  }
}

/// 真地图：百度地图 SDK 的 [BMFMapWidget]，点位由 [MapPinRequest] 反查坐标后落图。
///
/// 若全部点位都查不到坐标（网络/鉴权失败或库里没有该地名），回落到 SVG 示意底图——
/// 与「未配置 AK」保持同一套降级视觉，不会出现半截空白地图。
class _RealMap extends StatefulWidget {
  const _RealMap({
    required this.height,
    required this.pins,
    this.city,
    required this.client,
  });

  final double height;
  final List<MapPinRequest> pins;
  final String? city;
  final ApiClient client;

  @override
  State<_RealMap> createState() => _RealMapState();
}

class _ResolvedPin {
  const _ResolvedPin(this.lat, this.lon, this.title);
  final double lat;
  final double lon;
  final String title;
}

class _RealMapState extends State<_RealMap> {
  List<_ResolvedPin> _resolved = const [];
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  Future<void> _resolve() async {
    final out = <_ResolvedPin>[];
    for (final p in widget.pins) {
      final bd = await _lookup(p);
      if (bd != null) out.add(_ResolvedPin(bd.lat, bd.lon, p.name));
    }
    if (!mounted) return;
    setState(() {
      _resolved = out;
      _ready = true;
    });
  }

  /// 反查坐标：优先精确/包含匹配，统一换算到 BD-09LL（百度 SDK 坐标系）。
  /// 任何异常都返回 null——坐标缺失就跳过该点，绝不猜、不编造。
  Future<LatLon?> _lookup(MapPinRequest p) async {
    try {
      final data = await widget.client.attractions(
        city: p.city,
        name: p.name,
        limit: 5,
      );
      final items = _asItems(data);
      if (items.isEmpty) return null;
      Map<String, dynamic>? hit;
      for (final it in items) {
        final n = (it['name'] ?? '').toString();
        if (n == p.name || n.contains(p.name)) {
          hit = it;
          break;
        }
      }
      hit ??= items.first;
      final lat = _toDouble(hit['lat']);
      final lon = _toDouble(hit['lon']);
      final cs = hit['coordinateSystem']?.toString();
      if (lat == null || lon == null) return null;
      return toBd09(lat, lon, cs);
    } catch (_) {
      return null;
    }
  }

  static List<Map<String, dynamic>> _asItems(dynamic data) {
    if (data is Map) {
      final items = data['items'];
      if (items is List) {
        return items
            .whereType<Map>()
            .map((e) => Map<String, dynamic>.from(e))
            .toList();
      }
    }
    if (data is List) {
      return data
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .toList();
    }
    return [];
  }

  static double? _toDouble(dynamic v) {
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v);
    return null;
  }

  void _onCreated(BMFMapController c) {
    if (_resolved.isEmpty) return;
    // ignore: deprecated_member_use
    c.addMarkers(
      _resolved
          .map((r) => BMFMarker(
                position: BMFCoordinate(r.lat, r.lon),
                title: r.title,
              ))
          .toList(),
    );
    if (_resolved.length > 1) {
      c.addPolyline(
        BMFPolyline(
          coordinates: _resolved
              .map((r) => BMFCoordinate(r.lat, r.lon))
              .toList(),
          width: 5,
          colors: const [Color(0xFFC8632E)],
        ),
      );
    }
    c.setNewLatLngZoom(
      coordinate: BMFCoordinate(_resolved.first.lat, _resolved.first.lon),
      zoom: 12,
    );
  }

  @override
  Widget build(BuildContext context) {
    // 解析中或解析后无可用坐标：降级示意底图
    if (!_ready || _resolved.isEmpty) {
      return _SchematicMap(
        height: widget.height,
        pinCount: widget.pins.length,
        city: widget.city,
      );
    }
    return SizedBox(
      height: widget.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(T.rCard),
        child: Stack(
          children: [
            BMFMapWidget(onBMFMapCreated: _onCreated),
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
                    const Icon(Icons.place_outlined, size: 14, color: T.primary),
                    const SizedBox(width: 4),
                    Text(
                      widget.city ?? '行程地图',
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
            // 右上：真地图形态徽标
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
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.map, size: 12, color: T.text2),
                    SizedBox(width: 3),
                    Text(
                      '真地图 · 百度',
                      style: TextStyle(fontSize: 10, height: 1.3, color: T.text3),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// SVG 示意底图（降级用）：浅色等高线 + 水域，点位按当日顺序沿示意路线分布。
/// 不承载真实地理信息。
class _SchematicMap extends StatelessWidget {
  const _SchematicMap({
    required this.height,
    required this.pinCount,
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
          const _MapBackdrop(),
          if (hasPlan) _RouteLayer(pinCount: pinCount),
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
