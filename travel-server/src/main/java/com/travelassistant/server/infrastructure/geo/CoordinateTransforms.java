package com.travelassistant.server.infrastructure.geo;

/**
 * 地理坐标系转换与球面距离，供餐饮锚点与地图 POI 对齐使用。
 *
 * <p>公式与 {@code tools/kb/models.py} 一致，Java 侧不另写一套口径。三处容易出错的点：
 * <ul>
 *   <li>BD-09 三角项系数是 {@code pi * 3000 / 180}，不是 {@code pi}；写错的往返仍然自洽，
 *       但 BD-09 转 GCJ-02 会偏约 27 m。回归测试用 Python 侧独立基准向量锁死，不接受
 *       「往返一致即正确」这种自证。</li>
 *   <li>境外点不做偏移，直接原样返回。</li>
 *   <li>GCJ-02 转 WGS-84 没有解析逆变换，用迭代逼近，迭代次数与 Python 侧相同。</li>
 * </ul>
 *
 * <p>所有入参顺序统一为 {@code (lat, lon)}，与 Python 侧一致；对外只暴露经纬度对，
 * 不引入额外依赖。
 */
public final class CoordinateTransforms {

  /** 平均地球半径（米），与 Haversine 常用口径一致。 */
  private static final double EARTH_RADIUS_METERS = 6371008.8;
  private static final double GCJ_A = 6378245.0;
  private static final double GCJ_EE = 0.00669342162296594323;
  private static final double X_PI = Math.PI * 3000.0 / 180.0;

  private CoordinateTransforms() {
  }

  /** 经纬度对，顺序 {@code (lat, lon)}。 */
  public record LatLon(double lat, double lon) {
  }

  /** 把带坐标系标签的点统一换算成 GCJ-02；无法识别的标签返回 null，绝不猜坐标系。 */
  public static LatLon toGcj02(double lat, double lon, String coordinateSystem) {
    if (coordinateSystem == null) {
      return null;
    }
    return switch (coordinateSystem.strip().toUpperCase(java.util.Locale.ROOT)) {
      case "GCJ-02", "GCJ02" -> new LatLon(lat, lon);
      case "WGS-84", "WGS84" -> wgs84ToGcj02(lat, lon);
      case "BD-09", "BD-09LL", "BD09", "BD09LL" -> bd09ToGcj02(lat, lon);
      default -> null;
    };
  }

  public static LatLon wgs84ToGcj02(double lat, double lon) {
    if (outOfChina(lat, lon)) {
      return new LatLon(lat, lon);
    }
    double dLat = transformLat(lon - 105.0, lat - 35.0);
    double dLon = transformLon(lon - 105.0, lat - 35.0);
    double radLat = lat / 180.0 * Math.PI;
    double sinLat = Math.sin(radLat);
    double magic = 1.0 - GCJ_EE * sinLat * sinLat;
    double sqrtMagic = Math.sqrt(magic);
    dLat = (dLat * 180.0) / ((GCJ_A * (1.0 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
    dLon = (dLon * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
    return new LatLon(lat + dLat, lon + dLon);
  }

  public static LatLon gcj02ToWgs84(double lat, double lon) {
    if (outOfChina(lat, lon)) {
      return new LatLon(lat, lon);
    }
    double wgsLat = lat;
    double wgsLon = lon;
    for (int i = 0; i < 10; i++) {
      LatLon converted = wgs84ToGcj02(wgsLat, wgsLon);
      double deltaLat = converted.lat() - lat;
      double deltaLon = converted.lon() - lon;
      wgsLat -= deltaLat;
      wgsLon -= deltaLon;
      if (Math.max(Math.abs(deltaLat), Math.abs(deltaLon)) < 1e-9) {
        break;
      }
    }
    return new LatLon(wgsLat, wgsLon);
  }

  public static LatLon bd09ToGcj02(double lat, double lon) {
    double x = lon - 0.0065;
    double y = lat - 0.006;
    double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
    double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
    // x 取自经度、y 取自纬度，故 z*sin(theta) 是纬度、z*cos(theta) 是经度。
    return new LatLon(z * Math.sin(theta), z * Math.cos(theta));
  }

  public static LatLon gcj02ToBd09(double lat, double lon) {
    double x = lon;
    double y = lat;
    double z = Math.sqrt(x * x + y * y) + 0.00002 * Math.sin(y * X_PI);
    double theta = Math.atan2(y, x) + 0.000003 * Math.cos(x * X_PI);
    return new LatLon(z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065);
  }

  /** 两点球面直线距离（米）。入参必须已经统一到同一坐标系。 */
  public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dLat = p2 - p1;
    double dLon = Math.toRadians(lon2 - lon1);
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1.0, h)));
  }

  private static boolean outOfChina(double lat, double lon) {
    return !(0.8293 <= lat && lat <= 55.8271 && 72.004 <= lon && lon <= 137.8347);
  }

  private static double transformLat(double x, double y) {
    double value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y;
    value += 0.2 * Math.sqrt(Math.abs(x));
    value += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
    value += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
    value += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
    return value;
  }

  private static double transformLon(double x, double y) {
    double value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y;
    value += 0.1 * Math.sqrt(Math.abs(x));
    value += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
    value += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
    value += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
    return value;
  }
}
