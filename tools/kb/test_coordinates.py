# -*- coding: utf-8 -*-
"""BD-09LL / GCJ-02 / WGS-84 坐标转换回归测试，零依赖。

运行：PYTHONPATH="tools;$PYTHONPATH" python -B -m unittest kb.test_coordinates -v
由 tools/gate.sh 调用。

独立参考实现按 BD-09 公开定义重写一遍，用来锁住 x_pi = pi * 3000 / 180。
只测往返自洽发现不了系数错误：正反函数互为镜像，误用 pi 时往返反而更精确。
BD-09 正逆变换不是严格解析互逆，标准公式往返残差约 1e-6 度（分米级），
故往返容差取 5e-6；系数正确性由独立 oracle 对照（1e-9）锁定。
BD 层不做境外判定，境外 POI 需调用方先按 _out_of_china 判断。
"""
import math
import unittest

from kb import models

X_PI = math.pi * 3000.0 / 180.0

# 境内多点，避免单点碰巧一致；数值是各城市中心的近似坐标，仅作输入。
POINTS = (
    (39.915152, 116.403954),  # 北京
    (31.231270, 121.470625),  # 上海
    (22.543096, 114.057865),  # 深圳
    (30.592759, 104.063271),  # 成都
    (23.129163, 113.264435),  # 广州
)


def ref_bd_to_gcj(lat, lon):
    x = lon - 0.0065
    y = lat - 0.006
    z = math.sqrt(x * x + y * y) - 0.00002 * math.sin(y * X_PI)
    theta = math.atan2(y, x) - 0.000003 * math.cos(x * X_PI)
    return z * math.sin(theta), z * math.cos(theta)


def ref_gcj_to_bd(lat, lon):
    x, y = lon, lat
    z = math.sqrt(x * x + y * y) + 0.00002 * math.sin(y * X_PI)
    theta = math.atan2(y, x) + 0.000003 * math.cos(x * X_PI)
    return z * math.sin(theta) + 0.006, z * math.cos(theta) + 0.0065


class CoordinateConversionTest(unittest.TestCase):
    def assert_close(self, actual, expected, tol=1e-9, msg=""):
        self.assertEqual(len(actual), 2, msg)
        self.assertAlmostEqual(actual[0], expected[0], delta=tol, msg=msg)
        self.assertAlmostEqual(actual[1], expected[1], delta=tol, msg=msg)

    def test_bd_to_gcj_matches_standard_formula(self):
        for point in POINTS:
            with self.subTest(point=point):
                self.assert_close(
                    models.bd09_to_gcj02(*point), ref_bd_to_gcj(*point),
                    msg="BD->GCJ 必须用 x_pi，改成 math.pi 会偏数十米",
                )

    def test_gcj_to_bd_matches_standard_formula(self):
        for point in POINTS:
            with self.subTest(point=point):
                self.assert_close(
                    models.gcj02_to_bd09(*point), ref_gcj_to_bd(*point),
                    msg="GCJ->BD 必须用 x_pi",
                )

    def test_bd_to_wgs_uses_standard_bd_to_gcj(self):
        for point in POINTS:
            with self.subTest(point=point):
                expected = models.gcj02_to_wgs84(*ref_bd_to_gcj(*point))
                self.assert_close(models.bd09_to_wgs84(*point), expected, tol=1e-6)

    def test_wgs_to_bd_uses_standard_gcj_to_bd(self):
        for point in POINTS:
            with self.subTest(point=point):
                gcj = models.wgs84_to_gcj02(*point)
                self.assert_close(models.wgs84_to_bd09(*point), ref_gcj_to_bd(*gcj))

    def test_bd_gcj_bd_round_trip(self):
        for point in POINTS:
            with self.subTest(point=point):
                back = models.gcj02_to_bd09(*models.bd09_to_gcj02(*point))
                self.assert_close(back, point, tol=5e-6)

    def test_bd_wgs_bd_round_trip(self):
        for point in POINTS:
            with self.subTest(point=point):
                back = models.wgs84_to_bd09(*models.bd09_to_wgs84(*point))
                self.assert_close(back, point, tol=5e-6)

    def test_wgs_bd_wgs_round_trip(self):
        for point in POINTS:
            with self.subTest(point=point):
                back = models.bd09_to_wgs84(*models.wgs84_to_bd09(*point))
                self.assert_close(back, point, tol=5e-6)

    def test_bd_to_wgs_to_gcj_matches_direct_bd_to_gcj(self):
        for point in POINTS:
            with self.subTest(point=point):
                via_wgs = models.wgs84_to_gcj02(*models.bd09_to_wgs84(*point))
                self.assert_close(via_wgs, models.bd09_to_gcj02(*point), tol=1e-6)

    def test_out_of_china_passthrough(self):
        tokyo = (35.6895, 139.6917)
        self.assert_close(models.wgs84_to_gcj02(*tokyo), tokyo)
        self.assert_close(models.gcj02_to_wgs84(*tokyo), tokyo)

    def test_signature_is_lat_lon_tuple_of_floats(self):
        out = models.bd09_to_gcj02("39.915152", "116.403954")
        self.assertIsInstance(out, tuple)
        self.assertTrue(all(isinstance(v, float) for v in out))
        self.assert_close(out, ref_bd_to_gcj(39.915152, 116.403954))


if __name__ == "__main__":
    unittest.main()
