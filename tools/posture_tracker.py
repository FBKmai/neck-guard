"""巡检 / 确认双速状态机，与 Android 端 pose/PostureTracker.kt 语义一致。

Kotlin 版靠切换送帧间隔省电；电脑端不缺算力，可以把两个间隔设成一样全速跑，
判定语义（触发计数、窗聚合、迟滞、连续窗、冷却、恢复、重触发）完全相同。

坐标与角度约定见 posture_geometry.py。时间单位统一为毫秒。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import posture_geometry as pg

# 检测节奏
SLOW = "SLOW"
FAST = "FAST"

# 模式切换原因
TRIGGERED = "TRIGGERED"
RETRIGGERED = "RETRIGGERED"
CONFIRMED = "CONFIRMED"
GOOD_WINDOW = "GOOD_WINDOW"
TOO_MANY_INVALID = "TOO_MANY_INVALID"
RESET = "RESET"

# 事件类型
EV_FRAME_ADDED = "FRAME_ADDED"
EV_MODE_CHANGED = "MODE_CHANGED"
EV_WINDOW_ENDED = "WINDOW_ENDED"
EV_RECOVERED = "RECOVERED"

MIN_INTERVAL_MILLIS = 30


@dataclass
class TrackerConfig:
    """与 Kotlin 的 TrackerConfig 同名同义。电脑端默认两个间隔相同，全速检测。"""

    slow_interval_millis: int = 100
    fast_interval_millis: int = 100
    confirm_window_millis: int = 3_000
    #: 巡检下连续多少个有效帧超阈值才进入确认
    trigger_frames: int = 2
    #: 前倾中连续多少个有效帧低于退出线才算恢复
    recover_frames: int = 5
    #: 确认后至少隔这么久才允许再次进入确认
    retrigger_hold_millis: int = 30_000
    #: 确认模式内连续多少个无效窗退回巡检
    max_invalid_windows: int = 2

    def sanitized(self) -> "TrackerConfig":
        """防御性夹取，避免命令行传进 0 或负数导致空转。"""
        def clamp(v, lo, hi):
            return max(lo, min(hi, v))

        return TrackerConfig(
            slow_interval_millis=clamp(self.slow_interval_millis, MIN_INTERVAL_MILLIS, 10_000),
            fast_interval_millis=clamp(self.fast_interval_millis, MIN_INTERVAL_MILLIS, 5_000),
            confirm_window_millis=clamp(self.confirm_window_millis, 500, 60_000),
            trigger_frames=max(1, self.trigger_frames),
            recover_frames=max(1, self.recover_frames),
            retrigger_hold_millis=max(0, self.retrigger_hold_millis),
            max_invalid_windows=max(1, self.max_invalid_windows),
        )


@dataclass
class TrackerEvent:
    """状态机抛出的事件。调用方按顺序处理，处理期间不要回调 tracker 的写方法。"""

    kind: str
    #: FRAME_ADDED / WINDOW_ENDED
    window_seq: int = 0
    #: FRAME_ADDED
    index: int = 0
    status: Optional[str] = None
    measurement: Optional[pg.Measurement] = None
    #: MODE_CHANGED
    from_mode: Optional[str] = None
    to_mode: Optional[str] = None
    reason: Optional[str] = None
    neck_deg: Optional[float] = None
    #: WINDOW_ENDED
    outcome: Optional[Dict] = None
    consecutive_invalid: int = 0
    #: RECOVERED
    forward_head_millis: int = 0


@dataclass
class TrackerSnapshot:
    mode: str
    forward_head: bool
    bad_streak: int
    trigger_progress: int
    recover_progress: int
    window_seq: int
    window_started_at_millis: Optional[int]
    confirmed_at_millis: Optional[int]
    threshold_deg: float
    baseline_deg: Optional[float]


class PostureTracker:
    """三个状态：

    - S0：SLOW 且未前倾，逐帧看有没有超阈值；
    - FAST：确认窗连跑，窗聚合与判定全部交给 PostureAnalyzer；
    - S2：SLOW 且已确认前倾，等待恢复或冷却后重新确认。

    单线程使用（电脑端只有一个取流线程在推进），不加锁。
    """

    def __init__(self, analyzer_config: Optional[pg.AnalyzerConfig] = None,
                 tracker_config: Optional[TrackerConfig] = None):
        self._analyzer = pg.PostureAnalyzer(analyzer_config or pg.AnalyzerConfig())
        self.config = (tracker_config or TrackerConfig()).sanitized()
        self.mode = SLOW
        self.desired_interval_millis = self.config.slow_interval_millis
        self._trigger_count = 0
        self._recover_count = 0
        self._consecutive_invalid = 0
        self._window_seq = 0
        self._window_started_at: Optional[int] = None
        self._confirmed_at: Optional[int] = None

    # ------------------------------------------------------------- 只读属性
    @property
    def threshold_deg(self) -> float:
        return self._analyzer.threshold

    @property
    def exit_threshold_deg(self) -> float:
        return self._analyzer.threshold - self._analyzer.cfg.hysteresis_deg

    @property
    def baseline_deg(self) -> Optional[float]:
        return self._analyzer.baseline

    @property
    def in_forward_head(self) -> bool:
        return self._analyzer.in_forward_head

    @property
    def bad_streak(self) -> int:
        return self._analyzer.bad_streak

    @property
    def last_notify_at(self) -> Optional[int]:
        return self._analyzer.last_notify_at

    # ----------------------------------------------------------------- 输入
    def on_frame(self, status: str, m: Optional[pg.Measurement], now_ms: int) -> List[TrackerEvent]:
        """处理一帧，返回事件列表。"""
        events: List[TrackerEvent] = []
        # 先结算已到期的窗，本帧再计入新窗，避免窗被一帧拖长
        if self.mode == FAST and self._window_expired(now_ms):
            self._end_window_into(events, now_ms)
        if self.mode == FAST:
            index = self._analyzer.add_frame(status, m)
            events.append(TrackerEvent(EV_FRAME_ADDED, window_seq=self._window_seq, index=index,
                                       status=status, measurement=m))
        else:
            self._handle_slow_frame(status, m, now_ms, events)
        return events

    def tick(self, now_ms: int) -> List[TrackerEvent]:
        """定时驱动：确认窗内长时间没有帧时也能让窗到期（结果为 INVALID）。"""
        if self.mode != FAST or not self._window_expired(now_ms):
            return []
        events: List[TrackerEvent] = []
        self._end_window_into(events, now_ms)
        return events

    def calibrate(self, samples_deg: List[float]) -> Optional[float]:
        baseline = self._analyzer.calibrate(samples_deg)
        if baseline is not None:
            self._reset_internal()
        return baseline

    def set_baseline(self, deg: Optional[float]) -> None:
        self._analyzer.set_baseline(deg)
        self._reset_internal()

    def reset(self) -> List[TrackerEvent]:
        events: List[TrackerEvent] = []
        was_fast = self.mode == FAST
        self._analyzer.reset_state()
        self._reset_internal()
        if was_fast:
            events.append(TrackerEvent(EV_MODE_CHANGED, from_mode=FAST, to_mode=SLOW, reason=RESET))
        return events

    def snapshot(self) -> TrackerSnapshot:
        return TrackerSnapshot(
            mode=self.mode,
            forward_head=self._analyzer.in_forward_head,
            bad_streak=self._analyzer.bad_streak,
            trigger_progress=self._trigger_count,
            recover_progress=self._recover_count,
            window_seq=self._window_seq,
            window_started_at_millis=self._window_started_at,
            confirmed_at_millis=self._confirmed_at,
            threshold_deg=self._analyzer.threshold,
            baseline_deg=self._analyzer.baseline,
        )

    # ----------------------------------------------------------------- 内部
    def _reset_internal(self) -> None:
        self.mode = SLOW
        self.desired_interval_millis = self.config.slow_interval_millis
        self._trigger_count = 0
        self._recover_count = 0
        self._consecutive_invalid = 0
        self._window_started_at = None
        self._confirmed_at = None

    def _window_expired(self, now_ms: int) -> bool:
        if self._window_started_at is None:
            return False
        return now_ms - self._window_started_at >= self.config.confirm_window_millis

    def _handle_slow_frame(self, status: str, m: Optional[pg.Measurement],
                           now_ms: int, events: List[TrackerEvent]) -> None:
        """巡检帧：只逐帧计数，不进窗聚合。"""
        if status != pg.VALID or m is None:
            # 未对齐 / 无躯干线 / 看不清 / 没人：两个方向的连续计数都清零
            self._trigger_count = 0
            self._recover_count = 0
            return
        neck = m.neck_deg
        if not self._analyzer.in_forward_head:
            # S0：正常巡检
            self._recover_count = 0
            if neck > self._analyzer.threshold:
                self._trigger_count += 1
                if self._trigger_count >= self.config.trigger_frames:
                    self._enter_fast(TRIGGERED, neck, now_ms, events)
            else:
                self._trigger_count = 0
            return
        # S2：已确认前倾，等恢复或冷却后重新确认
        if neck < self.exit_threshold_deg:
            self._trigger_count = 0
            self._recover_count += 1
            if self._recover_count >= self.config.recover_frames:
                self._analyzer.in_forward_head = False
                self._analyzer.bad_streak = 0  # markRecovered：保留冷却
                # 只有确认过（用户已经收到提醒）才报恢复
                if self._confirmed_at is not None:
                    events.append(TrackerEvent(EV_RECOVERED, neck_deg=neck,
                                               forward_head_millis=now_ms - self._confirmed_at))
                self._recover_count = 0
                self._confirmed_at = None
            return
        self._recover_count = 0
        hold_passed = (self._confirmed_at is None or
                       now_ms - self._confirmed_at >= self.config.retrigger_hold_millis)
        can_notify = (self._analyzer.last_notify_at is None or
                      now_ms - self._analyzer.last_notify_at >= self._analyzer.cfg.cooldown_millis)
        if hold_passed and can_notify:
            self._trigger_count += 1
            if self._trigger_count >= self.config.trigger_frames:
                self._enter_fast(RETRIGGERED, neck, now_ms, events)
        else:
            # 迟滞带内或冷却未过：保持前倾状态但不做无谓的高帧率确认
            self._trigger_count = 0

    def _enter_fast(self, reason: str, neck_deg: Optional[float], now_ms: int,
                    events: List[TrackerEvent]) -> None:
        self._trigger_count = 0
        self._recover_count = 0
        self._consecutive_invalid = 0
        self._begin_window(now_ms)
        self.mode = FAST
        self.desired_interval_millis = self.config.fast_interval_millis
        events.append(TrackerEvent(EV_MODE_CHANGED, from_mode=SLOW, to_mode=FAST,
                                   reason=reason, neck_deg=neck_deg))

    def _leave_fast(self, reason: str, neck_deg: Optional[float], events: List[TrackerEvent]) -> None:
        self._window_started_at = None
        self.mode = SLOW
        self.desired_interval_millis = self.config.slow_interval_millis
        self._trigger_count = 0
        self._recover_count = 0
        events.append(TrackerEvent(EV_MODE_CHANGED, from_mode=FAST, to_mode=SLOW,
                                   reason=reason, neck_deg=neck_deg))

    def _begin_window(self, now_ms: int) -> None:
        self._window_seq += 1
        self._window_started_at = now_ms
        self._analyzer.begin_window()

    def _end_window_into(self, events: List[TrackerEvent], now_ms: int) -> None:
        was_forward_head = self._analyzer.in_forward_head
        seq = self._window_seq
        outcome = self._analyzer.end_window(now_ms)
        verdict = outcome["verdict"]
        if verdict == pg.INVALID:
            self._consecutive_invalid += 1
            events.append(TrackerEvent(EV_WINDOW_ENDED, window_seq=seq, outcome=outcome,
                                       consecutive_invalid=self._consecutive_invalid))
            if self._consecutive_invalid >= self.config.max_invalid_windows:
                self._leave_fast(TOO_MANY_INVALID, None, events)
            else:
                self._begin_window(now_ms)
        elif verdict == pg.GOOD:
            self._consecutive_invalid = 0
            events.append(TrackerEvent(EV_WINDOW_ENDED, window_seq=seq, outcome=outcome))
            med = outcome["median_neck"]
            since = self._confirmed_at
            if was_forward_head and med is not None and since is not None:
                events.append(TrackerEvent(EV_RECOVERED, neck_deg=med, forward_head_millis=now_ms - since))
            self._confirmed_at = None
            self._leave_fast(GOOD_WINDOW, med, events)
        else:  # BAD
            self._consecutive_invalid = 0
            events.append(TrackerEvent(EV_WINDOW_ENDED, window_seq=seq, outcome=outcome))
            if outcome["confirmed"]:
                self._confirmed_at = now_ms
                self._leave_fast(CONFIRMED, outcome["median_neck"], events)
            else:
                # 还没连够 K 个窗，背靠背再开一窗
                self._begin_window(now_ms)
