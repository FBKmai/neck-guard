package com.local.neckguard.pose

import kotlin.math.abs

/** 判定参数，全部可在设置页调整。角度单位度，时间单位毫秒。 */
data class AnalyzerConfig(
    /**
     * 未校准时使用的绝对阈值。
     * v0.5 起颈角相对躯干线计算，伏案时躯干本身的前倾不再计入，所以比旧的竖直口径低。
     */
    val absoluteThresholdDeg: Float = 35f,
    /** 校准后阈值 = 基线 + delta。 */
    val calibrationDeltaDeg: Float = 12f,
    val thresholdMinDeg: Float = 20f,
    val thresholdMaxDeg: Float = 50f,
    /** 迟滞：进入前倾需 > 阈值，退出需 < 阈值 - hysteresis。 */
    val hysteresisDeg: Float = 4f,
    /**
     * 有效帧至少要占「按送帧间隔推算的期望帧数」的多少。
     * v0.7 起改用比例：默认 1/3 与旧的 8 帧 / 期望 24 帧等价，
     * 但换帧率（手机 8 fps、电脑 30 fps）后窗的松紧不再随之漂移。
     */
    val minValidRatio: Float = 1f / 3f,
    /** beginWindow 没给期望帧数时退回的绝对帧数门槛。 */
    val minValidFramesFallback: Int = 8,
    /** 无论比例算出多少，有效帧都不得少于这个数，避免窗很短时 1 帧就定生死。 */
    val minValidFramesFloor: Int = 2,
    /** 连续多少个 BAD 窗才确认前倾。 */
    val consecutiveBadWindows: Int = 2,
    /** 两次通知之间的最小间隔。 */
    val cooldownMillis: Long = 10L * 60L * 1000L,
)

enum class WindowVerdict { GOOD, BAD, INVALID }

data class WindowSummary(
    val verdict: WindowVerdict,
    val medianNeckDeg: Float?,
    val medianTorsoDeg: Float?,
    val thresholdDeg: Float,
    val totalFrames: Int,
    val validFrames: Int,
    val misalignedFrames: Int,
    /** 髋不可见、没有躯干参考线而被跳过的帧数。 */
    val noTorsoFrames: Int = 0,
    /** 角度最接近中位数的有效帧序号（addFrame 返回的序号），用于选快照。 */
    val representativeFrameIndex: Int?,
    val representative: PostureMeasurement?,
    /**
     * 本窗内角度超过阈值的有效帧（序号 -> 角度），按时间顺序，最多 MAX_BAD_FRAMES 个，
     * 用于在前倾事件里展示多帧截图。
     */
    val badFrames: List<Pair<Int, Float>> = emptyList(),
)

data class WindowOutcome(
    val summary: WindowSummary,
    val badStreak: Int,
    /** 已连续 K 个窗为 BAD。 */
    val confirmed: Boolean,
    /** 已确认且不在冷却期，需要发通知。 */
    val shouldNotify: Boolean,
    /** 需要写事件日志（首次确认或本次通知）。 */
    val shouldRecord: Boolean,
)

/**
 * 采样窗状态机：窗聚合 -> 迟滞判定 -> 连续计数 -> 冷却。
 * 纯 Kotlin，可单测。方法都加了 synchronized，因为 addFrame 来自 MediaPipe 回调线程，
 * beginWindow/endWindow 来自调度协程。
 */
class PostureAnalyzer(config: AnalyzerConfig = AnalyzerConfig()) {

    @Volatile
    var config: AnalyzerConfig = config

    var baselineDeg: Float? = null
        private set

    var inForwardHead: Boolean = false
        private set

    var badStreak: Int = 0
        private set

    var lastNotifyAt: Long? = null
        private set

    val thresholdDeg: Float
        get() = thresholdFor(baselineDeg, config)

    private val validFrames = ArrayList<Pair<Int, PostureMeasurement>>()
    private var totalFrames = 0
    private var misalignedFrames = 0
    private var noTorsoFrames = 0
    private var frameCounter = 0
    private var expectedFrames = 0

    /**
     * 开始一个新窗。[expectedFrames] 是按送帧间隔推算的本窗期望帧数，
     * 0 表示未知，此时窗有效性退回绝对帧数门槛。
     */
    @Synchronized
    fun beginWindow(expectedFrames: Int = 0) {
        validFrames.clear()
        totalFrames = 0
        misalignedFrames = 0
        noTorsoFrames = 0
        frameCounter = 0
        this.expectedFrames = expectedFrames.coerceAtLeast(0)
    }

    /** 本窗的有效帧门槛：期望帧数已知时按比例算，未知时退回绝对帧数。 */
    @Synchronized
    fun minValidFrames(): Int {
        val cfg = config
        val floor = cfg.minValidFramesFloor.coerceAtLeast(1)
        if (expectedFrames <= 0) return maxOf(floor, cfg.minValidFramesFallback)
        return maxOf(floor, Math.round(expectedFrames * cfg.minValidRatio))
    }

    /** 返回该帧在本窗内的序号，调用方可用它保存对应的图像以便之后取代表帧。 */
    @Synchronized
    fun addFrame(result: FrameResult): Int {
        val index = frameCounter++
        totalFrames++
        when (result) {
            is FrameResult.Valid -> validFrames.add(index to result.measurement)
            is FrameResult.Misaligned -> misalignedFrames++
            is FrameResult.NoTorso -> noTorsoFrames++
            FrameResult.LowVisibility, FrameResult.NoPerson -> Unit
        }
        return index
    }

