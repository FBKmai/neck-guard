package com.local.neckguard.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureAnalyzerTest {

    private val config = AnalyzerConfig(
        absoluteThresholdDeg = 40f,
        calibrationDeltaDeg = 12f,
        hysteresisDeg = 4f,
        minValidFramesFallback = 3,
        minValidFramesFloor = 1,
        consecutiveBadWindows = 2,
        cooldownMillis = 10_000L,
    )

    private fun measurement(neck: Float, torso: Float? = 5f) = PostureMeasurement(
        side = BodySide.LEFT,
        ear = PixelPoint(0f, 0f),
        shoulder = PixelPoint(0f, 10f),
        hip = if (torso == null) null else PixelPoint(0f, 20f),
        neckInclinationDeg = neck,
        torsoInclinationDeg = torso,
        shoulderOffsetRatio = 0.1f,
        aligned = true,
    )

    /** 跑一个窗：给定若干有效帧角度，返回结果。 */
    private fun PostureAnalyzer.runWindow(now: Long, vararg necks: Float, extra: List<FrameResult> = emptyList()): WindowOutcome {
        beginWindow()
        necks.forEach { addFrame(FrameResult.Valid(measurement(it))) }
        extra.forEach { addFrame(it) }
        return endWindow(now)
    }

    @Test
    fun median_oddAndEven() {
        assertEquals(3f, PostureAnalyzer.median(listOf(5f, 1f, 3f)), 1e-6f)
        assertEquals(2.5f, PostureAnalyzer.median(listOf(4f, 1f, 2f, 3f)), 1e-6f)
    }

    @Test
    fun threshold_absoluteWithoutBaseline_andClampedWithBaseline() {
        assertEquals(40f, PostureAnalyzer.thresholdFor(null, config), 1e-6f)
        assertEquals(37f, PostureAnalyzer.thresholdFor(25f, config), 1e-6f)
        // 默认 clamp 区间是 20..50（v0.5 起颈角相对躯干线，下限随之下调）
        assertEquals(20f, PostureAnalyzer.thresholdFor(2f, config), 1e-6f)
        assertEquals(50f, PostureAnalyzer.thresholdFor(45f, config), 1e-6f)
    }

    @Test
    fun defaultConfig_matchesTorsoRelativeThresholds() {
        val defaults = AnalyzerConfig()
        assertEquals(35f, defaults.absoluteThresholdDeg, 1e-6f)
        assertEquals(20f, defaults.thresholdMinDeg, 1e-6f)
        assertEquals(50f, defaults.thresholdMaxDeg, 1e-6f)
    }

    @Test
    fun invalidWindow_whenTooFewValidFrames_doesNotTouchStreak() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 50f, 50f, 50f)
        assertEquals(1, analyzer.badStreak)
        val outcome = analyzer.runWindow(1_000L, 50f, extra = listOf(FrameResult.LowVisibility, FrameResult.NoPerson))
        assertEquals(WindowVerdict.INVALID, outcome.summary.verdict)
        assertNull(outcome.summary.medianNeckDeg)
        assertEquals(1, analyzer.badStreak)
        assertEquals(3, outcome.summary.totalFrames)
        assertEquals(1, outcome.summary.validFrames)
    }

    @Test
    fun misalignedFrames_countedButNotValid() {
        val analyzer = PostureAnalyzer(config)
        val outcome = analyzer.runWindow(
            0L, 20f, 21f, 22f,
            extra = listOf(FrameResult.Misaligned(measurement(60f)), FrameResult.Misaligned(measurement(61f))),
        )
        assertEquals(WindowVerdict.GOOD, outcome.summary.verdict)
        assertEquals(2, outcome.summary.misalignedFrames)
        assertEquals(21f, outcome.summary.medianNeckDeg!!, 1e-6f)
    }

    @Test
    fun noTorsoFrames_countedButNotValid() {
        val analyzer = PostureAnalyzer(config)
        val outcome = analyzer.runWindow(
            0L, 20f, 21f, 22f,
            extra = listOf(FrameResult.NoTorso(measurement(60f)), FrameResult.NoTorso(measurement(61f))),
        )
        assertEquals(WindowVerdict.GOOD, outcome.summary.verdict)
        assertEquals(2, outcome.summary.noTorsoFrames)
        assertEquals(0, outcome.summary.misalignedFrames)
        assertEquals(3, outcome.summary.validFrames)
        assertEquals(5, outcome.summary.totalFrames)
        assertEquals(21f, outcome.summary.medianNeckDeg!!, 1e-6f)
    }

    @Test
    fun alertOnlyAfterKConsecutiveBadWindows() {
        val analyzer = PostureAnalyzer(config)
        val first = analyzer.runWindow(0L, 45f, 46f, 47f)
        assertEquals(WindowVerdict.BAD, first.summary.verdict)
        assertFalse(first.confirmed)
        assertFalse(first.shouldNotify)
        assertFalse(first.shouldRecord)

        val second = analyzer.runWindow(1_000L, 45f, 46f, 47f)
        assertTrue(second.confirmed)
        assertTrue(second.shouldNotify)
        assertTrue(second.shouldRecord)
        assertEquals(2, second.badStreak)
    }

    @Test
    fun goodWindowResetsStreak() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 45f, 46f, 47f)
        val good = analyzer.runWindow(1_000L, 20f, 21f, 22f)
        assertEquals(WindowVerdict.GOOD, good.summary.verdict)
        assertEquals(0, good.badStreak)
        val bad = analyzer.runWindow(2_000L, 45f, 46f, 47f)
        assertFalse(bad.confirmed)
    }

    @Test
    fun cooldownSuppressesNotification_butStillConfirmed() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 45f, 46f, 47f)
        val notify = analyzer.runWindow(1_000L, 45f, 46f, 47f)
        assertTrue(notify.shouldNotify)

        val suppressed = analyzer.runWindow(2_000L, 45f, 46f, 47f)
        assertTrue(suppressed.confirmed)
        assertFalse(suppressed.shouldNotify)
        assertFalse(suppressed.shouldRecord)

        val again = analyzer.runWindow(1_000L + 10_000L, 45f, 46f, 47f)
        assertTrue(again.shouldNotify)
        assertTrue(again.shouldRecord)
    }

    @Test
    fun hysteresis_staysBadUntilBelowThresholdMinusHysteresis() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 45f, 45f, 45f)
        assertTrue(analyzer.inForwardHead)
        // 38 在 36..40 之间：仍视为前倾
        val mid = analyzer.runWindow(1_000L, 38f, 38f, 38f)
        assertEquals(WindowVerdict.BAD, mid.summary.verdict)
        // 35 < 36：退出
        val exit = analyzer.runWindow(2_000L, 35f, 35f, 35f)
        assertEquals(WindowVerdict.GOOD, exit.summary.verdict)
        assertFalse(analyzer.inForwardHead)
        // 未处于前倾时 38 不进入
        val stay = analyzer.runWindow(3_000L, 38f, 38f, 38f)
        assertEquals(WindowVerdict.GOOD, stay.summary.verdict)
    }

    @Test
    fun calibration_setsBaselineAndThreshold_andResetsState() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 45f, 45f, 45f)
        assertEquals(1, analyzer.badStreak)
        val baseline = analyzer.calibrate(listOf(22f, 20f, 24f, 21f, 23f))
        assertEquals(22f, baseline!!, 1e-6f)
        assertEquals(22f, analyzer.baselineDeg!!, 1e-6f)
        assertEquals(34f, analyzer.thresholdDeg, 1e-6f)
        assertEquals(0, analyzer.badStreak)
        assertFalse(analyzer.inForwardHead)
        assertNull(analyzer.calibrate(emptyList()))
        assertEquals(22f, analyzer.baselineDeg!!, 1e-6f)
    }

    @Test
    fun representativeFrame_isClosestToMedian() {
        val analyzer = PostureAnalyzer(config)
        analyzer.beginWindow()
        val i0 = analyzer.addFrame(FrameResult.Valid(measurement(10f)))
        val i1 = analyzer.addFrame(FrameResult.LowVisibility)
        val i2 = analyzer.addFrame(FrameResult.Valid(measurement(30f)))
        val i3 = analyzer.addFrame(FrameResult.Valid(measurement(50f)))
        assertEquals(listOf(0, 1, 2, 3), listOf(i0, i1, i2, i3))
        val outcome = analyzer.endWindow(0L)
        assertEquals(30f, outcome.summary.medianNeckDeg!!, 1e-6f)
        assertEquals(2, outcome.summary.representativeFrameIndex)
        assertEquals(30f, outcome.summary.representative!!.neckInclinationDeg, 1e-6f)
    }

    @Test
    fun badFrames_onlyAboveThreshold_inOrder() {
        val analyzer = PostureAnalyzer(config)
        analyzer.beginWindow()
        analyzer.addFrame(FrameResult.Valid(measurement(45f)))
        analyzer.addFrame(FrameResult.Valid(measurement(30f)))
        analyzer.addFrame(FrameResult.LowVisibility)
        analyzer.addFrame(FrameResult.Valid(measurement(50f)))
        val outcome = analyzer.endWindow(0L)
        assertEquals(listOf(0 to 45f, 3 to 50f), outcome.summary.badFrames)
    }

    @Test
    fun pickBadFrames_samplesEvenly_whenTooMany() {
        val frames = (0 until 30).map { it to measurement(41f + it) }
        val picked = PostureAnalyzer.pickBadFrames(frames, 40f)
        assertEquals(PostureAnalyzer.MAX_BAD_FRAMES, picked.size)
        assertEquals(0, picked.first().first)
        assertEquals(25, picked.last().first)
        assertTrue(picked.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    @Test
    fun markRecovered_clearsForwardHeadAndStreak_butKeepsCooldown() {
        val analyzer = PostureAnalyzer(config)
        analyzer.runWindow(0L, 45f, 46f, 47f)
        val notify = analyzer.runWindow(1_000L, 45f, 46f, 47f)
        assertTrue(notify.shouldNotify)
        assertTrue(analyzer.inForwardHead)
        assertEquals(2, analyzer.badStreak)

        analyzer.markRecovered()
        assertFalse(analyzer.inForwardHead)
        assertEquals(0, analyzer.badStreak)
        assertEquals(1_000L, analyzer.lastNotifyAt!!.toLong())
        // 冷却仍在：恢复后立刻再连续两窗前倾只确认不通知
        analyzer.runWindow(2_000L, 45f, 46f, 47f)
        val again = analyzer.runWindow(3_000L, 45f, 46f, 47f)
        assertTrue(again.confirmed)
        assertFalse(again.shouldNotify)
    }

    @Test
    fun canNotify_respectsCooldown() {
        val analyzer = PostureAnalyzer(config)
        assertTrue(analyzer.canNotify(0L))
        analyzer.runWindow(0L, 45f, 46f, 47f)
        analyzer.runWindow(1_000L, 45f, 46f, 47f)
        assertFalse(analyzer.canNotify(5_000L))
        assertTrue(analyzer.canNotify(11_000L))
    }

    @Test
    fun exitThreshold_isThresholdMinusHysteresis() {
        val analyzer = PostureAnalyzer(config)
        assertEquals(36f, analyzer.exitThresholdDeg, 1e-6f)
        analyzer.setBaseline(25f)
        assertEquals(33f, analyzer.exitThresholdDeg, 1e-6f)
    }

    @Test
    fun windowProgress_countsValidAndTotal() {
        val analyzer = PostureAnalyzer(config)
        analyzer.beginWindow()
        assertEquals(0 to 0, analyzer.windowProgress())
        analyzer.addFrame(FrameResult.Valid(measurement(20f)))
        analyzer.addFrame(FrameResult.NoPerson)
        assertEquals(1 to 2, analyzer.windowProgress())
    }

    @Test
    fun minValidFrames_scalesWithExpectedFrameCount() {
        // 窗门槛按期望帧数的比例算：换帧率后松紧不变（v0.7）
        val cfg = AnalyzerConfig(
            minValidRatio = 1f / 3f,
            minValidFramesFallback = 8,
            minValidFramesFloor = 2,
        )
        val analyzer = PostureAnalyzer(cfg)
        analyzer.beginWindow(24)                   // 3 s @ 8 fps
        assertEquals(8, analyzer.minValidFrames())
        analyzer.beginWindow(90)                   // 3 s @ 30 fps
        assertEquals(30, analyzer.minValidFrames())
        analyzer.beginWindow(4)                    // 帧率极低时被下限兜住
        assertEquals(2, analyzer.minValidFrames())
        analyzer.beginWindow()                     // 期望帧数未知，退回绝对门槛
        assertEquals(8, analyzer.minValidFrames())
    }

    @Test
    fun window_invalidWhenValidFramesBelowRatio() {
        val cfg = AnalyzerConfig(
            absoluteThresholdDeg = 40f,
            minValidRatio = 1f / 3f,
            minValidFramesFloor = 1,
        )
        val analyzer = PostureAnalyzer(cfg)
        analyzer.beginWindow(24)                   // 门槛 8 帧
        repeat(7) { analyzer.addFrame(FrameResult.Valid(measurement(50f))) }
        assertEquals(WindowVerdict.INVALID, analyzer.endWindow(0L).summary.verdict)

        analyzer.beginWindow(24)
        repeat(8) { analyzer.addFrame(FrameResult.Valid(measurement(50f))) }
        assertEquals(WindowVerdict.BAD, analyzer.endWindow(0L).summary.verdict)
    }

    @Test
    fun torsoMedian_ignoresNulls() {
        val analyzer = PostureAnalyzer(config)
        analyzer.beginWindow()
        analyzer.addFrame(FrameResult.Valid(measurement(10f, torso = 3f)))
        analyzer.addFrame(FrameResult.Valid(measurement(10f, torso = null)))
        analyzer.addFrame(FrameResult.Valid(measurement(10f, torso = 7f)))
        val outcome = analyzer.endWindow(0L)
        assertEquals(5f, outcome.summary.medianTorsoDeg!!, 1e-6f)
    }
}
