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
#: 髋刚丢失，沿用最近一次可靠的躯干方向。口径与 REF_TORSO 相同，数值可直接比较
REF_TORSO_HELD = "TORSO_HELD"
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
    #: 实际用来算角度的参考方向（已单位化，由髋指向肩）。
    #: REF_TORSO_HELD 时髋为 None，但角度是按这条沿用的方向算的，叠加层要用它
    #: 画参考线，否则线是竖直的而数字不是，看着对不上。REF_VERTICAL 时为 None。
    reference_direction: Optional[Point] = None


@dataclass
class TorsoHint:
    """最近一次可靠的躯干方向，由调用方持有并逐帧传入。

    髋被手或桌子短暂挡住时，与其让整帧作废（NO_TORSO 不参与判定）或改用
    竖直参考（换了口径，角度会跳十几度），不如沿用刚才那条躯干线：
    躯干方向变化远比帧间隔慢，一两秒内几乎不动。
    """

    #: 归一化的躯干方向（由髋指向肩），已单位化
    dx: float
    dy: float
    #: 该方向来自哪一帧（毫秒），用于判断是否过期
    at_millis: int


def torso_hint_from(hip: Point, shoulder: Point, now_ms: int) -> Optional[TorsoHint]:
    """从一帧可靠的髋肩点生成方向提示。两点重合时返回 None。"""
    dx, dy = shoulder[0] - hip[0], shoulder[1] - hip[1]
    length = math.hypot(dx, dy)
    if length < 1e-4:
        return None
    return TorsoHint(dx / length, dy / length, now_ms)


@dataclass
class GeometryConfig:
    min_visibility: float = 0.5
    max_shoulder_offset_ratio: float = 0.35
    #: True（默认）时髋不可见的帧记为 NO_TORSO 不参与判定，False 时退回竖直参考
    require_hip: bool = True
    #: 髋肩距离至少要有耳肩距离的多少倍，低于此值认为髋点不可靠
    min_torso_to_neck_ratio: float = 0.8
    #: 另一侧的可用度要高出当前侧这么多才换边，避免逐帧翻转导致角度跳变
    side_switch_margin: float = 0.15
    #: 髋丢失后，最近一次可靠的躯干方向还能沿用多久（毫秒）。0 表示关掉这个兜底
    torso_hold_millis: int = 2_000


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


def angle_between_vectors(ax: float, ay: float, bx: float, by: float) -> float:
    """两个向量的夹角，度，范围 0..180。任一向量为 0 时返回 0。"""
    la = math.hypot(ax, ay)
    lb = math.hypot(bx, by)
    if la < 1e-4 or lb < 1e-4:
        return 0.0
    cos = max(-1.0, min(1.0, (ax * bx + ay * by) / (la * lb)))
    return math.degrees(math.acos(cos))


def angle_between(a1: Point, a2: Point, b1: Point, b2: Point) -> float:
    """向量 a1->a2 与 b1->b2 的夹角，度，范围 0..180。任一向量为 0 时返回 0。"""
    return angle_between_vectors(a2[0] - a1[0], a2[1] - a1[1], b2[0] - b1[0], b2[1] - b1[1])


def neck_relative_to_torso(hip: Point, shoulder: Point, ear: Point) -> float:
    """颈部前倾角：耳肩连线与躯干（hip->shoulder）延长线的夹角。共线时为 0。"""
    return angle_between(hip, shoulder, shoulder, ear)


def distance(a: Point, b: Point) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def side_score(lms: Sequence[Landmark], side: str) -> Tuple[float, float]:
    """一侧的可用度，返回 (耳肩链得分, 含髋链得分)，都取各自链上最弱的一点。

    耳肩髋是串联关系，任何一点崩掉整条向量都会崩，所以取 min 而不是平均。
    只看耳朵会在「耳朵清楚但肩很糊」时选错边。

    分成两档是因为髋经常被桌子挡住：那时两侧的含髋得分都接近 0，直接比会
    退化成掷硬币，此时应该回到耳肩这一档上比较。
    """
    if side == "LEFT":
        ear_i, sh_i, hip_i = LEFT_EAR, LEFT_SHOULDER, LEFT_HIP
    else:
        ear_i, sh_i, hip_i = RIGHT_EAR, RIGHT_SHOULDER, RIGHT_HIP
    neck = min(lms[ear_i].visibility, lms[sh_i].visibility)
    return neck, min(neck, lms[hip_i].visibility)


