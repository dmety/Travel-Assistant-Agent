package com.travelassistant.server.infrastructure.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.infrastructure.geo.CoordinateTransforms.LatLon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 转换回归：期望值来自 tools/kb/models.py 的独立实现（该实现已由
 * kb.test_coordinates 用标准公式校验），不是本类自己往返算出来的。
 */
@DisplayName("坐标系转换与球面距离")
class CoordinateTransformsTest {

  private static final double TOLERANCE = 1e-7;

  private static void assertClose(LatLon actual, double lat, double lon) {
    assertThat(actual.lat()).isCloseTo(lat, org.assertj.core.data.Offset.offset(TOLERANCE));
    assertThat(actual.lon()).isCloseTo(lon, org.assertj.core.data.Offset.offset(TOLERANCE));
  }

  @Test
  @DisplayName("WGS-84 转 GCJ-02 锁死三个城市的独立基准向量")
  void wgs84ToGcj02MatchesPythonOracle() {
    assertClose(CoordinateTransforms.wgs84ToGcj02(39.9087, 116.3975),
        39.910103499, 116.403743573);
    assertClose(CoordinateTransforms.wgs84ToGcj02(30.259, 120.13),
        30.256708300, 120.134758756);
    assertClose(CoordinateTransforms.wgs84ToGcj02(22.5431, 114.0579),
        22.540382814, 114.063013999);
  }

  @Test
  @DisplayName("BD-09LL 转 GCJ-02 用 X_PI 系数，误用 pi 会偏约 27 米")
  void bd09ToGcj02UsesCorrectTrigCoefficient() {
    LatLon good = CoordinateTransforms.bd09ToGcj02(39.915, 116.404);
    assertClose(good, 39.908656740, 116.397627291);

    // 故意用 Math.PI 复现历史 bug，证明往返自洽不能发现它、而基准向量能。
    double x = 116.404 - 0.0065;
    double y = 39.915 - 0.006;
    double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI);
    double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI);
    LatLon wrong = new LatLon(z * Math.sin(theta), z * Math.cos(theta));
    assertThat(CoordinateTransforms.haversineMeters(
        good.lat(), good.lon(), wrong.lat(), wrong.lon()))
        .isGreaterThan(20.0).isLessThan(35.0);
  }

  @Test
  @DisplayName("GCJ-02 转 BD-09LL 与 Python 基准一致，往返误差小于 1 米")
  void bd09RoundTripIsSubMeter() {
    LatLon bd = CoordinateTransforms.gcj02ToBd09(39.910103499, 116.403743573);
    assertClose(bd, 39.916442750, 116.410116586);
    LatLon back = CoordinateTransforms.bd09ToGcj02(bd.lat(), bd.lon());
    assertThat(CoordinateTransforms.haversineMeters(
        39.910103499, 116.403743573, back.lat(), back.lon())).isLessThan(1.0);
  }

  @Test
  @DisplayName("GCJ-02 迭代逆变换回到 WGS-84")
  void gcj02ToWgs84MatchesPythonOracle() {
    assertClose(CoordinateTransforms.gcj02ToWgs84(39.915, 116.404),
        39.913596236, 116.397755755);
    LatLon back = CoordinateTransforms.wgs84ToGcj02(39.913596236, 116.397755755);
    assertClose(back, 39.915, 116.404);
  }

  @Test
  @DisplayName("境外点不做偏移")
  void outOfChinaIsUntouched() {
    assertClose(CoordinateTransforms.wgs84ToGcj02(35.0, 139.0), 35.0, 139.0);
    assertClose(CoordinateTransforms.gcj02ToWgs84(35.0, 139.0), 35.0, 139.0);
  }

  @Test
  @DisplayName("带标签的点统一到 GCJ-02；未知或空标签返回 null，不猜")
  void normalizesByCoordinateSystemLabel() {
    assertClose(CoordinateTransforms.toGcj02(39.9087, 116.3975, "WGS-84"),
        39.910103499, 116.403743573);
    assertClose(CoordinateTransforms.toGcj02(39.915, 116.404, "BD-09LL"),
        39.908656740, 116.397627291);
    assertClose(CoordinateTransforms.toGcj02(30.259, 120.13, " gcj-02 "), 30.259, 120.13);
    assertThat(CoordinateTransforms.toGcj02(30.259, 120.13, null)).isNull();
    assertThat(CoordinateTransforms.toGcj02(30.259, 120.13, "UNKNOWN")).isNull();
  }

  @Test
  @DisplayName("Haversine 与独立公式一致，同点为零且对称")
  void haversineMatchesIndependentFormula() {
    assertThat(CoordinateTransforms.haversineMeters(30.259, 120.13, 30.260, 120.131))
        .isCloseTo(146.931896, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(CoordinateTransforms.haversineMeters(30.259, 120.13, 30.259, 120.13))
        .isZero();
    assertThat(CoordinateTransforms.haversineMeters(30.259, 120.13, 30.260, 120.131))
        .isEqualTo(CoordinateTransforms.haversineMeters(30.260, 120.131, 30.259, 120.13));
  }
}
