"""posture_geometry 单测，用例与 Android 端 PostureGeometryTest / PostureAnalyzerTest 一一对应。

运行：cd tools && .venv/Scripts/python -m unittest test_geometry -v
"""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import posture_geometry as pg  # noqa: E402
from posture_geometry import (  # noqa: E402
    BAD, GOOD, INVALID, LANDMARK_COUNT, LEFT_EAR, LEFT_HIP, LEFT_SHOULDER, LOW_VISIBILITY, MISALIGNED,
    NO_PERSON, NO_TORSO, REF_TORSO, REF_VERTICAL, RIGHT_EAR, RIGHT_HIP, RIGHT_SHOULDER, VALID,
    AnalyzerConfig, GeometryConfig, Landmark, Measurement, PostureAnalyzer, analyze, angle_between,
    inclination_from_vertical, median, pick_side, threshold_for,
)


def blank_landmarks():
    return [Landmark(0.0, 0.0, 0.0) for _ in range(LANDMARK_COUNT)]


def side_view(ear_x, ear_y, sh_x, sh_y, hip_x="same", hip_y="same", far_x=None, far_vis=0.9, side="LEFT"):
    lms = blank_landmarks()
    if side == "LEFT":
        ear, sh, far, hip = LEFT_EAR, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP
    else:
        ear, sh, far, hip = RIGHT_EAR, RIGHT_SHOULDER, LEFT_SHOULDER, RIGHT_HIP
    if hip_x == "same":
        hip_x = sh_x
    if hip_y == "same":
        hip_y = sh_y + 0.3
    if far_x is None:
        far_x = sh_x
    lms[ear] = Landmark(ear_x, ear_y, 0.95)
    lms[sh] = Landmark(sh_x, sh_y, 0.95)
    lms[far] = Landmark(far_x, sh_y, far_vis)
    if hip_x is not None and hip_y is not None:
        lms[hip] = Landmark(hip_x, hip_y, 0.9)
    return lms