def pick_side(lms: Sequence[Landmark], current: Optional[str] = None,
              switch_margin: float = 0.15, min_visibility: float = 0.5) -> str:
    """选用哪一侧的关键点。

    先比含髋的整条链；两侧的髋都不可用时退回只比耳肩，避免被两个同样接近 0
    的髋置信度主导。current 是上一帧用的侧别，给了它就带切换粘性：另一侧要
    高出 switch_margin 才换边，否则两边得分接近时会逐帧翻转让角度跳变。
    """
    left_neck, left_full = side_score(lms, "LEFT")
    right_neck, right_full = side_score(lms, "RIGHT")
    # 两侧都没有可用的髋时，含髋得分没有区分度，改比耳肩
    if left_full < min_visibility and right_full < min_visibility:
        left, right = left_neck, right_neck
    else:
        left, right = left_full, right_full
    if current is None:
        return "LEFT" if left >= right else "RIGHT"
    other = "RIGHT" if current == "LEFT" else "LEFT"
    current_score = left if current == "LEFT" else right
    other_score = right if current == "LEFT" else left
    return other if other_score > current_score + switch_margin else current


def _hint_usable(hint: Optional[TorsoHint], now_ms: Optional[int], hold_millis: int) -> bool:
    """方向提示是否还在保留期内。缺时间戳或关掉保留期时一律不用。"""
    if hint is None or now_ms is None or hold_millis <= 0:
        return False
    age = now_ms - hint.at_millis
    return 0 <= age <= hold_millis


def analyze(lms: Optional[Sequence[Landmark]], width: int, height: int,
            cfg: GeometryConfig = GeometryConfig(),
            current_side: Optional[str] = None,
            torso_hint: Optional[TorsoHint] = None,
            now_ms: Optional[int] = None) -> Tuple[str, Optional[Measurement]]:
    """返回 (status, measurement)。

    status ∈ {VALID, MISALIGNED, NO_TORSO, LOW_VISIBILITY, NO_PERSON}；
    仅 VALID / MISALIGNED / NO_TORSO 时 measurement 非 None。

    current_side 是上一帧用的侧别，传了就带切换粘性（见 pick_side）。
    保持纯函数：状态由调用方持有，本函数不记忆任何东西。
    """
    if lms is None or len(lms) < LANDMARK_COUNT:
        return NO_PERSON, None
    if width <= 0 or height <= 0:
        raise ValueError("image size must be positive")

    side = pick_side(lms, current_side, cfg.side_switch_margin, cfg.min_visibility)
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
    elif _hint_usable(torso_hint, now_ms, cfg.torso_hold_millis):
        # 髋刚丢，沿用最近一次可靠的躯干方向：口径不变，角度可以直接跟之前比
        reference = REF_TORSO_HELD
        neck = angle_between_vectors(torso_hint.dx, torso_hint.dy,
                                     ear[0] - shoulder[0], ear[1] - shoulder[1])
    else:
        reference = REF_VERTICAL
        neck = inclination_from_vertical(shoulder, ear)

    # 叠加层按它画参考线，保证线与角度始终同源
    ref_dir: Optional[Point] = None
    if hip is not None:
        dx, dy = shoulder[0] - hip[0], shoulder[1] - hip[1]
        length = math.hypot(dx, dy)
        if length >= 1e-4:
            ref_dir = (dx / length, dy / length)
    elif reference == REF_TORSO_HELD:
        ref_dir = (torso_hint.dx, torso_hint.dy)

    torso_len = distance(shoulder, hip_visible) if hip_visible is not None else neck_len * 2.0
    if far_lm.visibility < cfg.min_visibility or torso_len < 1e-3:
        # 远侧肩膀被身体挡住，恰好说明是正侧面
        offset = 0.0
    else:
        offset = abs(px(far_lm)[0] - shoulder[0]) / max(torso_len, 1e-3)
    aligned = offset < cfg.max_shoulder_offset_ratio

    m = Measurement(side, ear, shoulder, hip_visible, neck, torso, offset, aligned, reference, ref_dir)
    # 对齐问题优先提示：摆放不对时算出来的角度本来也不可信
    if not aligned:
        return MISALIGNED, m
    # REF_TORSO_HELD 与 REF_TORSO 是同一个口径，可以照常参与判定；
    # 只有退到竖直参考（换了口径）才在 require_hip 下跳过这一帧
    if reference == REF_VERTICAL and cfg.require_hip:
        return NO_TORSO, m
    return VALID, m


