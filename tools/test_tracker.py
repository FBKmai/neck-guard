"""posture_tracker 单测，用例与 Android 端 PostureTrackerTest.kt 一一对应。

运行：cd tools && python -m unittest test_tracker -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import posture_geometry as pg  # noqa: E402
from posture_tracker import (  # noqa: E402
    CONFIRMED, EV_FRAME_ADDED, EV_MODE_CHANGED, EV_RECOVERED, EV_WINDOW_ENDED, FAST, GOOD_WINDOW,
    RESET, RETRIGGERED, SLOW, TOO_MANY_INVALID, TRIGGERED, PostureTracker, TrackerConfig,
)

ANALYZER_CFG = pg.AnalyzerConfig(
    absolute_threshold_deg=40.0,
    calibration_delta_deg=12.0,
    hysteresis_deg=4.0,
    # 窗内期望 24 帧（3000/125），按 1/8 得有效帧门槛 3，与 v0.6 用例的绝对值一致
    min_valid_ratio=1.0 / 8.0,
    min_valid_frames_floor=1,
    consecutive_bad_windows=2,
    cooldown_millis=10_000,
)

TRACKER_CFG = TrackerConfig(
    slow_interval_millis=700,
    fast_interval_millis=125,
    confirm_window_millis=3_000,
    # 原来的 trigger_frames=2 / recover_frames=3 在 700 ms 巡检下等价于这两个时长
    trigger_millis=700,
    recover_millis=1_400,
    invalid_grace_millis=1_000,
    retrigger_hold_millis=5_000,
    max_invalid_windows=2,
)


def meas(neck, torso=5.0):
    return pg.Measurement("LEFT", (0, 0), (0, 10), (0, 20) if torso is not None else None,
                          neck, torso, 0.1, True, pg.REF_TORSO)


def tracker():
    return PostureTracker(ANALYZER_CFG, TRACKER_CFG)


def feed(t, now, neck):
    """送一个有效帧。"""
    return t.on_frame(pg.VALID, meas(neck), now)


def kinds(events, kind):
    return [e for e in events if e.kind == kind]


def run_fast_window(t, start_ms, *necks):
    """跑完一个确认窗：按 fastInterval 送帧后 tick 让窗到期，返回窗结束事件。"""
    for i, neck in enumerate(necks):
        t.on_frame(pg.VALID, meas(neck), start_ms + i * 125)
    events = t.tick(start_ms + TRACKER_CFG.confirm_window_millis)
    ended = kinds(events, EV_WINDOW_ENDED)
    assert len(ended) == 1, ended
    return ended[0], events


def trigger_fast(t, start_ms, neck=50.0):
    """从巡检触发进入 FAST，返回进入时刻。"""
    feed(t, start_ms, neck)
    events = feed(t, start_ms + 700, neck)
    changed = kinds(events, EV_MODE_CHANGED)
    assert changed and changed[0].to_mode == FAST, events
    return start_ms + 700


class TrackerTest(unittest.TestCase):

    def test_slow_frames_below_threshold_produce_no_events(self):
        t = tracker()
        for i in range(5):
            self.assertEqual(feed(t, i * 700, 20.0), [])
        self.assertEqual(t.mode, SLOW)

    def test_trigger_after_consecutive_frames_above_threshold(self):
        t = tracker()
        self.assertEqual(feed(t, 0, 50.0), [])
        events = feed(t, 700, 50.0)
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0].to_mode, FAST)
        self.assertEqual(changed[0].reason, TRIGGERED)
        self.assertAlmostEqual(changed[0].neck_deg, 50.0)
        self.assertEqual(t.mode, FAST)
        self.assertEqual(t.desired_interval_millis, 125)

    def test_trigger_timer_reset_by_good_frame(self):
        t = tracker()
        feed(t, 0, 50.0)
        feed(t, 700, 20.0)          # 低于阈值的正常帧清零计时
        self.assertEqual(feed(t, 1400, 50.0), [])
        self.assertEqual(t.mode, SLOW)

    def test_short_invalid_gap_suspends_timer_instead_of_clearing(self):
        """抓一下脸这种短暂无效不该让触发计时白跑（v0.7 宽限期）。"""
        t = tracker()
        feed(t, 0, 50.0)                        # 计时起点 0
        t.on_frame(pg.NO_PERSON, None, 500)     # 宽限期内（< 1000 ms），计时挂起
        events = feed(t, 700, 50.0)             # 累计 700 ms，达到 trigger_millis
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0].reason, TRIGGERED)

    def test_long_invalid_gap_clears_timer(self):
        t = tracker()
        feed(t, 0, 50.0)
        t.on_frame(pg.NO_PERSON, None, 700)
        t.on_frame(pg.NO_TORSO, meas(50.0), 1_800)   # 距首个无效帧 1100 ms，超宽限
        self.assertEqual(feed(t, 2_000, 50.0), [])   # 计时从头开始
        self.assertEqual(t.mode, SLOW)
        # 再攒够 trigger_millis 才触发
        changed = kinds(feed(t, 2_700, 50.0), EV_MODE_CHANGED)
        self.assertEqual(changed[0].reason, TRIGGERED)

    def test_trigger_is_frame_rate_independent(self):
        """同样的 700 ms 持续前倾，1.4 fps 与 8 fps 都恰好触发，不早也不晚。"""
        slow = tracker()
        feed(slow, 0, 50.0)
        self.assertEqual(kinds(feed(slow, 699, 50.0), EV_MODE_CHANGED), [])
        self.assertTrue(kinds(feed(slow, 700, 50.0), EV_MODE_CHANGED))
        fast = tracker()
        for ms in range(0, 700, 125):
            self.assertEqual(kinds(feed(fast, ms, 50.0), EV_MODE_CHANGED), [])
        self.assertTrue(kinds(feed(fast, 700, 50.0), EV_MODE_CHANGED))

    def test_fast_frames_produce_frame_added_with_same_window_seq(self):
        t = tracker()
        start = trigger_fast(t, 0)
        e1 = kinds(t.on_frame(pg.VALID, meas(50.0), start + 125), EV_FRAME_ADDED)
        e2 = kinds(t.on_frame(pg.VALID, meas(50.0), start + 250), EV_FRAME_ADDED)
        self.assertEqual(len(e1), 1)
        self.assertEqual(len(e2), 1)
        self.assertEqual(e1[0].window_seq, e2[0].window_seq)
        self.assertEqual([e1[0].index, e2[0].index], [0, 1])

    def test_fast_window_expires_on_tick_invalid_twice_goes_back_to_slow(self):
        t = tracker()
        start = trigger_fast(t, 0)
        # 一帧都不送，窗到期即 INVALID
        first, _ = run_fast_window(t, start)
        self.assertEqual(first.outcome["verdict"], pg.INVALID)
        self.assertEqual(first.consecutive_invalid, 1)
        self.assertEqual(t.mode, FAST)
        events = t.tick(start + 2 * TRACKER_CFG.confirm_window_millis)
        ended = kinds(events, EV_WINDOW_ENDED)[0]
        self.assertEqual(ended.consecutive_invalid, 2)
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(changed[0].reason, TOO_MANY_INVALID)
        self.assertEqual(t.mode, SLOW)
        self.assertEqual(t.desired_interval_millis, 700)

    def test_frame_after_expiry_ends_old_window_and_starts_new_one(self):
        t = tracker()
        start = trigger_fast(t, 0)
        for i in range(3):
            t.on_frame(pg.VALID, meas(50.0), start + i * 125)
        # 超过窗长后的这一帧：先结算旧窗，再计入新窗
        events = t.on_frame(pg.VALID, meas(50.0), start + 3_100)
        ended = kinds(events, EV_WINDOW_ENDED)
        self.assertEqual(len(ended), 1)
        self.assertEqual(ended[0].outcome["verdict"], pg.BAD)
        added = kinds(events, EV_FRAME_ADDED)
        self.assertEqual(len(added), 1)
        self.assertEqual(added[0].index, 0)
        self.assertGreater(added[0].window_seq, ended[0].window_seq)

    def test_confirm_after_two_bad_windows_returns_to_slow_in_forward_head(self):
        t = tracker()
        start = trigger_fast(t, 0)
        first, _ = run_fast_window(t, start, 45.0, 46.0, 47.0)
        self.assertEqual(first.outcome["verdict"], pg.BAD)
        self.assertFalse(first.outcome["confirmed"])
        self.assertEqual(t.mode, FAST)
        second, events = run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        self.assertTrue(second.outcome["confirmed"])
        self.assertTrue(second.outcome["should_notify"])
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(changed[0].reason, CONFIRMED)
        self.assertEqual(t.mode, SLOW)
        self.assertTrue(t.in_forward_head)

    def test_good_window_interrupts_streak_and_backs_to_slow(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        ended, events = run_fast_window(t, start + 3_000, 20.0, 21.0, 22.0)
        self.assertEqual(ended.outcome["verdict"], pg.GOOD)
        self.assertEqual(t.bad_streak, 0)
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(changed[0].reason, GOOD_WINDOW)
        self.assertEqual(t.mode, SLOW)
        self.assertFalse(t.in_forward_head)

    def test_good_window_while_forward_head_emits_recovered(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)   # 确认
        self.assertTrue(t.in_forward_head)
        # 重新进入确认后给一个 GOOD 窗
        again = trigger_fast(t, start + 60_000, 50.0)
        _, events = run_fast_window(t, again, 20.0, 21.0, 22.0)
        recovered = kinds(events, EV_RECOVERED)
        self.assertEqual(len(recovered), 1)
        self.assertGreater(recovered[0].forward_head_millis, 0)
        self.assertFalse(t.in_forward_head)

    def test_recovery_after_frames_below_exit_threshold_keeps_cooldown(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        self.assertTrue(t.in_forward_head)
        notify_at = t.last_notify_at
        base = start + 7_000
        feed(t, base, 30.0)
        feed(t, base + 700, 30.0)
        events = feed(t, base + 1_400, 30.0)      # 累计 1400 ms，达到 recover_millis
        recovered = kinds(events, EV_RECOVERED)
        self.assertEqual(len(recovered), 1)
        self.assertFalse(t.in_forward_head)
        self.assertEqual(t.bad_streak, 0)
        # 冷却保留，恢复后立刻再前倾只确认不通知
        self.assertEqual(t.last_notify_at, notify_at)

    def test_recovery_timer_also_honours_invalid_grace(self):
        """恢复方向的计时同样走宽限期，不只是触发方向。"""
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        self.assertTrue(t.in_forward_head)

        base = start + 7_000
        feed(t, base, 30.0)                          # 恢复计时起点
        t.on_frame(pg.NO_PERSON, None, base + 500)   # 宽限期内，挂起不清零
        events = feed(t, base + 1_400, 30.0)         # 累计够 recover_millis
        self.assertEqual(len(kinds(events, EV_RECOVERED)), 1)
        self.assertFalse(t.in_forward_head)
        self.assertEqual(t.snapshot().recover_progress_millis, 0)

    def test_no_recovery_within_hysteresis_band(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        base = start + 7_000
        # 38 在 36..40 之间，不算恢复也不重触发
        for i in range(5):
            self.assertEqual(feed(t, base + i * 700, 38.0), [])
        self.assertTrue(t.in_forward_head)

    def test_retrigger_blocked_by_hold_and_by_cooldown(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        confirmed_at = start + 3_000 + TRACKER_CFG.confirm_window_millis
        # hold 未过（< 5s）
        feed(t, confirmed_at + 1_000, 50.0)
        self.assertEqual(feed(t, confirmed_at + 1_700, 50.0), [])
        self.assertEqual(t.mode, SLOW)
        # hold 过了但冷却未过（< 10s）
        feed(t, confirmed_at + 6_000, 50.0)
        self.assertEqual(feed(t, confirmed_at + 6_700, 50.0), [])
        self.assertEqual(t.mode, SLOW)

    def test_retrigger_after_cooldown_confirms_in_one_window(self):
        t = tracker()
        start = trigger_fast(t, 0)
        run_fast_window(t, start, 45.0, 46.0, 47.0)
        run_fast_window(t, start + 3_000, 45.0, 46.0, 47.0)
        late = start + 60_000
        feed(t, late, 50.0)
        events = feed(t, late + 700, 50.0)
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(changed[0].reason, RETRIGGERED)
        # 确认后没清 bad_streak，一个 BAD 窗就能再次确认
        ended, _ = run_fast_window(t, late + 700, 45.0, 46.0, 47.0)
        self.assertTrue(ended.outcome["confirmed"])
        self.assertTrue(ended.outcome["should_notify"])

    def test_desired_interval_follows_mode(self):
        t = tracker()
        self.assertEqual(t.desired_interval_millis, 700)
        trigger_fast(t, 0)
        self.assertEqual(t.desired_interval_millis, 125)
        t.reset()
        self.assertEqual(t.desired_interval_millis, 700)

    def test_trigger_needs_full_duration_regardless_of_frame_count(self):
        """两帧也好十帧也好，攒够 trigger_millis 才进确认。"""
        t = tracker()
        feed(t, 0, 50.0)
        feed(t, 500, 50.0)          # 才 500 ms，不够 700
        self.assertEqual(t.mode, SLOW)
        feed(t, 700, 50.0)
        self.assertEqual(t.mode, FAST)

    def test_reset_during_fast_emits_mode_changed_reset(self):
        t = tracker()
        trigger_fast(t, 0)
        events = t.reset()
        changed = kinds(events, EV_MODE_CHANGED)
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0].reason, RESET)
        self.assertEqual(t.mode, SLOW)
        # 巡检状态下 reset 不产生事件
        self.assertEqual(t.reset(), [])

    def test_set_baseline_updates_threshold_and_resets(self):
        t = tracker()
        trigger_fast(t, 0)
        t.set_baseline(25.0)
        self.assertAlmostEqual(t.threshold_deg, 37.0)
        self.assertAlmostEqual(t.exit_threshold_deg, 33.0)
        self.assertEqual(t.mode, SLOW)
        self.assertFalse(t.in_forward_head)

    def test_calibrate_sets_baseline(self):
        t = tracker()
        self.assertAlmostEqual(t.calibrate([22.0, 20.0, 24.0, 21.0, 23.0]), 22.0)
        self.assertAlmostEqual(t.baseline_deg, 22.0)
        self.assertAlmostEqual(t.threshold_deg, 34.0)
        self.assertIsNone(t.calibrate([]))
        self.assertAlmostEqual(t.baseline_deg, 22.0)

    def test_snapshot_reflects_progress(self):
        t = tracker()
        feed(t, 0, 50.0)
        snap = t.snapshot()
        self.assertEqual(snap.mode, SLOW)
        self.assertEqual(snap.trigger_progress_millis, 0)   # 只有一帧，还没累计出时长
        feed(t, 400, 50.0)
        self.assertEqual(t.snapshot().trigger_progress_millis, 400)
        self.assertFalse(snap.forward_head)
        self.assertAlmostEqual(snap.threshold_deg, 40.0)

    def test_config_sanitized_against_bad_input(self):
        cfg = TrackerConfig(slow_interval_millis=0, fast_interval_millis=-5, confirm_window_millis=0,
                            trigger_millis=-1, recover_millis=-1, invalid_grace_millis=-1,
                            retrigger_hold_millis=-1, max_invalid_windows=0).sanitized()
        self.assertEqual(cfg.slow_interval_millis, 30)
        self.assertEqual(cfg.fast_interval_millis, 30)
        self.assertEqual(cfg.confirm_window_millis, 500)
        self.assertEqual(cfg.trigger_millis, 0)
        self.assertEqual(cfg.recover_millis, 0)
        self.assertEqual(cfg.invalid_grace_millis, 0)
        self.assertEqual(cfg.retrigger_hold_millis, 0)
        self.assertEqual(cfg.max_invalid_windows, 1)

    def test_observe_frame_interval_corrects_window_gate(self):
        """电脑端全速跑时用实测帧率校正窗门槛（v0.7）。"""
        t = tracker()
        self.assertEqual(t.config.expected_frames_per_window(), 24)
        t.observe_frame_interval(33)                       # 实测约 30 fps
        self.assertEqual(t.config.fast_interval_millis, 33)
        self.assertEqual(t.config.expected_frames_per_window(), 90)
        t.observe_frame_interval(0)                        # 非法值忽略
        self.assertEqual(t.config.fast_interval_millis, 33)
        t.observe_frame_interval(99_999)                   # 夹到上限
        self.assertEqual(t.config.fast_interval_millis, 5_000)

    def test_expected_frames_per_window(self):
        self.assertEqual(TRACKER_CFG.expected_frames_per_window(), 24)   # 3000 / 125
        self.assertEqual(TrackerConfig(confirm_window_millis=3_000,
                                       fast_interval_millis=33).expected_frames_per_window(), 90)


if __name__ == "__main__":
    unittest.main()
