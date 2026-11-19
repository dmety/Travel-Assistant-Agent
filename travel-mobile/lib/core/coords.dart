/// 地理坐标系转换（WGS-84 / GCJ-02 / BD-09），供真地图点位渲染使用。
///
/// 公式与后端 `CoordinateTransforms.java`、`tools/kb/models.py` 完全同一口径：
/// - BD-09 三角项系数是 `pi * 3000 / 180`（误用 `pi` 会偏约 27 米）；
/// - 境外点不做偏移，原样返回；
/// - `toBd09` 按 KB 的 `coordinateSystem` 标签换算，无法识别的标签返回 null，
///   绝不猜坐标系（与后端 `toGcj02` 同一纪律）。
library;

import 'dart:math' as math;

const double _earthRadiusMeters = 6371008.8;
const double _gcjA = 6378245.0;
const double _gcjEe = 0.00669342162296594323;
const double _xPi = math.pi * 3000.0 / 180.0;

/// 经纬度对，顺序 (lat, lon)。
class LatLon {
  const LatLon(this.lat, this.lon);

  final double lat;
  final double lon;
}

/// 按 KB 坐标系标签换算成 BD-09LL（百度地图 SDK 使用的坐标系）。
///
/// 标签取 `WGS-84` / `GCJ-02` / `BD-09` 等大小写与连字符变体；
/// 无法识别时返回 null，调用方应跳过该点而不是猜。
LatLon? toBd09(double lat, double lon, String? coordinateSystem) {
  if (coordinateSystem == null) return null;
  final label = coordinateSystem.trim().toUpperCase();
  switch (label) {
    case 'BD-09':
    case 'BD-09LL':
    case 'BD09':
    case 'BD09LL':
      return LatLon(lat, lon);
    case 'GCJ-02':
    case 'GCJ02':
      return gcj02ToBd09(lat, lon);
    case 'WGS-84':
    case 'WGS84':
      final gcj = wgs84ToGcj02(lat, lon);
      return gcj02ToBd09(gcj.lat, gcj.lon);
    default:
      return null;
  }
}

LatLon wgs84ToGcj02(double lat, double lon) {
  if (_outOfChina(lat, lon)) return LatLon(lat, lon);
  var dLat = _transformLat(lon - 105.0, lat - 35.0);
  var dLon = _transformLon(lon - 105.0, lat - 35.0);
  final radLat = lat / 180.0 * math.pi;
  final sinLat = math.sin(radLat);
  final magic = 1.0 - _gcjEe * sinLat * sinLat;
  final sqrtMagic = math.sqrt(magic);
  dLat = (dLat * 180.0) / ((_gcjA * (1.0 - _gcjEe)) / (magic * sqrtMagic) * math.pi);
  dLon = (dLon * 180.0) / (_gcjA / sqrtMagic * math.cos(radLat) * math.pi);
  return LatLon(lat + dLat, lon + dLon);
}

LatLon gcj02ToBd09(double lat, double lon) {
  final x = lon;
  final y = lat;
  final z = math.sqrt(x * x + y * y) + 0.00002 * math.sin(y * _xPi);
  final theta = math.atan2(y, x) + 0.000003 * math.cos(x * _xPi);
  return LatLon(z * math.sin(theta) + 0.006, z * math.cos(theta) + 0.0065);
}

LatLon bd09ToGcj02(double lat, double lon) {
  final x = lon - 0.0065;
  final y = lat - 0.006;
  final z = math.sqrt(x * x + y * y) - 0.00002 * math.sin(y * _xPi);
  final theta = math.atan2(y, x) - 0.000003 * math.cos(x * _xPi);
  return LatLon(z * math.sin(theta), z * math.cos(theta));
}

/// 两点球面直线距离（米）。入参必须已统一到同一坐标系。
double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
  final p1 = lat1 * math.pi / 180.0;
  final p2 = lat2 * math.pi / 180.0;
  final dLat = p2 - p1;
  final dLon = (lon2 - lon1) * math.pi / 180.0;
  final h = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(p1) * math.cos(p2) * math.sin(dLon / 2) * math.sin(dLon / 2);
  return 2 * _earthRadiusMeters * math.asin(math.sqrt(math.min(1.0, h)));
}

bool _outOfChina(double lat, double lon) =>
    !(0.8293 <= lat && lat <= 55.8271 && 72.004 <= lon && lon <= 137.8347);

double _transformLat(double x, double y) {
  var value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y;
  value += 0.2 * math.sqrt(x.abs());
  value +=
      (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) *
          2.0 /
          3.0;
  value +=
      (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0;
  value += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) *
      2.0 /
      3.0;
  return value;
}

double _transformLon(double x, double y) {
  var value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y;
  value += 0.1 * math.sqrt(x.abs());
  value +=
      (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) *
          2.0 /
          3.0;
  value +=
      (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0;
  value += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) *
      2.0 /
      3.0;
  return value;
}