class GeometryTest(unittest.TestCase):

    def test_inclination_zero_when_ear_above_shoulder(self):
        self.assertAlmostEqual(inclination_from_vertical((100, 200), (100, 100)), 0.0, places=3)

    def test_inclination_90_when_level(self):
        self.assertAlmostEqual(inclination_from_vertical((100, 200), (200, 200)), 90.0, places=3)

    def test_inclination_45_on_diagonal(self):
        self.assertAlmostEqual(inclination_from_vertical((0, 100), (100, 0)), 45.0, places=3)

    def test_inclination_symmetric_under_mirror(self):
        left = inclination_from_vertical((0, 100), (-60, 20))
        right = inclination_from_vertical((0, 100), (60, 20))
        self.assertAlmostEqual(left, right, places=4)

    def test_analyze_uses_pixel_coordinates(self):
        # 归一化 dx=0.1, dy=0.1 看似 45 度，640x480 下实际 dx=64, dy=48。
        # 默认髋在肩正下方，躯干竖直，所以相对躯干线与相对竖直线数值相同。
        lms = side_view(0.6, 0.4, 0.5, 0.5)
        status, m = analyze(lms, 640, 480)
        self.assertEqual(status, VALID)
        self.assertAlmostEqual(m.neck_deg, math.degrees(math.atan2(64, 48)), places=2)
        self.assertEqual(m.neck_reference, REF_TORSO)

    def test_angle_between_collinear_and_perpendicular(self):
        self.assertAlmostEqual(angle_between((0, 0), (10, 0), (0, 0), (10, 0)), 0.0, places=3)
        self.assertAlmostEqual(angle_between((0, 0), (10, 0), (0, 0), (0, -10)), 90.0, places=3)
        self.assertAlmostEqual(angle_between((0, 0), (10, 0), (10, 0), (0, 0)), 180.0, places=3)

    def test_angle_between_zero_for_degenerate_vector(self):
        self.assertAlmostEqual(angle_between((5, 5), (5, 5), (0, 0), (1, 1)), 0.0, places=3)

    def test_analyze_neck_zero_when_lying_down(self):
        # 躺平：髋、肩、耳共线，头没有相对身体前伸
        lms = side_view(0.7, 0.5, 0.5, 0.5, hip_x=0.2, hip_y=0.5)
        status, m = analyze(lms, 400, 400)
        self.assertEqual(status, VALID)
        self.assertAlmostEqual(m.neck_deg, 0.0, places=2)
        # 旧口径（相对竖直线）在这里是 90 度，正是误报的来源
        self.assertAlmostEqual(m.torso_deg, 90.0, places=2)

    def test_analyze_neck_subtracts_torso_lean(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, hip_x=0.7, hip_y=0.7)
        status, m = analyze(lms, 100, 100)
        self.assertEqual(status, VALID)
        self.assertAlmostEqual(m.neck_deg, 45.0, places=2)
        self.assertAlmostEqual(m.torso_deg, 45.0, places=2)

    def test_analyze_falls_back_to_vertical_when_require_hip_disabled(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, hip_x=None, hip_y=None)
        status, m = analyze(lms, 100, 100, GeometryConfig(require_hip=False))
        self.assertEqual(status, VALID)
        self.assertEqual(m.neck_reference, REF_VERTICAL)

    def test_analyze_no_torso_when_hip_too_close_to_shoulder(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, hip_x=0.5, hip_y=0.52)
        status, m = analyze(lms, 100, 100)
        self.assertEqual(status, NO_TORSO)
        self.assertEqual(m.neck_reference, REF_VERTICAL)

    def test_analyze_picks_right_side(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, side="RIGHT")
        status, m = analyze(lms, 100, 100)
        self.assertEqual(status, VALID)
        self.assertEqual(m.side, "RIGHT")

    def test_side_score_is_weakest_of_chain(self):
        """选侧看整条链最弱的一点，不是只看耳朵（v0.7）。"""
        lms = blank_landmarks()
        lms[LEFT_EAR] = Landmark(0.4, 0.3, 0.91)
        lms[LEFT_SHOULDER] = Landmark(0.4, 0.5, 0.54)
        lms[LEFT_HIP] = Landmark(0.4, 0.8, 0.52)
        lms[RIGHT_EAR] = Landmark(0.6, 0.3, 0.82)
        lms[RIGHT_SHOULDER] = Landmark(0.6, 0.5, 0.94)
        lms[RIGHT_HIP] = Landmark(0.6, 0.8, 0.96)
        self.assertAlmostEqual(pg.side_score(lms, "LEFT")[1], 0.52, places=6)
        self.assertAlmostEqual(pg.side_score(lms, "RIGHT")[1], 0.82, places=6)
        # 旧逻辑只比耳朵会选左（0.91 > 0.82），新逻辑选整条链更稳的右侧
        self.assertEqual(pick_side(lms), "RIGHT")

    def test_pick_side_falls_back_to_neck_chain_when_both_hips_hidden(self):
        """髋被桌子挡住时两侧含髋得分都接近 0，此时改比耳肩而不是掷硬币。"""
        lms = blank_landmarks()
        lms[LEFT_EAR] = Landmark(0.4, 0.3, 0.95)
        lms[LEFT_SHOULDER] = Landmark(0.4, 0.5, 0.95)
        lms[LEFT_HIP] = Landmark(0.4, 0.8, 0.05)
        lms[RIGHT_EAR] = Landmark(0.6, 0.3, 0.30)
        lms[RIGHT_SHOULDER] = Landmark(0.6, 0.5, 0.30)
        lms[RIGHT_HIP] = Landmark(0.6, 0.8, 0.04)
        # 含髋得分 0.05 vs 0.04 几乎无区分度；耳肩得分 0.95 vs 0.30 差距明确
        self.assertEqual(pick_side(lms), "LEFT")
        # 粘性同样生效：右侧耳肩差得多，在用左侧时不会被拉走
        self.assertEqual(pick_side(lms, "LEFT"), "LEFT")

    def test_pick_side_uses_hip_when_one_side_has_it(self):
        """只要有一侧的髋可用，就仍按含髋的整条链比。"""
        lms = blank_landmarks()
        lms[LEFT_EAR] = Landmark(0.4, 0.3, 0.95)
        lms[LEFT_SHOULDER] = Landmark(0.4, 0.5, 0.95)
        lms[LEFT_HIP] = Landmark(0.4, 0.8, 0.10)      # 左髋不可用
        lms[RIGHT_EAR] = Landmark(0.6, 0.3, 0.75)
        lms[RIGHT_SHOULDER] = Landmark(0.6, 0.5, 0.75)
        lms[RIGHT_HIP] = Landmark(0.6, 0.8, 0.70)     # 右边整条链都够用
        self.assertEqual(pick_side(lms), "RIGHT")

    def test_pick_side_is_sticky_within_margin(self):
        lms = blank_landmarks()
        lms[LEFT_EAR] = Landmark(0.4, 0.3, 0.80)
        lms[LEFT_SHOULDER] = Landmark(0.4, 0.5, 0.80)
        lms[LEFT_HIP] = Landmark(0.4, 0.8, 0.80)
        lms[RIGHT_EAR] = Landmark(0.6, 0.3, 0.90)
        lms[RIGHT_SHOULDER] = Landmark(0.6, 0.5, 0.90)
        lms[RIGHT_HIP] = Landmark(0.6, 0.8, 0.90)
        # 没有历史时按分数选右
        self.assertEqual(pick_side(lms), "RIGHT")
        # 已经在用左侧：右侧只高 0.10，不到 0.15 的换边门槛，保持左侧不翻转
        self.assertEqual(pick_side(lms, "LEFT"), "LEFT")
        # 差距拉大到 0.30 才换
        lms[RIGHT_EAR] = Landmark(0.6, 0.3, 0.99)
        lms[RIGHT_SHOULDER] = Landmark(0.6, 0.5, 0.99)
        lms[RIGHT_HIP] = Landmark(0.6, 0.8, 0.99)
        self.assertEqual(pick_side(lms, "LEFT"), "RIGHT")
        # 反向同理：在用右侧时不会被略好的左侧拉走
        self.assertEqual(pick_side(lms, "RIGHT"), "RIGHT")

    def test_analyze_respects_current_side(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, side="RIGHT")
        # 左侧三点全 0，右侧明显更好，传 LEFT 也会被 margin 判成该换边
        status, m = analyze(lms, 100, 100, current_side="LEFT")
        self.assertEqual(status, VALID)
        self.assertEqual(m.side, "RIGHT")

    def test_torso_hint_holds_angle_when_hip_briefly_lost(self):
        """髋短暂丢失时沿用上次躯干方向，角度不该跳变（v0.7）。"""
        mem = pg.SideAndTorsoMemory()
        # 躯干竖直，头前伸：有髋时相对躯干线约 36.9 度
        with_hip = side_view(0.6, 0.35, 0.5, 0.5, hip_x=0.5, hip_y=0.8)
        status, m1 = mem.analyze(with_hip, 100, 100, now_ms=1_000)
        self.assertEqual(status, VALID)
        self.assertEqual(m1.neck_reference, REF_TORSO)

        # 下一帧髋没了，其余点不动：沿用方向，角度与上一帧一致
        no_hip = side_view(0.6, 0.35, 0.5, 0.5, hip_x=None, hip_y=None)
        status, m2 = mem.analyze(no_hip, 100, 100, now_ms=1_500)
        self.assertEqual(status, VALID)
        self.assertEqual(m2.neck_reference, pg.REF_TORSO_HELD)
        self.assertAlmostEqual(m2.neck_deg, m1.neck_deg, places=4)

        # 超过保留期（默认 2 秒）后退回竖直参考，并按 require_hip 跳过该帧
        status, m3 = mem.analyze(no_hip, 100, 100, now_ms=4_000)
        self.assertEqual(status, NO_TORSO)
        self.assertEqual(m3.neck_reference, REF_VERTICAL)

    def test_held_hint_does_not_renew_itself(self):
        """沿用的帧不能给自己续期，否则保留期形同虚设。"""
        mem = pg.SideAndTorsoMemory()
        mem.analyze(side_view(0.6, 0.35, 0.5, 0.5, hip_x=0.5, hip_y=0.8), 100, 100, now_ms=0)
        no_hip = side_view(0.6, 0.35, 0.5, 0.5, hip_x=None, hip_y=None)
        # 每 500 ms 一帧连续沿用，到 2500 ms 时已超过 2000 ms 的保留期
        for t in (500, 1_000, 1_500, 2_000):
            status, m = mem.analyze(no_hip, 100, 100, now_ms=t)
            self.assertEqual(m.neck_reference, pg.REF_TORSO_HELD, f"t={t}")
        status, m = mem.analyze(no_hip, 100, 100, now_ms=2_500)
        self.assertEqual(m.neck_reference, REF_VERTICAL)

    def test_torso_hint_prevents_false_alarm_when_leaning_over_desk(self):
        """伏案时髋一丢，退回竖直会让角度从 11 度跳到 35 度，直接越过阈值误报。"""
        mem = pg.SideAndTorsoMemory()
        # 躯干前倾（髋在肩后下方），头再前伸一点
        with_hip = side_view(0.64, 0.30, 0.50, 0.50, hip_x=0.36, hip_y=0.82)
        _, m1 = mem.analyze(with_hip, 100, 100, now_ms=0)
        self.assertLess(m1.neck_deg, 20.0)

        no_hip = side_view(0.64, 0.30, 0.50, 0.50, hip_x=None, hip_y=None)
        _, m2 = mem.analyze(no_hip, 100, 100, now_ms=500)
        self.assertEqual(m2.neck_reference, pg.REF_TORSO_HELD)
        self.assertAlmostEqual(m2.neck_deg, m1.neck_deg, places=3)

        # 对照：不带记忆直接算，角度会跳到 35 度附近
        _, bare = analyze(no_hip, 100, 100)
        self.assertEqual(bare.neck_reference, REF_VERTICAL)
        self.assertGreater(bare.neck_deg - m1.neck_deg, 20.0)

    def test_reference_direction_matches_the_angle_basis(self):
        """叠加层按 reference_direction 画参考线，它必须和算角度用的方向一致。"""
        mem = pg.SideAndTorsoMemory()
        with_hip = side_view(0.64, 0.30, 0.50, 0.50, hip_x=0.36, hip_y=0.82)
        _, m1 = mem.analyze(with_hip, 100, 100, now_ms=0)
        # 有髋时就是单位化的髋->肩方向
        dx, dy = m1.shoulder[0] - m1.hip[0], m1.shoulder[1] - m1.hip[1]
        length = math.hypot(dx, dy)
        self.assertAlmostEqual(m1.reference_direction[0], dx / length, places=5)
        self.assertAlmostEqual(m1.reference_direction[1], dy / length, places=5)
        # 用它反算角度，应与 measurement 里的角度一致
        recomputed = pg.angle_between_vectors(
            m1.reference_direction[0], m1.reference_direction[1],
            m1.ear[0] - m1.shoulder[0], m1.ear[1] - m1.shoulder[1])
        self.assertAlmostEqual(recomputed, m1.neck_deg, places=3)

        # 髋丢失沿用时，方向不变，参考线不会突然跳成竖直
        no_hip = side_view(0.64, 0.30, 0.50, 0.50, hip_x=None, hip_y=None)
        _, m2 = mem.analyze(no_hip, 100, 100, now_ms=500)
        self.assertEqual(m2.neck_reference, pg.REF_TORSO_HELD)
        self.assertIsNone(m2.hip)
        self.assertAlmostEqual(m2.reference_direction[0], m1.reference_direction[0], places=5)
        self.assertAlmostEqual(m2.reference_direction[1], m1.reference_direction[1], places=5)

        # 退回竖直参考时为 None，由调用方画竖直线
        _, m3 = mem.analyze(no_hip, 100, 100, now_ms=5_000)
        self.assertEqual(m3.neck_reference, REF_VERTICAL)
        self.assertIsNone(m3.reference_direction)

    def test_torso_hint_disabled_by_zero_hold(self):
        cfg = GeometryConfig(torso_hold_millis=0)
        mem = pg.SideAndTorsoMemory(cfg)
        mem.analyze(side_view(0.6, 0.35, 0.5, 0.5, hip_x=0.5, hip_y=0.8), 100, 100, now_ms=0)
        status, m = mem.analyze(side_view(0.6, 0.35, 0.5, 0.5, hip_x=None, hip_y=None),
                                100, 100, now_ms=100)
        self.assertEqual(status, NO_TORSO)
        self.assertEqual(m.neck_reference, REF_VERTICAL)

    def test_analyze_without_now_ms_keeps_old_behaviour(self):
        """不传时间戳时行为与 v0.6 完全一致，方便老调用点逐步迁移。"""
        no_hip = side_view(0.6, 0.35, 0.5, 0.5, hip_x=None, hip_y=None)
        status, m = analyze(no_hip, 100, 100)
        self.assertEqual(status, NO_TORSO)
        self.assertEqual(m.neck_reference, REF_VERTICAL)

    def test_analyze_low_visibility(self):
        lms = blank_landmarks()
        lms[LEFT_EAR] = Landmark(0.5, 0.3, 0.9)
        lms[LEFT_SHOULDER] = Landmark(0.5, 0.5, 0.2)
        self.assertEqual(analyze(lms, 100, 100), (LOW_VISIBILITY, None))

    def test_analyze_no_person(self):
        self.assertEqual(analyze(None, 100, 100), (NO_PERSON, None))
        self.assertEqual(analyze([], 100, 100), (NO_PERSON, None))

    def test_analyze_misaligned(self):
        # 躯干长 0.3，肩距 0.2 -> ratio 0.67 > 0.35
        lms = side_view(0.5, 0.3, 0.5, 0.5, far_x=0.7)
        status, m = analyze(lms, 100, 100)
        self.assertEqual(status, MISALIGNED)
        self.assertAlmostEqual(m.shoulder_offset_ratio, 0.2 / 0.3, places=2)

    def test_analyze_aligned_when_far_shoulder_occluded(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, far_x=0.9, far_vis=0.1)
        self.assertEqual(analyze(lms, 100, 100)[0], VALID)

    def test_analyze_torso_none_when_hip_hidden(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, hip_x=None, hip_y=None)
        status, m = analyze(lms, 100, 100)
        self.assertEqual(status, NO_TORSO)
        self.assertIsNone(m.torso_deg)
        self.assertIsNone(m.hip)
        self.assertEqual(m.neck_reference, REF_VERTICAL)
        self.assertAlmostEqual(m.neck_deg, 0.0, places=3)

    def test_analyze_torso_from_hip(self):
        lms = side_view(0.5, 0.3, 0.5, 0.5, hip_x=0.5, hip_y=0.8)
        status, m = analyze(lms, 100, 100)
        self.assertAlmostEqual(m.torso_deg, 0.0, places=3)

    def test_analyze_rejects_bad_size(self):
        with self.assertRaises(ValueError):
            analyze(side_view(0.5, 0.3, 0.5, 0.5), 0, 100)


