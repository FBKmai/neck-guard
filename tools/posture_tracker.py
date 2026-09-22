"""巡检 / 确认双速状态机，与 Android 端 pose/PostureTracker.kt 语义一致。

Kotlin 版靠切换送帧间隔省电；电脑端不缺算力，可以把两个间隔设成一样全速跑，
判定语义（触发计数、窗聚合、迟滞、连续窗、冷却、恢复、重触发）完全相同。

坐标与角度约定见 posture_geometry.py。时间单位统一为毫秒。
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace
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
    #: 巡检下超阈值持续这么久才进入确认（v0.7 起按时长，不再按帧数）
    trigger_millis: int = 1_400
    #: 前倾中低于退出线持续这么久才算恢复
    recover_millis: int = 3_500
    #: 计时中途出现无效帧，容忍这么久不清零；超过则本次计时作废
    invalid_grace_millis: int = 1_000
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
            trigger_millis=max(0, self.trigger_millis),
            recover_millis=max(0, self.recover_millis),
            invalid_grace_millis=max(0, self.invalid_grace_millis),
            retrigger_hold_millis=max(0, self.retrigger_hold_millis),
            max_invalid_windows=max(1, self.max_invalid_windows),
        )

    def expected_frames_per_window(self) -> int:
        """确认窗内按送帧间隔推算的期望帧数，用于窗有效性的覆盖率门槛。"""
        return max(1, self.confirm_window_millis // max(1, self.fast_interval_millis))


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
    #: 已累计的超阈值 / 低于退出线时长，毫秒
    trigger_progress_millis: int
    recover_progress_millis: int
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
        #: 超阈值 / 低于退出线的连续计时起点，None 表示没在计时
        self._trigger_since: Optional[int] = None
        self._recover_since: Optional[int] = None
        #: 计时期间最后一次满足条件的帧时刻，用来算已累计时长
        self._last_trigger_at: Optional[int] = None
        self._last_recover_at: Optional[int] = None
        #: 计时中途第一个无效帧的时刻，用于宽限期
        self._invalid_since: Optional[int] = None
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

    def observe_frame_interval(self, interval_millis: int) -> None:
        """告知实测送帧间隔，用来校正确认窗的有效帧门槛。

        电脑端不做节流，帧率由手机推流与 GPU 决定，配置里的 fast_interval_millis
        只是个估计值。用实测值校正后，窗门槛才真的等于「窗内三分之一的时间有效」。
        只改间隔不碰计时口径，所以正在跑的确认窗不受影响（下一个窗才用新值）。
        """
        if interval_millis <= 0:
            return
        clamped = max(MIN_INTERVAL_MILLIS, min(5_000, int(interval_millis)))
        if clamped == self.config.fast_interval_millis:
            return
        self.config = replace(self.config, fast_interval_millis=clamped).sanitized()

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
            trigger_progress_millis=self._elapsed(self._trigger_since, self._last_trigger_at),
            recover_progress_millis=self._elapsed(self._recover_since, self._last_recover_at),
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
        self._clear_timers()
        self._consecutive_invalid = 0
        self._window_started_at = None
        self._confirmed_at = None

    def _clear_timers(self) -> None:
        self._trigger_since = None
        self._recover_since = None
        self._last_trigger_at = None
        self._last_recover_at = None
        self._invalid_since = None

    @staticmethod
    def _elapsed(since: Optional[int], last: Optional[int]) -> int:
        """计时起点到最后一次满足条件的帧之间的时长。没在计时返回 0。"""
        if since is None or last is None:
            return 0
        return max(0, last - since)

    def _held_for(self, since: Optional[int], last: Optional[int], need_millis: int) -> bool:
        """条件是否已连续满足 need_millis。need_millis <= 0 时一帧即成立。"""
        if since is None:
            return False
        if need_millis <= 0:
            return True
        return self._elapsed(since, last) >= need_millis

    def _window_expired(self, now_ms: int) -> bool:
        if self._window_started_at is None:
            return False
        return now_ms - self._window_started_at >= self.config.confirm_window_millis

    def _handle_slow_frame(self, status: str, m: Optional[pg.Measurement],
                           now_ms: int, events: List[TrackerEvent]) -> None:
        """巡检帧：按时长累计，不进窗聚合。

        v0.7 起触发与恢复都按毫秒计时，换帧率或换推理后端不会改变行为。
        短暂无效（抓一下脸、手挡住耳朵）在宽限期内只挂起计时而不清零。
        """
        if status != pg.VALID or m is None:
            # 未对齐 / 无躯干线 / 看不清 / 没人
            if self._trigger_since is None and self._recover_since is None:
                return
            if self._invalid_since is None:
                self._invalid_since = now_ms
            elif now_ms - self._invalid_since >= self.config.invalid_grace_millis:
                self._clear_timers()
            return
        self._invalid_since = None
        neck = m.neck_deg
        if not self._analyzer.in_forward_head:
            # S0：正常巡检
            self._recover_since = None
            self._last_recover_at = None
            if neck > self._analyzer.threshold:
                if self._trigger_since is None:
                    self._trigger_since = now_ms
                self._last_trigger_at = now_ms
                if self._held_for(self._trigger_since, self._last_trigger_at, self.config.trigger_millis):
                    self._enter_fast(TRIGGERED, neck, now_ms, events)
            else:
                self._trigger_since = None
                self._last_trigger_at = None
            return
        # S2：已确认前倾，等恢复或冷却后重新确认
        if neck < self.exit_threshold_deg:
            self._trigger_since = None
            self._last_trigger_at = None
            if self._recover_since is None:
                self._recover_since = now_ms
            self._last_recover_at = now_ms
            if self._held_for(self._recover_since, self._last_recover_at, self.config.recover_millis):
                self._analyzer.in_forward_head = False
                self._analyzer.bad_streak = 0  # markRecovered：保留冷却
                # 只有确认过（用户已经收到提醒）才报恢复
                if self._confirmed_at is not None:
                    events.append(TrackerEvent(EV_RECOVERED, neck_deg=neck,
                                               forward_head_millis=now_ms - self._confirmed_at))
                self._recover_since = None
                self._last_recover_at = None
                self._confirmed_at = None
            return
        self._recover_since = None
        self._last_recover_at = None
        hold_passed = (self._confirmed_at is None or
                       now_ms - self._confirmed_at >= self.config.retrigger_hold_millis)
        can_notify = (self._analyzer.last_notify_at is None or
                      now_ms - self._analyzer.last_notify_at >= self._analyzer.cfg.cooldown_millis)
        if hold_passed and can_notify:
            if self._trigger_since is None:
                self._trigger_since = now_ms
            self._last_trigger_at = now_ms
            if self._held_for(self._trigger_since, self._last_trigger_at, self.config.trigger_millis):
                self._enter_fast(RETRIGGERED, neck, now_ms, events)
        else:
            # 迟滞带内或冷却未过：保持前倾状态但不做无谓的高帧率确认
            self._trigger_since = None
            self._last_trigger_at = None

    def _enter_fast(self, reason: str, neck_deg: Optional[float], now_ms: int,
                    events: List[TrackerEvent]) -> None:
        self._clear_timers()
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
        self._clear_timers()
        events.append(TrackerEvent(EV_MODE_CHANGED, from_mode=FAST, to_mode=SLOW,
                                   reason=reason, neck_deg=neck_deg))

    def _begin_window(self, now_ms: int) -> None:
        self._window_seq += 1
        self._window_started_at = now_ms
        # 把期望帧数交给分析器，窗有效性按覆盖率而非绝对帧数判定
        self._analyzer.begin_window(self.config.expected_frames_per_window())

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
