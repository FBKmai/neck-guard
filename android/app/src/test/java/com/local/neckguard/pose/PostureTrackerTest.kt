package com.local.neckguard.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureTrackerTest {

    private val analyzerConfig = AnalyzerConfig(
        absoluteThresholdDeg = 40f,
        calibrationDeltaDeg = 12f,
        hysteresisDeg = 4f,
        // 窗内期望 24 帧（3000/125），按 1/8 得有效帧门槛 3，与 v0.6 用例的绝对值一致
        minValidRatio = 1f / 8f,
        minValidFramesFallback = 3,
        minValidFramesFloor = 1,
        consecutiveBadWindows = 2,
        cooldownMillis = 10_000L,
    )

    private val trackerConfig = TrackerConfig(
        slowIntervalMillis = 700L,
        fastIntervalMillis = 125L,
        confirmWindowMillis = 3_000L,
        // 原来的 triggerFrames = 2 / recoverFrames = 3 在 700 ms 巡检下等价于这两个时长
        triggerMillis = 700L,
        recoverMillis = 1_400L,
        invalidGraceMillis = 1_000L,
        retriggerHoldMillis = 5_000L,
        maxInvalidWindows = 2,
    )

    private fun tracker() = PostureTracker(analyzerConfig, trackerConfig)

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

    private fun valid(neck: Float) = FrameResult.Valid(measurement(neck))

    /** 送一帧，返回事件。 */
    private fun PostureTracker.feed(nowMillis: Long, neck: Float) = onFrame(valid(neck), nowMillis)

    /**
     * 跑完一个确认窗：从 startMillis 起按 fastInterval 送若干帧，然后 tick 让窗到期。
     * 返回窗结束事件。调用前 tracker 必须已处于 FAST。
     */
    private fun PostureTracker.runFastWindow(startMillis: Long, vararg necks: Float): TrackerEvent.WindowEnded {
        necks.forEachIndexed { i, neck -> onFrame(valid(neck), startMillis + i * 125L) }
        val events = tick(startMillis + trackerConfig.confirmWindowMillis)
        return events.filterIsInstance<TrackerEvent.WindowEnded>().single()
    }

    /** 从巡检状态触发进入 FAST，返回进入时刻。 */
    private fun PostureTracker.triggerFast(startMillis: Long, neck: Float = 50f): Long {
        feed(startMillis, neck)
        val events = feed(startMillis + 700L, neck)
        assertTrue("应已进入确认模式", events.any { it is TrackerEvent.ModeChanged })
        return startMillis + 700L
    }

    @Test
    fun slowFrames_belowThreshold_produceNoEvents() {
        val t = tracker()
        assertTrue(t.feed(0L, 20f).isEmpty())
        assertTrue(t.feed(700L, 25f).isEmpty())
        assertEquals(TrackerMode.SLOW, t.mode)
        assertEquals(700L, t.desiredIntervalMillis)
    }

    @Test
    fun trigger_afterConsecutiveFramesAboveThreshold() {
        val t = tracker()
        assertTrue(t.feed(0L, 50f).isEmpty())
        val events = t.feed(700L, 52f)
        val change = events.filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(TrackerMode.FAST, change.to)
        assertEquals(ModeChangeReason.TRIGGERED, change.reason)
        assertEquals(52f, change.neckDeg!!, 1e-6f)
        assertEquals(TrackerMode.FAST, t.mode)
        assertEquals(125L, t.desiredIntervalMillis)
    }

    @Test
    fun triggerTimer_resetByGoodFrame() {
        val t = tracker()
        t.feed(0L, 50f)
        t.feed(700L, 20f)
        assertTrue("低于阈值的正常帧应清零触发计时", t.feed(1_400L, 50f).isEmpty())
        assertEquals(TrackerMode.SLOW, t.mode)
    }

    @Test
    fun triggerTimer_clearedAfterInvalidGraceExpires() {
        // 超过宽限期的无效段才清零；宽限期内只挂起，见 shortInvalidGap_suspendsTimer
        val t2 = tracker()
        t2.feed(0L, 50f)
        t2.onFrame(FrameResult.LowVisibility, 700L)
        t2.onFrame(FrameResult.LowVisibility, 1_800L)      // 距首个无效帧 1100 ms
        assertTrue("看不清超过宽限期应清零触发计时", t2.feed(2_000L, 50f).isEmpty())
        assertEquals(TrackerMode.SLOW, t2.mode)

        val t3 = tracker()
        t3.feed(0L, 50f)
        t3.onFrame(FrameResult.Misaligned(measurement(50f)), 700L)
        t3.onFrame(FrameResult.Misaligned(measurement(50f)), 1_800L)
        assertTrue("未对齐超过宽限期应清零触发计时", t3.feed(2_000L, 50f).isEmpty())
    }

    @Test
    fun fastFrames_produceFrameAdded_withSameWindowSeq() {
        val t = tracker()
        val start = t.triggerFast(0L)
        val a = t.feed(start + 125L, 50f).filterIsInstance<TrackerEvent.FrameAdded>().single()
        val b = t.feed(start + 250L, 51f).filterIsInstance<TrackerEvent.FrameAdded>().single()
        assertEquals(a.windowSeq, b.windowSeq)
        assertEquals(0, a.index)
        assertEquals(1, b.index)
    }

    @Test
    fun fastWindow_expiresOnTick_invalidTwiceGoesBackToSlow() {
        val t = tracker()
        val start = t.triggerFast(0L)

        val first = t.tick(start + 3_000L)
        val ended = first.filterIsInstance<TrackerEvent.WindowEnded>().single()
        assertEquals(WindowVerdict.INVALID, ended.outcome.summary.verdict)
        assertEquals(1, ended.consecutiveInvalid)
        assertEquals("首个无效窗后仍应留在确认模式", TrackerMode.FAST, t.mode)

        val second = t.tick(start + 6_000L)
        val ended2 = second.filterIsInstance<TrackerEvent.WindowEnded>().single()
        assertEquals(2, ended2.consecutiveInvalid)
        val change = second.filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(ModeChangeReason.TOO_MANY_INVALID, change.reason)
        assertEquals(TrackerMode.SLOW, t.mode)
        assertEquals(700L, t.desiredIntervalMillis)
    }

    @Test
    fun frameAfterExpiry_endsOldWindow_andStartsNewOne() {
        val t = tracker()
        val start = t.triggerFast(0L)
        // 窗内先放三帧，保证窗有效
        val firstSeq = t.feed(start + 125L, 50f).filterIsInstance<TrackerEvent.FrameAdded>().single().windowSeq
        t.feed(start + 250L, 50f)
        t.feed(start + 375L, 50f)

        val events = t.feed(start + 3_100L, 50f)
        val ended = events.filterIsInstance<TrackerEvent.WindowEnded>().single()
        assertEquals(firstSeq, ended.windowSeq)
        val added = events.filterIsInstance<TrackerEvent.FrameAdded>().single()
        assertEquals("到期后的帧应计入新窗", firstSeq + 1, added.windowSeq)
        assertEquals(0, added.index)
    }

    @Test
    fun confirm_afterTwoBadWindows_returnsToSlowInForwardHead() {
        val t = tracker()
        val start = t.triggerFast(0L)

        val first = t.runFastWindow(start, 50f, 51f, 52f)
        assertEquals(WindowVerdict.BAD, first.outcome.summary.verdict)
        assertFalse(first.outcome.confirmed)
        assertEquals("未确认前应继续下一个确认窗", TrackerMode.FAST, t.mode)

        val second = t.runFastWindow(start + 3_000L, 50f, 51f, 52f)
        assertTrue(second.outcome.confirmed)
        assertTrue(second.outcome.shouldNotify)
        assertEquals(TrackerMode.SLOW, t.mode)
        assertTrue(t.inForwardHead)
        assertNotNull(t.snapshot().confirmedAtMillis)
    }

    @Test
    fun goodWindow_interruptsStreak_andBacksToSlow() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        assertEquals(1, t.badStreak)

        val good = t.runFastWindow(start + 3_000L, 20f, 21f, 22f)
        assertEquals(WindowVerdict.GOOD, good.outcome.summary.verdict)
        assertEquals(0, t.badStreak)
        assertEquals(TrackerMode.SLOW, t.mode)
        assertFalse(t.inForwardHead)
    }

    @Test
    fun goodWindow_whileForwardHead_emitsRecovered() {
        val t = tracker()
        // 先确认一次前倾
        var start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        t.runFastWindow(start + 3_000L, 50f, 51f, 52f)
        assertTrue(t.inForwardHead)

        // 冷却已过后重触发，然后给一个 GOOD 窗
        val base = 20_000L
        t.feed(base, 50f)
        t.feed(base + 700L, 50f)
        assertEquals(TrackerMode.FAST, t.mode)
        start = base + 700L
        t.onFrame(valid(20f), start + 125L)
        t.onFrame(valid(21f), start + 250L)
        t.onFrame(valid(22f), start + 375L)
        val events = t.tick(start + 3_000L)
        assertEquals(WindowVerdict.GOOD, events.filterIsInstance<TrackerEvent.WindowEnded>().single().outcome.summary.verdict)
        assertNotNull(events.filterIsInstance<TrackerEvent.Recovered>().singleOrNull())
        assertFalse(t.inForwardHead)
    }

    @Test
    fun recovery_afterFramesBelowExitThreshold_keepsCooldown() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        val confirmWindow = t.runFastWindow(start + 3_000L, 50f, 51f, 52f)
        assertTrue(confirmWindow.outcome.shouldNotify)
        val notifyAt = t.lastNotifyAt
        assertNotNull(notifyAt)

        // 阈值 40，迟滞 4，退出线 36；连续 3 帧 35 度才算恢复
        val base = 7_000L
        assertTrue(t.feed(base, 35f).isEmpty())
        assertTrue(t.feed(base + 700L, 34f).isEmpty())
        val events = t.feed(base + 1_400L, 33f)
        val recovered = events.filterIsInstance<TrackerEvent.Recovered>().single()
        assertEquals(33f, recovered.neckDeg, 1e-6f)
        assertFalse(t.inForwardHead)
        assertEquals("恢复不应清掉冷却", notifyAt, t.lastNotifyAt)
    }

    @Test
    fun noRecovery_withinHysteresisBand() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        t.runFastWindow(start + 3_000L, 50f, 51f, 52f)
        assertTrue(t.inForwardHead)

        // 38 在 36..40 之间：既不恢复，也因冷却未过而不重触发
        var now = 7_000L
        repeat(6) {
            assertTrue(t.feed(now, 38f).isEmpty())
            now += 700L
        }
        assertTrue(t.inForwardHead)
        assertEquals(TrackerMode.SLOW, t.mode)
    }

    @Test
    fun retrigger_blockedByHold_andByCooldown() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        val confirmedAt = start + 3_000L + 3_000L
        t.runFastWindow(start + 3_000L, 50f, 51f, 52f)

        // hold 5 s 未过
        assertTrue(t.feed(confirmedAt + 1_000L, 50f).isEmpty())
        assertTrue(t.feed(confirmedAt + 1_700L, 50f).isEmpty())
        assertEquals(TrackerMode.SLOW, t.mode)

        // hold 已过但冷却 10 s 未过
        assertTrue(t.feed(confirmedAt + 5_500L, 50f).isEmpty())
        assertTrue(t.feed(confirmedAt + 6_200L, 50f).isEmpty())
        assertEquals(TrackerMode.SLOW, t.mode)
    }

    @Test
    fun retrigger_afterCooldown_confirmsInOneWindow() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.runFastWindow(start, 50f, 51f, 52f)
        t.runFastWindow(start + 3_000L, 50f, 51f, 52f)
        val notifyAt = t.lastNotifyAt!!

        val base = notifyAt + 10_100L
        t.feed(base, 50f)
        val change = t.feed(base + 700L, 50f).filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(ModeChangeReason.RETRIGGERED, change.reason)

        // badStreak 未被清零，一个 BAD 窗即可再次确认并提醒
        val window = t.runFastWindow(base + 700L, 50f, 51f, 52f)
        assertTrue(window.outcome.confirmed)
        assertTrue(window.outcome.shouldNotify)
    }

    @Test
    fun desiredInterval_followsModeAndConfigUpdate() {
        val t = tracker()
        assertEquals(700L, t.desiredIntervalMillis)
        t.updateConfig(analyzerConfig, trackerConfig.copy(slowIntervalMillis = 500L))
        assertEquals(500L, t.desiredIntervalMillis)

        t.feed(0L, 50f)
        t.feed(500L, 50f)
        assertEquals(TrackerMode.FAST, t.mode)
        t.updateConfig(analyzerConfig, trackerConfig.copy(slowIntervalMillis = 500L, fastIntervalMillis = 100L))
        assertEquals(100L, t.desiredIntervalMillis)
    }

    @Test
    fun reset_duringFast_emitsModeChangedReset() {
        val t = tracker()
        t.triggerFast(0L)
        val events = t.reset()
        val change = events.filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(ModeChangeReason.RESET, change.reason)
        assertEquals(TrackerMode.SLOW, t.mode)
        assertEquals(700L, t.desiredIntervalMillis)
        assertTrue(t.reset().isEmpty())
    }

    @Test
    fun setBaseline_updatesThresholdAndResets() {
        val t = tracker()
        t.triggerFast(0L)
        t.setBaseline(25f)
        assertEquals(37f, t.thresholdDeg, 1e-6f)
        assertEquals(33f, t.exitThresholdDeg, 1e-6f)
        assertEquals(TrackerMode.SLOW, t.mode)
        assertNull(t.snapshot().confirmedAtMillis)
    }

    @Test
    fun snapshot_reflectsWindowProgress() {
        val t = tracker()
        val start = t.triggerFast(0L)
        t.feed(start + 125L, 50f)
        t.onFrame(FrameResult.NoPerson, start + 250L)
        val snap = t.snapshot()
        assertEquals(TrackerMode.FAST, snap.mode)
        assertEquals(1, snap.windowValidFrames)
        assertEquals(2, snap.windowTotalFrames)
        assertEquals(40f, snap.thresholdDeg, 1e-6f)
    }

    @Test
    fun config_sanitizedAgainstBadInput() {
        val bad = TrackerConfig(
            slowIntervalMillis = 0L,
            fastIntervalMillis = -5L,
            confirmWindowMillis = 10L,
            triggerMillis = -1L,
            recoverMillis = -1L,
            invalidGraceMillis = -1L,
            retriggerHoldMillis = -1L,
            maxInvalidWindows = 0,
        ).sanitized()
        assertEquals(TrackerConfig.MIN_INTERVAL_MILLIS, bad.slowIntervalMillis)
        assertEquals(TrackerConfig.MIN_INTERVAL_MILLIS, bad.fastIntervalMillis)
        assertEquals(500L, bad.confirmWindowMillis)
        assertEquals(0L, bad.triggerMillis)
        assertEquals(0L, bad.recoverMillis)
        assertEquals(0L, bad.invalidGraceMillis)
        assertEquals(0L, bad.retriggerHoldMillis)
        assertEquals(1, bad.maxInvalidWindows)
    }

    @Test
    fun expectedFramesPerWindow_followsFastInterval() {
        assertEquals(24, trackerConfig.expectedFramesPerWindow())               // 3000 / 125
        assertEquals(
            90,
            trackerConfig.copy(fastIntervalMillis = 33L).expectedFramesPerWindow(),
        )
    }

    @Test
    fun shortInvalidGap_suspendsTimer_insteadOfClearing() {
        // 抓一下脸这种短暂无效不该让触发计时白跑（v0.7 宽限期）
        val t = tracker()
        t.feed(0L, 50f)                                  // 计时起点 0
        t.onFrame(FrameResult.NoPerson, 500L)            // 宽限期内（< 1000 ms），挂起
        val events = t.feed(700L, 50f)                   // 累计 700 ms，达到 triggerMillis
        val changed = events.filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(ModeChangeReason.TRIGGERED, changed.reason)
    }

    @Test
    fun longInvalidGap_clearsTimer() {
        val t = tracker()
        t.feed(0L, 50f)
        t.onFrame(FrameResult.NoPerson, 700L)
        t.onFrame(FrameResult.NoTorso(measurement(50f)), 1_800L)   // 距首个无效帧 1100 ms，超宽限
        assertTrue(t.feed(2_000L, 50f).isEmpty())                  // 计时重新开始
        assertEquals(TrackerMode.SLOW, t.mode)
        val changed = t.feed(2_700L, 50f).filterIsInstance<TrackerEvent.ModeChanged>().single()
        assertEquals(ModeChangeReason.TRIGGERED, changed.reason)
    }

    @Test
    fun trigger_isFrameRateIndependent() {
        // 同样的 700 ms 持续前倾，1.4 fps 与 8 fps 都恰好触发，不早也不晚
        val slow = tracker()
        slow.feed(0L, 50f)
        assertTrue(slow.feed(699L, 50f).isEmpty())
        assertTrue(slow.feed(700L, 50f).isNotEmpty())

        val fast = tracker()
        var ms = 0L
        while (ms < 700L) {
            assertTrue(fast.feed(ms, 50f).isEmpty())
            ms += 125L
        }
        assertTrue(fast.feed(700L, 50f).isNotEmpty())
    }
}
