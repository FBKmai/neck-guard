package com.local.neckguard.pose

import kotlin.math.abs

/** 判定参数，全部可在设置页调整。角度单位度，时间单位毫秒。 */
data class AnalyzerConfig(
    /** 未校准时使用的绝对阈值。 */
    val absoluteThresholdDeg: Float = 40f,
    /** 校准后阈值 = 基线 + delta。 */
    val calibrationDeltaDeg: Float = 12f,
    val thresholdMinDeg: Float = 30f,
    val thresholdMaxDeg: Float = 50f,
    /** 迟滞：进入前倾需 > 阈值，退出需 < 阈值 - hysteresis。 */
    val hysteresisDeg: Float = 4f,
    /** 一个采样窗内至少多少个有效帧才算有效窗。 */
    val minValidFramesPerWindow: Int = 8,
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
    /** 角度最接近中位数的有效帧序号（addFrame 返回的序号），用于选快照。 */
    val representativeFrameIndex: Int?,
    val representative: PostureMeasurement?,
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
    private var frameCounter = 0

    @Synchronized
    fun beginWindow() {
        validFrames.clear()
        totalFrames = 0
        misalignedFrames = 0
        frameCounter = 0
    }

    /** 返回该帧在本窗内的序号，调用方可用它保存对应的图像以便之后取代表帧。 */
    @Synchronized
    fun addFrame(result: FrameResult): Int {
        val index = frameCounter++
        totalFrames++
        when (result) {
            is FrameResult.Valid -> validFrames.add(index to result.measurement)
            is FrameResult.Misaligned -> misalignedFrames++
            FrameResult.LowVisibility, FrameResult.NoPerson -> Unit
        }
        return index
    }

    @Synchronized
    fun endWindow(nowMillis: Long): WindowOutcome {
        val cfg = config
        val threshold = thresholdFor(baselineDeg, cfg)

        if (validFrames.size < cfg.minValidFramesPerWindow) {
            val summary = WindowSummary(
                verdict = WindowVerdict.INVALID,
                medianNeckDeg = null,
                medianTorsoDeg = null,
                thresholdDeg = threshold,
                totalFrames = totalFrames,
                validFrames = validFrames.size,
                misalignedFrames = misalignedFrames,
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
            representativeFrameIndex = representative?.first,
            representative = representative?.second,
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

    companion object {
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