CFG = AnalyzerConfig(absolute_threshold_deg=40.0, calibration_delta_deg=12.0, hysteresis_deg=4.0,
                     min_valid_frames_fallback=3, min_valid_frames_floor=1,
                     consecutive_bad_windows=2, cooldown_millis=10_000)


def meas(neck, torso=5.0):
    return Measurement("LEFT", (0, 0), (0, 10), (0, 20) if torso is not None else None,
                       neck, torso, 0.1, True)


def run_window(a, now, *necks, extra=()):
    a.begin_window()
    for n in necks:
        a.add_frame(VALID, meas(n))
    for status, m in extra:
        a.add_frame(status, m)
    return a.end_window(now)


class AnalyzerTest(unittest.TestCase):

    def test_median_odd_even(self):
        self.assertAlmostEqual(median([5, 1, 3]), 3.0)
        self.assertAlmostEqual(median([4, 1, 2, 3]), 2.5)
        with self.assertRaises(ValueError):
            median([])

    def test_threshold_absolute_and_clamped(self):
        self.assertAlmostEqual(threshold_for(None, CFG), 40.0)
        self.assertAlmostEqual(threshold_for(25.0, CFG), 37.0)
        # 默认 clamp 区间是 20..50（v0.5 起颈角相对躯干线，下限随之下调）
        self.assertAlmostEqual(threshold_for(2.0, CFG), 20.0)
        self.assertAlmostEqual(threshold_for(45.0, CFG), 50.0)

    def test_default_config_matches_torso_relative_thresholds(self):
        d = AnalyzerConfig()
        self.assertAlmostEqual(d.absolute_threshold_deg, 35.0)
        self.assertAlmostEqual(d.threshold_min_deg, 20.0)
        self.assertAlmostEqual(d.threshold_max_deg, 50.0)

    def test_invalid_window_does_not_touch_streak(self):
        a = PostureAnalyzer(CFG)
        run_window(a, 0, 50, 50, 50)
        self.assertEqual(a.bad_streak, 1)
        o = run_window(a, 1000, 50, extra=[(LOW_VISIBILITY, None), (NO_PERSON, None)])
        self.assertEqual(o["verdict"], INVALID)
        self.assertIsNone(o["median_neck"])
        self.assertEqual(a.bad_streak, 1)
        self.assertEqual(o["total"], 3)
        self.assertEqual(o["valid"], 1)

    def test_misaligned_counted_but_not_valid(self):
        a = PostureAnalyzer(CFG)
        o = run_window(a, 0, 20, 21, 22, extra=[(MISALIGNED, meas(60)), (MISALIGNED, meas(61))])
        self.assertEqual(o["verdict"], GOOD)
        self.assertEqual(o["misaligned"], 2)
        self.assertAlmostEqual(o["median_neck"], 21.0)

    def test_no_torso_frames_counted_but_not_valid(self):
        a = PostureAnalyzer(CFG)
        o = run_window(a, 0, 20, 21, 22, extra=[(NO_TORSO, meas(60)), (NO_TORSO, meas(61))])
        self.assertEqual(o["verdict"], GOOD)
        self.assertEqual(o["no_torso"], 2)
        self.assertEqual(o["misaligned"], 0)
        self.assertEqual(o["valid"], 3)
        self.assertEqual(o["total"], 5)
        self.assertAlmostEqual(o["median_neck"], 21.0)

    def test_alert_only_after_k_consecutive_bad(self):
        a = PostureAnalyzer(CFG)
        first = run_window(a, 0, 45, 46, 47)
        self.assertEqual(first["verdict"], BAD)
        self.assertFalse(first["confirmed"])
        self.assertFalse(first["should_notify"])
        self.assertFalse(first["should_record"])
        second = run_window(a, 1000, 45, 46, 47)
        self.assertTrue(second["confirmed"])
        self.assertTrue(second["should_notify"])
        self.assertTrue(second["should_record"])
        self.assertEqual(second["bad_streak"], 2)

    def test_good_window_resets_streak(self):
        a = PostureAnalyzer(CFG)
        run_window(a, 0, 45, 46, 47)
        good = run_window(a, 1000, 20, 21, 22)
        self.assertEqual(good["verdict"], GOOD)
        self.assertEqual(good["bad_streak"], 0)
        self.assertFalse(run_window(a, 2000, 45, 46, 47)["confirmed"])

    def test_cooldown_suppresses_notification(self):
        a = PostureAnalyzer(CFG)
        run_window(a, 0, 45, 46, 47)
        self.assertTrue(run_window(a, 1000, 45, 46, 47)["should_notify"])
        suppressed = run_window(a, 2000, 45, 46, 47)
        self.assertTrue(suppressed["confirmed"])
        self.assertFalse(suppressed["should_notify"])
        self.assertFalse(suppressed["should_record"])
        again = run_window(a, 1000 + 10_000, 45, 46, 47)
        self.assertTrue(again["should_notify"])
        self.assertTrue(again["should_record"])

    def test_hysteresis(self):
        a = PostureAnalyzer(CFG)
        run_window(a, 0, 45, 45, 45)
        self.assertTrue(a.in_forward_head)
        self.assertEqual(run_window(a, 1000, 38, 38, 38)["verdict"], BAD)   # 36..40 之间仍前倾
        self.assertEqual(run_window(a, 2000, 35, 35, 35)["verdict"], GOOD)  # < 36 退出
        self.assertFalse(a.in_forward_head)
        self.assertEqual(run_window(a, 3000, 38, 38, 38)["verdict"], GOOD)  # 未前倾时 38 不进入

    def test_calibration_sets_baseline_and_resets(self):
        a = PostureAnalyzer(CFG)
        run_window(a, 0, 45, 45, 45)
        self.assertEqual(a.bad_streak, 1)
        self.assertAlmostEqual(a.calibrate([22, 20, 24, 21, 23]), 22.0)
        self.assertAlmostEqual(a.baseline, 22.0)
        self.assertAlmostEqual(a.threshold, 34.0)
        self.assertEqual(a.bad_streak, 0)
        self.assertFalse(a.in_forward_head)
        self.assertIsNone(a.calibrate([]))
        self.assertAlmostEqual(a.baseline, 22.0)
        a.set_baseline(None)
        self.assertAlmostEqual(a.threshold, 40.0)

    def test_representative_closest_to_median(self):
        a = PostureAnalyzer(CFG)
        a.begin_window()
        idx = [a.add_frame(VALID, meas(10)), a.add_frame(LOW_VISIBILITY, None),
               a.add_frame(VALID, meas(30)), a.add_frame(VALID, meas(50))]
        self.assertEqual(idx, [0, 1, 2, 3])
        o = a.end_window(0)
        self.assertAlmostEqual(o["median_neck"], 30.0)
        self.assertEqual(o["representative_index"], 2)
        self.assertAlmostEqual(o["representative"].neck_deg, 30.0)

    def test_min_valid_frames_scales_with_expected_frame_count(self):
        """窗门槛按期望帧数的比例算：换帧率后松紧不变（v0.7）。"""
        cfg = AnalyzerConfig(min_valid_ratio=1.0 / 3.0, min_valid_frames_fallback=8,
                             min_valid_frames_floor=2)
        a = PostureAnalyzer(cfg)
        a.begin_window(24)                      # 3 s @ 8 fps
        self.assertEqual(a.min_valid_frames(), 8)
        a.begin_window(90)                      # 3 s @ 30 fps
        self.assertEqual(a.min_valid_frames(), 30)
        a.begin_window(4)                       # 帧率极低时被下限兜住
        self.assertEqual(a.min_valid_frames(), 2)
        a.begin_window()                        # 期望帧数未知，退回绝对门槛
        self.assertEqual(a.min_valid_frames(), 8)

    def test_window_invalid_when_valid_frames_below_ratio(self):
        cfg = AnalyzerConfig(absolute_threshold_deg=40.0, min_valid_ratio=1.0 / 3.0,
                             min_valid_frames_floor=1)
        a = PostureAnalyzer(cfg)
        a.begin_window(24)                      # 门槛 8 帧
        for _ in range(7):
            a.add_frame(VALID, meas(50))
        self.assertEqual(a.end_window(0)["verdict"], INVALID)
        a.begin_window(24)
        for _ in range(8):
            a.add_frame(VALID, meas(50))
        self.assertEqual(a.end_window(0)["verdict"], BAD)

    def test_torso_median_ignores_none(self):
        a = PostureAnalyzer(CFG)
        a.begin_window()
        a.add_frame(VALID, meas(10, 3.0))
        a.add_frame(VALID, meas(10, None))
        a.add_frame(VALID, meas(10, 7.0))
        self.assertAlmostEqual(a.end_window(0)["median_torso"], 5.0)


if __name__ == "__main__":
    unittest.main()
