package com.local.neckguard.pose

/** 检测节奏：巡检（低帧率常开）与确认（高帧率短时）。 */
enum class TrackerMode { SLOW, FAST }

/** 模式切换的原因，用于事件日志与 UI 提示。 */
enum class ModeChangeReason {
    /** 巡检中连续多帧超阈值，进入确认。 */
    TRIGGERED,

    /** 已确认前倾且冷却已过，仍未恢复，再次进入确认以便再提醒。 */
    RETRIGGERED,

    /** 连续 K 个确认窗判为前倾，已确认。 */
    CONFIRMED,

    /** 确认窗判为正常，回到巡检。 */
    GOOD_WINDOW,

    /** 确认窗连续无效（画面里没人或看不清），退回巡检省电。 */
    TOO_MANY_INVALID,

    /** 外部重置（改基线、停止等）。 */
    RESET,
}

/**
 * 双速状态机参数。时间单位毫秒。
 *
 * 巡检期以 slowIntervalMillis 送帧，只做逐帧计数不进窗聚合；
 * 一旦连续 triggerFrames 个有效帧超阈值就切到 fastIntervalMillis，
 * 用 confirmWindowMillis 的窗复用 PostureAnalyzer 的中位数 + 迟滞 + 连续窗判定。
 */
data class TrackerConfig(
    /** 巡检模式最小送帧间隔，默认 700 ms（约 1.4 fps）。 */
    val slowIntervalMillis: Long = 700L,
    /** 确认模式最小送帧间隔，默认 125 ms（约 8 fps）。 */
    val fastIntervalMillis: Long = 125L,
    /** 一个确认窗的时长。 */
    val confirmWindowMillis: Long = 3_000L,
    /** 巡检下连续多少个有效帧超过阈值才进入确认模式。 */
    val triggerFrames: Int = 2,
    /** 前倾中连续多少个有效帧低于退出线才算恢复。 */
    val recoverFrames: Int = 5,
    /** 确认后至少隔这么久才允许再次进入确认（还需通知冷却也已过）。 */
    val retriggerHoldMillis: Long = 30_000L,
    /** 确认模式内连续多少个无效窗就退回巡检。 */
    val maxInvalidWindows: Int = 2,
) {
    /** 防御性夹取，避免设置页写入 0 或负数导致空转。 */
    fun sanitized(): TrackerConfig = copy(
        slowIntervalMillis = slowIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, 10_000L),
        fastIntervalMillis = fastIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, 5_000L),
        confirmWindowMillis = confirmWindowMillis.coerceIn(500L, 60_000L),
        triggerFrames = triggerFrames.coerceAtLeast(1),
        recoverFrames = recoverFrames.coerceAtLeast(1),
        retriggerHoldMillis = retriggerHoldMillis.coerceAtLeast(0L),
        maxInvalidWindows = maxInvalidWindows.coerceAtLeast(1),
    )

    companion object {
        const val MIN_INTERVAL_MILLIS = 30L
    }
}

/** 状态机对外抛出的事件。调用方在锁外顺序处理，处理期间不得回调 tracker 的写方法。 */
sealed class TrackerEvent {
    /**
     * 该帧已计入当前确认窗。调用方据此只在确认窗内缓存截图。
     * windowSeq 用于区分背靠背的相邻窗（index 在每个窗内从 0 重新计数）。
     */
    data class FrameAdded(val windowSeq: Long, val index: Int, val result: FrameResult) : TrackerEvent()

    data class ModeChanged(
        val from: TrackerMode,
        val to: TrackerMode,
        val reason: ModeChangeReason,
        val neckDeg: Float?,
    ) : TrackerEvent()

    /** 一个确认窗结束。outcome 的 shouldRecord / shouldNotify 语义与 PostureAnalyzer 一致。 */
    data class WindowEnded(
        val windowSeq: Long,
        val outcome: WindowOutcome,
        val consecutiveInvalid: Int,
    ) : TrackerEvent()

    /** 从"前倾中"恢复到端正。forwardHeadMillis 是从确认到恢复的持续时长。 */
    data class Recovered(val neckDeg: Float, val forwardHeadMillis: Long) : TrackerEvent()
}

/** 供 UI 读取的只读快照。 */
data class TrackerSnapshot(
    val mode: TrackerMode,
    val forwardHead: Boolean,
    val badStreak: Int,
    val triggerProgress: Int,
    val recoverProgress: Int,
    val windowSeq: Long,
    val windowStartedAtMillis: Long?,
    val windowValidFrames: Int,
    val windowTotalFrames: Int,
    val confirmedAtMillis: Long?,
    val thresholdDeg: Float,
    val baselineDeg: Float?,
)