class SideAndTorsoMemory:
    """analyze 的逐帧记忆：上一帧的侧别 + 最近一次可靠的躯干方向。

    analyze 本身保持纯函数，这点状态放在这里，三个调用方（手机服务、手机
    预览页、电脑脚本）共用同一套语义，不必各写一遍。单线程使用，不加锁。
    """

    def __init__(self, cfg: GeometryConfig = GeometryConfig()):
        self.cfg = cfg
        self.side: Optional[str] = None
        self.torso_hint: Optional[TorsoHint] = None

    def analyze(self, lms: Optional[Sequence[Landmark]], width: int, height: int,
                now_ms: Optional[int] = None) -> Tuple[str, Optional[Measurement]]:
        status, m = analyze(lms, width, height, self.cfg, self.side, self.torso_hint, now_ms)
        if m is None:
            return status, m
        self.side = m.side
        # 只有这一帧真的有可靠的髋，才刷新方向提示；沿用的帧不能自我续期，
        # 否则髋一直不出现也能无限续下去，保留期就形同虚设。
        if m.neck_reference == REF_TORSO and m.hip is not None and now_ms is not None:
            hint = torso_hint_from(m.hip, m.shoulder, now_ms)
            if hint is not None:
                self.torso_hint = hint
        return status, m

    def reset(self) -> None:
        self.side = None
        self.torso_hint = None


@dataclass
class AnalyzerConfig:
    #: v0.5 起颈角相对躯干线，伏案时躯干本身的前倾不再计入，所以比旧的竖直口径低
    absolute_threshold_deg: float = 35.0
    calibration_delta_deg: float = 12.0
    threshold_min_deg: float = 20.0
    threshold_max_deg: float = 50.0
    hysteresis_deg: float = 4.0
    #: 有效帧至少要占「按送帧间隔推算的期望帧数」的多少。
    #: 默认 1/3 与 v0.6 的 8 帧 / 期望 24 帧等价，但换帧率后不会变松或变紧。
    min_valid_ratio: float = 1.0 / 3.0
    #: begin_window() 没给期望帧数时退回的绝对帧数门槛
    min_valid_frames_fallback: int = 8
    #: 无论比例算出多少，有效帧都不得少于这个数，避免窗很短时 1 帧就定生死
    min_valid_frames_floor: int = 2
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
        self._expected = 0

    @property
    def threshold(self) -> float:
        return threshold_for(self.baseline, self.cfg)

    def begin_window(self, expected_frames: int = 0) -> None:
        """expected_frames 是按送帧间隔推算的本窗期望帧数，0 表示未知（退回绝对帧数门槛）。"""
        self._valid.clear()
        self._total = 0
        self._misaligned = 0
        self._no_torso = 0
        self._counter = 0
        self._expected = max(0, expected_frames)

    def min_valid_frames(self) -> int:
        """本窗的有效帧门槛：期望帧数已知时按比例算，未知时退回绝对帧数。"""
        cfg = self.cfg
        floor = max(1, cfg.min_valid_frames_floor)
        if self._expected <= 0:
            return max(floor, cfg.min_valid_frames_fallback)
        return max(floor, round(self._expected * cfg.min_valid_ratio))

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
        if len(self._valid) < self.min_valid_frames():
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
