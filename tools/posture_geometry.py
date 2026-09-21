"""与 Android 端 pose/PostureGeometry.kt、pose/PostureAnalyzer.kt 保持一致的纯算法实现。

不依赖 MediaPipe / OpenCV，树莓派版本（v3）直接复用本模块。
坐标约定：图像坐标 y 向下；角度单位为度。
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import median as _median
from typing import Dict, List, Optional, Sequence, Tuple

# MediaPipe Pose Landmarker 33 点索引
NOSE = 0
LEFT_EAR, RIGHT_EAR = 7, 8
LEFT_SHOULDER, RIGHT_SHOULDER = 11, 12
LEFT_HIP, RIGHT_HIP = 23, 24
LANDMARK_COUNT = 33

# analyze() 返回的状态
VALID = "VALID"
MISALIGNED = "MISALIGNED"
NO_TORSO = "NO_TORSO"
LOW_VISIBILITY = "LOW_VISIBILITY"
NO_PERSON = "NO_PERSON"

# 颈角用的参考方向
REF_TORSO = "TORSO"
REF_VERTICAL = "VERTICAL"

# end_window() 返回的判定
GOOD = "GOOD"
BAD = "BAD"
INVALID = "INVALID"

Point = Tuple[float, float]


@dataclass
class Landmark:
    """归一化关键点：x、y 取值 0..1，visibility 取值 0..1。"""

    x: float
    y: float
    visibility: float


@dataclass
class Measurement:
    side: str
    ear: Point
    shoulder: Point
    hip: Optional[Point]
    #: 颈部前倾角：耳肩连线与 neck_reference 指定方向的夹角
    neck_deg: float
    #: 髋肩连线与竖直向上的夹角，仅用于显示与记录
    torso_deg: Optional[float]
    shoulder_offset_ratio: float
    aligned: bool
    neck_reference: str = REF_TORSO


@dataclass
class GeometryConfig:
    min_visibility: float = 0.5
    max_shoulder_offset_ratio: float = 0.35
    #: True（默认）时髋不可见的帧记为 NO_TORSO 不参与判定，False 时退回竖直参考
    require_hip: bool = True
    #: 髋肩距离至少要有耳肩距离的多少倍，低于此值认为髋点不可靠
    min_torso_to_neck_ratio: float = 0.8


def median(values: Sequence[float]) -> float:
    if not values:
        raise ValueError("median of empty list")
    return float(_median(values))


def inclination_from_vertical(frm: Point, to: Point) -> float:
    """向量 frm->to 与竖直向上 (0,-1) 的夹角，度，范围 0..180。"""
    dx = to[0] - frm[0]
    dy = to[1] - frm[1]
    length = math.hypot(dx, dy)
    if length < 1e-4:
        return 0.0
    cos = max(-1.0, min(1.0, (frm[1] - to[1]) / length))
    return math.degrees(math.acos(cos))


def angle_between(a1: Point, a2: Point, b1: Point, b2: Point) -> float:
    """向量 a1->a2 与 b1->b2 的夹角，度，范围 0..180。任一向量为 0 时返回 0。"""
    ax, ay = a2[0] - a1[0], a2[1] - a1[1]
    bx, by = b2[0] - b1[0], b2[1] - b1[1]
    la = math.hypot(ax, ay)
    lb = math.hypot(bx, by)
    if la < 1e-4 or lb < 1e-4:
        return 0.0
    cos = max(-1.0, min(1.0, (ax * bx + ay * by) / (la * lb)))
    return math.degrees(math.acos(cos))


def neck_relative_to_torso(hip: Point, shoulder: Point, ear: Point) -> float:
    """颈部前倾角：耳肩连线与躯干（hip->shoulder）延长线的夹角。共线时为 0。"""
    return angle_between(hip, shoulder, shoulder, ear)


def distance(a: Point, b: Point) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def pick_side(lms: Sequence[Landmark]) -> str:
    """用耳朵 visibility 决定使用哪一侧的关键点。"""
    return "LEFT" if lms[LEFT_EAR].visibility >= lms[RIGHT_EAR].visibility else "RIGHT"


def analyze(lms: Optional[Sequence[Landmark]], width: int, height: int,
            cfg: GeometryConfig = GeometryConfig()) -> Tuple[str, Optional[Measurement]]:
    """返回 (status, measurement)。

    status ∈ {VALID, MISALIGNED, NO_TORSO, LOW_VISIBILITY, NO_PERSON}；
    仅 VALID / MISALIGNED / NO_TORSO 时 measurement 非 None。
    """
    if lms is None or len(lms) < LANDMARK_COUNT:
        return NO_PERSON, None
    if width <= 0 or height <= 0:
        raise ValueError("image size must be positive")

    side = pick_side(lms)
    if side == "LEFT":
        ear_i, sh_i, far_i, hip_i = LEFT_EAR, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP
    else:
        ear_i, sh_i, far_i, hip_i = RIGHT_EAR, RIGHT_SHOULDER, LEFT_SHOULDER, RIGHT_HIP

    ear_lm, sh_lm = lms[ear_i], lms[sh_i]
    if ear_lm.visibility < cfg.min_visibility or sh_lm.visibility < cfg.min_visibility:
        return LOW_VISIBILITY, None

    def px(lm: Landmark) -> Point:
        return lm.x * width, lm.y * height

    ear, shoulder = px(ear_lm), px(sh_lm)
    hip_lm = lms[hip_i]
    hip_visible: Optional[Point] = px(hip_lm) if hip_lm.visibility >= cfg.min_visibility else None
    far_lm = lms[far_i]

    neck_len = distance(ear, shoulder)
    # 髋点落在肩膀附近时躯干方向是噪声，宁可当成没有躯干线
    hip: Optional[Point] = hip_visible
    if hip is not None and distance(shoulder, hip) < neck_len * cfg.min_torso_to_neck_ratio:
        hip = None
    torso = inclination_from_vertical(hip_visible, shoulder) if hip_visible is not None else None

    if hip is not None:
        reference = REF_TORSO
        neck = neck_relative_to_torso(hip, shoulder, ear)
    else:
        reference = REF_VERTICAL
        neck = inclination_from_vertical(shoulder, ear)

    torso_len = distance(shoulder, hip_visible) if hip_visible is not None else neck_len * 2.0
    if far_lm.visibility < cfg.min_visibility or torso_len < 1e-3:
        # 远侧肩膀被身体挡住，恰好说明是正侧面
        offset = 0.0
    else:
        offset = abs(px(far_lm)[0] - shoulder[0]) / max(torso_len, 1e-3)
    aligned = offset < cfg.max_shoulder_offset_ratio

    m = Measurement(side, ear, shoulder, hip_visible, neck, torso, offset, aligned, reference)
    # 对齐问题优先提示：摆放不对时算出来的角度本来也不可信
    if not aligned:
        return MISALIGNED, m
    if hip is None and cfg.require_hip:
        return NO_TORSO, m
    return VALID, m


@dataclass
class AnalyzerConfig:
    #: v0.5 起颈角相对躯干线，伏案时躯干本身的前倾不再计入，所以比旧的竖直口径低
    absolute_threshold_deg: float = 35.0
    calibration_delta_deg: float = 12.0
    threshold_min_deg: float = 20.0
    threshold_max_deg: float = 50.0
    hysteresis_deg: float = 4.0
    min_valid_frames_per_window: int = 8
    consecutive_bad_windows: int = 2
    cooldown_millis: int = 10 * 60 * 1000


def threshold_for(baseline: Optional[float], cfg: AnalyzerConfig) -> float:
    if baseline is None:
        return cfg.absolute_threshold_deg
    return max(cfg.threshold_min_deg, min(cfg.threshold_max_deg, baseline + cfg.calibration_delta_deg))


class PostureAnalyzer:
    """采样窗状态机：窗聚合 -> 迟滞判定 -> 连续计数 -> 冷却。与 Kotlin 版语义一致。"""

    def __init__(self, cfg: AnalyzerConfig = AnalyzerConfig()):
        self.cfg = cfg
        self.baseline: Optional[float] = None
        self.in_forward_head = False
        self.bad_streak = 0
        self.last_notify_at: Optional[int] = None
        self._valid: List[Tuple[int, Measurement]] = []
        self._total = 0
        self._misaligned = 0
        self._no_torso = 0
        self._counter = 0

    @property
    def threshold(self) -> float:
        return threshold_for(self.baseline, self.cfg)

    def begin_window(self) -> None:
        self._valid.clear()
        self._total = 0
        self._misaligned = 0
        self._no_torso = 0
        self._counter = 0

    def add_frame(self, status: str, m: Optional[Measurement]) -> int:
        """返回该帧在本窗内的序号，可用来对应保存的图像。"""
        idx = self._counter
        self._counter += 1
        self._total += 1
        if status == VALID and m is not None:
            self._valid.append((idx, m))
        elif status == MISALIGNED:
            self._misaligned += 1
        elif status == NO_TORSO:
            self._no_torso += 1
        return idx

    def end_window(self, now_ms: int) -> Dict:
        thr = self.threshold
        base = dict(threshold=thr, total=self._total, valid=len(self._valid),
                    misaligned=self._misaligned, no_torso=self._no_torso)
        if len(self._valid) < self.cfg.min_valid_frames_per_window:
            return dict(base, verdict=INVALID, median_neck=None, median_torso=None,
                        representative_index=None, representative=None,
                        bad_streak=self.bad_streak, confirmed=False,
                        should_notify=False, should_record=False)

        necks = [m.neck_deg for _, m in self._valid]
        med = median(necks)
        torsos = [m.torso_deg for _, m in self._valid if m.torso_deg is not None]
        med_torso = median(torsos) if torsos else None
        rep_idx, rep = min(self._valid, key=lambda p: abs(p[1].neck_deg - med))

        # 迟滞判定
        if self.in_forward_head:
            self.in_forward_head = med >= thr - self.cfg.hysteresis_deg
        else:
            self.in_forward_head = med > thr
        verdict = BAD if self.in_forward_head else GOOD
        self.bad_streak = self.bad_streak + 1 if verdict == BAD else 0

        confirmed = self.bad_streak >= self.cfg.consecutive_bad_windows
        cooldown_ok = self.last_notify_at is None or now_ms - self.last_notify_at >= self.cfg.cooldown_millis
        should_notify = confirmed and cooldown_ok
        should_record = should_notify or self.bad_streak == self.cfg.consecutive_bad_windows
        if should_notify:
            self.last_notify_at = now_ms

        return dict(base, verdict=verdict, median_neck=med, median_torso=med_torso,
                    representative_index=rep_idx, representative=rep,
                    bad_streak=self.bad_streak, confirmed=confirmed,
                    should_notify=should_notify, should_record=should_record)

    def calibrate(self, samples: Sequence[float]) -> Optional[float]:
        """用端坐时的颈部角度样本设定基线；样本为空返回 None 且不改动。"""
        if not samples:
            return None
        self.baseline = median(samples)
        self.reset_state()
        return self.baseline

    def set_baseline(self, deg: Optional[float]) -> None:
        self.baseline = deg
        self.reset_state()

    def reset_state(self) -> None:
        """清空迟滞、连续计数与冷却，不清基线。"""
        self.in_forward_head = False
        self.bad_streak = 0
        self.last_notify_at = None