/**
 * 巡检 / 确认双速状态机，包装 PostureAnalyzer。
 *
 * 三个状态：
 * - S0：SLOW 且未前倾，逐帧看有没有超阈值；
 * - FAST：确认窗连跑，窗聚合与判定全部交给 PostureAnalyzer；
 * - S2：SLOW 且已确认前倾，等待恢复或冷却后重新确认。
 *
 * 线程安全：所有公开方法 @Synchronized；analyzer 为私有且只在本类锁内调用，
 * 锁序固定为 tracker -> analyzer，不会与外部形成环。
 * desiredIntervalMillis 与 mode 为 @Volatile，推理引擎可无锁读取。
 */
class PostureTracker(
    analyzerConfig: AnalyzerConfig = AnalyzerConfig(),
    trackerConfig: TrackerConfig = TrackerConfig(),
) {
    private val analyzer = PostureAnalyzer(analyzerConfig)

    @Volatile
    var config: TrackerConfig = trackerConfig.sanitized()
        private set

    /** 推理引擎应使用的最小送帧间隔，随模式切换即时变化。 */
    @Volatile
    var desiredIntervalMillis: Long = config.slowIntervalMillis
        private set

    @Volatile
    var mode: TrackerMode = TrackerMode.SLOW
        private set

    private var triggerCount = 0
    private var recoverCount = 0
    private var consecutiveInvalid = 0
    private var windowSeq = 0L
    private var windowStartedAt: Long? = null
    private var confirmedAt: Long? = null

    val thresholdDeg: Float get() = analyzer.thresholdDeg
    val exitThresholdDeg: Float get() = analyzer.exitThresholdDeg
    val baselineDeg: Float? get() = analyzer.baselineDeg
    val inForwardHead: Boolean get() = analyzer.inForwardHead
    val badStreak: Int get() = analyzer.badStreak
    val lastNotifyAt: Long? get() = analyzer.lastNotifyAt

    /** 处理一帧。返回的事件列表由调用方在锁外顺序处理。 */
    @Synchronized
    fun onFrame(result: FrameResult, nowMillis: Long): List<TrackerEvent> {
        val events = ArrayList<TrackerEvent>(2)
        // 先结算已到期的窗，本帧再计入新窗，避免窗被一帧拖长
        if (mode == TrackerMode.FAST && windowExpired(nowMillis)) endWindowInto(events, nowMillis)
        when (mode) {
            TrackerMode.FAST -> {
                val index = analyzer.addFrame(result)
                events.add(TrackerEvent.FrameAdded(windowSeq, index, result))
            }
            TrackerMode.SLOW -> handleSlowFrame(result, nowMillis, events)
        }
        return events
    }

    /** 定时驱动：确认窗内长时间没有帧时也能让窗到期（结果为 INVALID）。 */
    @Synchronized
    fun tick(nowMillis: Long): List<TrackerEvent> {
        if (mode != TrackerMode.FAST || !windowExpired(nowMillis)) return emptyList()
        val events = ArrayList<TrackerEvent>(2)
        endWindowInto(events, nowMillis)
        return events
    }

    @Synchronized
    fun updateConfig(analyzerConfig: AnalyzerConfig, trackerConfig: TrackerConfig) {
        analyzer.config = analyzerConfig
        config = trackerConfig.sanitized()
        desiredIntervalMillis = intervalFor(mode)
    }

    @Synchronized
    fun setBaseline(deg: Float?) {
        analyzer.setBaseline(deg)
        resetInternal()
    }

    /** 外部重置：回到巡检、清空所有计数与冷却。 */
    @Synchronized
    fun reset(): List<TrackerEvent> {
        val events = ArrayList<TrackerEvent>(1)
        val wasFast = mode == TrackerMode.FAST
        analyzer.resetState()
        resetInternal()
        if (wasFast) {
            events.add(TrackerEvent.ModeChanged(TrackerMode.FAST, TrackerMode.SLOW, ModeChangeReason.RESET, null))
        }
        return events
    }

    @Synchronized
    fun snapshot(): TrackerSnapshot {
        val progress = analyzer.windowProgress()
        return TrackerSnapshot(
            mode = mode,
            forwardHead = analyzer.inForwardHead,
            badStreak = analyzer.badStreak,
            triggerProgress = triggerCount,
            recoverProgress = recoverCount,
            windowSeq = windowSeq,
            windowStartedAtMillis = windowStartedAt,
            windowValidFrames = if (mode == TrackerMode.FAST) progress.first else 0,
            windowTotalFrames = if (mode == TrackerMode.FAST) progress.second else 0,
            confirmedAtMillis = confirmedAt,
            thresholdDeg = analyzer.thresholdDeg,
            baselineDeg = analyzer.baselineDeg,
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun resetInternal() {
        mode = TrackerMode.SLOW
        desiredIntervalMillis = config.slowIntervalMillis
        triggerCount = 0
        recoverCount = 0
        consecutiveInvalid = 0
        windowStartedAt = null
        confirmedAt = null
    }

    private fun intervalFor(m: TrackerMode): Long =
        if (m == TrackerMode.FAST) config.fastIntervalMillis else config.slowIntervalMillis

    private fun windowExpired(nowMillis: Long): Boolean {
        val started = windowStartedAt ?: return false
        return nowMillis - started >= config.confirmWindowMillis
    }

    /** 巡检帧：只逐帧计数，不进窗聚合。 */
    private fun handleSlowFrame(result: FrameResult, nowMillis: Long, events: MutableList<TrackerEvent>) {
        val measurement = (result as? FrameResult.Valid)?.measurement
        if (measurement == null) {
            // 未对齐 / 看不清 / 没人：两个方向的连续计数都清零，避免跨越无效段累计
            triggerCount = 0
            recoverCount = 0
            return
        }
        val neck = measurement.neckInclinationDeg
        if (!analyzer.inForwardHead) {
            // S0：正常巡检
            recoverCount = 0
            if (neck > analyzer.thresholdDeg) {
                triggerCount++
                if (triggerCount >= config.triggerFrames) {
                    enterFast(ModeChangeReason.TRIGGERED, neck, nowMillis, events)
                }
            } else {
                triggerCount = 0
            }
            return
        }
        // S2：已确认前倾，等恢复或冷却后重新确认
        if (neck < analyzer.exitThresholdDeg) {
            triggerCount = 0
            recoverCount++
            if (recoverCount >= config.recoverFrames) {
                analyzer.markRecovered()
                // 只有确认过（用户已经收到提醒）才报恢复，否则"已恢复"会来得莫名其妙
                confirmedAt?.let { events.add(TrackerEvent.Recovered(neck, nowMillis - it)) }
                recoverCount = 0
                confirmedAt = null
            }
            return
        }
        recoverCount = 0
        val holdPassed = confirmedAt?.let { nowMillis - it >= config.retriggerHoldMillis } ?: true
        if (holdPassed && analyzer.canNotify(nowMillis)) {
            triggerCount++
            if (triggerCount >= config.triggerFrames) {
                enterFast(ModeChangeReason.RETRIGGERED, neck, nowMillis, events)
            }
        } else {
            // 迟滞带内或冷却未过：保持前倾状态但不做无谓的高帧率确认
            triggerCount = 0
        }
    }

    private fun enterFast(reason: ModeChangeReason, neckDeg: Float?, nowMillis: Long, events: MutableList<TrackerEvent>) {
        triggerCount = 0
        recoverCount = 0
        consecutiveInvalid = 0
        beginWindow(nowMillis)
        mode = TrackerMode.FAST
        desiredIntervalMillis = config.fastIntervalMillis
        events.add(TrackerEvent.ModeChanged(TrackerMode.SLOW, TrackerMode.FAST, reason, neckDeg))
    }

    private fun leaveFast(reason: ModeChangeReason, neckDeg: Float?, events: MutableList<TrackerEvent>) {
        windowStartedAt = null
        mode = TrackerMode.SLOW
        desiredIntervalMillis = config.slowIntervalMillis
        triggerCount = 0
        recoverCount = 0
        events.add(TrackerEvent.ModeChanged(TrackerMode.FAST, TrackerMode.SLOW, reason, neckDeg))
    }

    private fun beginWindow(nowMillis: Long) {
        windowSeq++
        windowStartedAt = nowMillis
        analyzer.beginWindow()
    }

    private fun endWindowInto(events: MutableList<TrackerEvent>, nowMillis: Long) {
        val wasForwardHead = analyzer.inForwardHead
        val seq = windowSeq
        val outcome = analyzer.endWindow(nowMillis)
        when (outcome.summary.verdict) {
            WindowVerdict.INVALID -> {
                consecutiveInvalid++
                events.add(TrackerEvent.WindowEnded(seq, outcome, consecutiveInvalid))
                if (consecutiveInvalid >= config.maxInvalidWindows) {
                    leaveFast(ModeChangeReason.TOO_MANY_INVALID, null, events)
                } else {
                    beginWindow(nowMillis)
                }
            }
            WindowVerdict.GOOD -> {
                consecutiveInvalid = 0
                events.add(TrackerEvent.WindowEnded(seq, outcome, 0))
                val median = outcome.summary.medianNeckDeg
                val since = confirmedAt
                if (wasForwardHead && median != null && since != null) {
                    events.add(TrackerEvent.Recovered(median, nowMillis - since))
                }
                confirmedAt = null
                leaveFast(ModeChangeReason.GOOD_WINDOW, median, events)
            }
            WindowVerdict.BAD -> {
                consecutiveInvalid = 0
                events.add(TrackerEvent.WindowEnded(seq, outcome, 0))
                if (outcome.confirmed) {
                    confirmedAt = nowMillis
                    leaveFast(ModeChangeReason.CONFIRMED, outcome.summary.medianNeckDeg, events)
                } else {
                    // 还没连够 K 个窗，背靠背再开一窗
                    beginWindow(nowMillis)
                }
            }
        }
    }
}