    @Synchronized
    fun endWindow(nowMillis: Long): WindowOutcome {
        val cfg = config
        val threshold = thresholdFor(baselineDeg, cfg)

        if (validFrames.size < minValidFrames()) {
            val summary = WindowSummary(
                verdict = WindowVerdict.INVALID,
                medianNeckDeg = null,
                medianTorsoDeg = null,
                thresholdDeg = threshold,
                totalFrames = totalFrames,
                validFrames = validFrames.size,
                misalignedFrames = misalignedFrames,
                noTorsoFrames = noTorsoFrames,
                representativeFrameIndex = null,
                representative = null,
            )
            return WindowOutcome(summary, badStreak, confirmed = false, shouldNotify = false, shouldRecord = false)
        }

        val neckValues = validFrames.map { it.second.neckInclinationDeg }
        val medianNeck = median(neckValues)
        val torsoValues = validFrames.mapNotNull { it.second.torsoInclinationDeg }
        val medianTorso = if (torsoValues.isEmpty()) null else median(torsoValues)

        val representative = validFrames.minByOrNull { abs(it.second.neckInclinationDeg - medianNeck) }
        val badFrames = pickBadFrames(validFrames, threshold)

        // 迟滞判定
        inForwardHead = if (inForwardHead) {
            medianNeck >= threshold - cfg.hysteresisDeg
        } else {
            medianNeck > threshold
        }
        val verdict = if (inForwardHead) WindowVerdict.BAD else WindowVerdict.GOOD
        badStreak = if (verdict == WindowVerdict.BAD) badStreak + 1 else 0

        val confirmed = badStreak >= cfg.consecutiveBadWindows
        val cooldownPassed = lastNotifyAt?.let { nowMillis - it >= cfg.cooldownMillis } ?: true
        val shouldNotify = confirmed && cooldownPassed
        val shouldRecord = shouldNotify || badStreak == cfg.consecutiveBadWindows
        if (shouldNotify) lastNotifyAt = nowMillis

        val summary = WindowSummary(
            verdict = verdict,
            medianNeckDeg = medianNeck,
            medianTorsoDeg = medianTorso,
            thresholdDeg = threshold,
            totalFrames = totalFrames,
            validFrames = validFrames.size,
            misalignedFrames = misalignedFrames,
            noTorsoFrames = noTorsoFrames,
            representativeFrameIndex = representative?.first,
            representative = representative?.second,
            badFrames = badFrames,
        )
        return WindowOutcome(summary, badStreak, confirmed, shouldNotify, shouldRecord)
    }

    /** 用一组端坐时的颈部角度样本设定基线，返回基线；样本为空返回 null 且不改动。 */
    @Synchronized
    fun calibrate(samplesDeg: List<Float>): Float? {
        if (samplesDeg.isEmpty()) return null
        val baseline = median(samplesDeg)
        baselineDeg = baseline
        resetState()
        return baseline
    }

    @Synchronized
    fun setBaseline(deg: Float?) {
        baselineDeg = deg
        resetState()
    }

    /** 清空迟滞、连续计数与冷却，不清基线。 */
    @Synchronized
    fun resetState() {
        inForwardHead = false
        badStreak = 0
        lastNotifyAt = null
    }

    /**
     * 逐帧恢复判定通过后调用：退出前倾、清连续计数，但**保留冷却**（lastNotifyAt）。
     * 与 resetState 的区别就在冷却：恢复后短时间内再次前倾仍应受冷却压制，只记录不重复提醒。
     */
    @Synchronized
    fun markRecovered() {
        inForwardHead = false
        badStreak = 0
    }

    /** 距上次通知是否已超过冷却时间。从未通知过返回 true。 */
    @Synchronized
    fun canNotify(nowMillis: Long): Boolean =
        lastNotifyAt?.let { nowMillis - it >= config.cooldownMillis } ?: true

    /** 退出前倾的角度线 = 阈值 - 迟滞。低于它才算恢复端正。 */
    val exitThresholdDeg: Float
        get() {
            val cfg = config
            return thresholdFor(baselineDeg, cfg) - cfg.hysteresisDeg
        }

    /** 当前窗进度（有效帧数, 总帧数），供 UI 显示。 */
    @Synchronized
    fun windowProgress(): Pair<Int, Int> = validFrames.size to totalFrames

    companion object {
        const val MAX_BAD_FRAMES = 6

        /**
         * 从有效帧里挑出超过阈值的帧，超过 MAX_BAD_FRAMES 时按时间均匀抽样，保证覆盖整个窗。
         */
        fun pickBadFrames(frames: List<Pair<Int, PostureMeasurement>>, thresholdDeg: Float): List<Pair<Int, Float>> {
            val bad = frames.filter { it.second.neckInclinationDeg > thresholdDeg }
                .map { it.first to it.second.neckInclinationDeg }
            if (bad.size <= MAX_BAD_FRAMES) return bad
            val step = bad.size.toFloat() / MAX_BAD_FRAMES
            return (0 until MAX_BAD_FRAMES).map { bad[(it * step).toInt().coerceIn(0, bad.size - 1)] }
        }

        fun thresholdFor(baselineDeg: Float?, config: AnalyzerConfig): Float =
            if (baselineDeg == null) {
                config.absoluteThresholdDeg
            } else {
                (baselineDeg + config.calibrationDeltaDeg).coerceIn(config.thresholdMinDeg, config.thresholdMaxDeg)
            }

        fun median(values: List<Float>): Float {
            require(values.isNotEmpty()) { "median of empty list" }
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }
    }
}
