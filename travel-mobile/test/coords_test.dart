import 'package:flutter_test/flutter_test.dart';
import 'package:travel_mobile/core/coords.dart';

/// 基准向量与后端 `CoordinateTransformsTest.java` 同源（Python oracle），
/// 锁死「往返自洽」测不出的系数错误（如 BD-09 误用 pi 会偏约 27 米）。
void main() {
  const tolerance = 1e-6;

  void assertClose(LatLon actual, double lat, double lon) {
    expect(actual.lat, closeTo(lat, tolerance));
    expect(actual.lon, closeTo(lon, tolerance));
  }

  group('wgs84ToGcj02', () {
    test('matches python oracle', () {
      assertClose(wgs84ToGcj02(39.9087, 116.3975), 39.910103499, 116.403743573);
      assertClose(wgs84ToGcj02(30.259, 120.13), 30.256708300, 120.134758756);
      assertClose(wgs84ToGcj02(22.5431, 114.0579), 22.540382814, 114.063013999);
    });

    test('境外点不做偏移', () {
      assertClose(wgs84ToGcj02(35.6762, 139.6503), 35.6762, 139.6503);
    });
  });

  group('gcj02ToBd09 / bd09ToGcj02', () {
    test('往返误差小于 1 米', () {
      final bd = gcj02ToBd09(39.910103499, 116.403743573);
      assertClose(bd, 39.916442750, 116.410116586);
      final back = bd09ToGcj02(bd.lat, bd.lon);
      expect(
        haversineMeters(39.910103499, 116.403743573, back.lat, back.lon),
        lessThan(1.0),
      );
    });
  });

  group('toBd09 按标签换算', () {
    test('WGS-84 标签先转 GCJ 再转 BD', () {
      final bd = toBd09(39.9087, 116.3975, 'WGS-84');
      expect(bd, isNotNull);
      assertClose(bd!, 39.916442750, 116.410116586);
    });

    test('GCJ-02 标签直接换算', () {
      final bd = toBd09(39.910103499, 116.403743573, 'GCJ-02');
      assertClose(bd!, 39.916442750, 116.410116586);
    });

    test('BD-09 标签原样返回', () {
      final bd = toBd09(39.916442750, 116.410116586, 'BD-09LL');
      assertClose(bd!, 39.916442750, 116.410116586);
    });

    test('未知标签返回 null，绝不猜坐标系', () {
      expect(toBd09(39.9, 116.4, null), isNull);
      expect(toBd09(39.9, 116.4, 'MARS-1'), isNull);
      expect(toBd09(39.9, 116.4, ''), isNull);
    });
  });
}
