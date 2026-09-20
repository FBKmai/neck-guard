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
        minValidFramesPerWindow = 3,
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
        assertEquals(30f, PostureAnalyzer.thresholdFor(10f, config), 1e-6f)
        assertEquals(50f, PostureAnalyzer.thresholdFor(45f, config), 1e-6f)
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
