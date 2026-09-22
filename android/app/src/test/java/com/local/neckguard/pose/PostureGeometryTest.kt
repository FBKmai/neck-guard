package com.local.neckguard.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureGeometryTest {

    private fun blankLandmarks(): MutableList<Landmark> =
        MutableList(PoseIndex.COUNT) { Landmark(0f, 0f, 0f) }

    private fun sideView(
        earX: Float,
        earY: Float,
        shoulderX: Float,
        shoulderY: Float,
        hipX: Float? = shoulderX,
        hipY: Float? = shoulderY + 0.3f,
        farShoulderX: Float = shoulderX,
        farShoulderVis: Float = 0.9f,
        side: BodySide = BodySide.LEFT,
    ): List<Landmark> {
        val lm = blankLandmarks()
        val ear = if (side == BodySide.LEFT) PoseIndex.LEFT_EAR else PoseIndex.RIGHT_EAR
        val shoulder = if (side == BodySide.LEFT) PoseIndex.LEFT_SHOULDER else PoseIndex.RIGHT_SHOULDER
        val far = if (side == BodySide.LEFT) PoseIndex.RIGHT_SHOULDER else PoseIndex.LEFT_SHOULDER
        val hip = if (side == BodySide.LEFT) PoseIndex.LEFT_HIP else PoseIndex.RIGHT_HIP
        lm[ear] = Landmark(earX, earY, 0.95f)
        lm[shoulder] = Landmark(shoulderX, shoulderY, 0.95f)
        lm[far] = Landmark(farShoulderX, shoulderY, farShoulderVis)
        if (hipX != null && hipY != null) lm[hip] = Landmark(hipX, hipY, 0.9f)
        return lm
    }

    @Test
    fun inclination_isZero_whenEarDirectlyAboveShoulder() {
        val angle = PostureGeometry.inclinationFromVertical(PixelPoint(100f, 200f), PixelPoint(100f, 100f))
        assertEquals(0f, angle, 1e-3f)
    }

    @Test
    fun inclination_is90_whenEarLevelWithShoulder() {
        val angle = PostureGeometry.inclinationFromVertical(PixelPoint(100f, 200f), PixelPoint(200f, 200f))
        assertEquals(90f, angle, 1e-3f)
    }

    @Test
    fun inclination_is45_onDiagonal() {
        val angle = PostureGeometry.inclinationFromVertical(PixelPoint(0f, 100f), PixelPoint(100f, 0f))
        assertEquals(45f, angle, 1e-3f)
    }

    @Test
    fun inclination_symmetricUnderMirror() {
        val left = PostureGeometry.inclinationFromVertical(PixelPoint(0f, 100f), PixelPoint(-60f, 20f))
        val right = PostureGeometry.inclinationFromVertical(PixelPoint(0f, 100f), PixelPoint(60f, 20f))
        assertEquals(left, right, 1e-4f)
    }

    @Test
    fun analyze_usesPixelCoordinates_notNormalized() {
        // 归一化坐标下 dx=0.1、dy=0.1 看似 45 度，但图像 640x480 时实际 dx=64、dy=48。
        // 默认髋在肩正下方，躯干竖直，所以相对躯干线的角度与相对竖直线相同。
        val lm = sideView(earX = 0.6f, earY = 0.4f, shoulderX = 0.5f, shoulderY = 0.5f)
        val result = PostureGeometry.analyze(lm, 640, 480)
        assertTrue(result is FrameResult.Valid)
        val m = (result as FrameResult.Valid).measurement
        val expected = Math.toDegrees(Math.atan2(64.0, 48.0)).toFloat()
        assertEquals(expected, m.neckInclinationDeg, 0.01f)
        assertEquals(NeckReference.TORSO, m.neckReference)
    }

    @Test
    fun analyze_neckIsZero_whenHeadAlignedWithTorso_lyingDown() {
        // 躺平：髋、肩、耳在一条水平线上，头并没有相对身体前伸
        val lm = sideView(earX = 0.7f, earY = 0.5f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.2f, hipY = 0.5f)
        val result = PostureGeometry.analyze(lm, 400, 400) as FrameResult.Valid
        assertEquals(0f, result.measurement.neckInclinationDeg, 1e-2f)
        // 旧口径（相对竖直线）在这里是 90 度，正是误报的来源
        assertEquals(90f, result.measurement.torsoInclinationDeg!!, 1e-2f)
    }

    @Test
    fun analyze_neckSubtractsTorsoLean() {
        // 躯干前倾：髋在肩的右下方，耳相对躯干线再偏 45 度
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.7f, hipY = 0.7f)
        val result = PostureGeometry.analyze(lm, 100, 100) as FrameResult.Valid
        // 躯干方向 hip->shoulder 指向左上 45 度，颈部方向 shoulder->ear 竖直向上，夹角 45 度
        assertEquals(45f, result.measurement.neckInclinationDeg, 1e-2f)
        assertEquals(45f, result.measurement.torsoInclinationDeg!!, 1e-2f)
    }

    @Test
    fun analyze_noTorso_whenHipHidden_byDefault() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val result = PostureGeometry.analyze(lm, 100, 100)
        assertTrue(result is FrameResult.NoTorso)
        val m = (result as FrameResult.NoTorso).measurement
        assertEquals(NeckReference.VERTICAL, m.neckReference)
        assertEquals(0f, m.neckInclinationDeg, 1e-3f)
    }

    @Test
    fun analyze_fallsBackToVertical_whenRequireHipDisabled() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val config = GeometryConfig(requireHip = false)
        val result = PostureGeometry.analyze(lm, 100, 100, config)
        assertTrue(result is FrameResult.Valid)
        assertEquals(NeckReference.VERTICAL, (result as FrameResult.Valid).measurement.neckReference)
    }

    @Test
    fun analyze_noTorso_whenHipTooCloseToShoulder() {
        // 髋点几乎落在肩上，躯干方向不可靠
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.5f, hipY = 0.52f)
        val result = PostureGeometry.analyze(lm, 100, 100)
        assertTrue(result is FrameResult.NoTorso)
        // 髋点本身仍然回传，UI 照常画出来
        assertEquals(NeckReference.VERTICAL, (result as FrameResult.NoTorso).measurement.neckReference)
    }

    @Test
    fun angleBetween_zeroWhenCollinear_and90WhenPerpendicular() {
        val origin = PixelPoint(0f, 0f)
        val right = PixelPoint(10f, 0f)
        val up = PixelPoint(0f, -10f)
        assertEquals(0f, PostureGeometry.angleBetween(origin, right, origin, right), 1e-3f)
        assertEquals(90f, PostureGeometry.angleBetween(origin, right, origin, up), 1e-3f)
        assertEquals(180f, PostureGeometry.angleBetween(origin, right, right, origin), 1e-3f)
    }

    @Test
    fun angleBetween_zeroForDegenerateVector() {
        val p = PixelPoint(5f, 5f)
        assertEquals(0f, PostureGeometry.angleBetween(p, p, PixelPoint(0f, 0f), PixelPoint(1f, 1f)), 1e-3f)
    }

    @Test
    fun analyze_picksRightSide_whenRightEarMoreVisible() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, side = BodySide.RIGHT)
        val result = PostureGeometry.analyze(lm, 100, 100) as FrameResult.Valid
        assertEquals(BodySide.RIGHT, result.measurement.side)
    }

    @Test
    fun analyze_lowVisibility_whenShoulderHidden() {
        val lm = blankLandmarks()
        lm[PoseIndex.LEFT_EAR] = Landmark(0.5f, 0.3f, 0.9f)
        lm[PoseIndex.LEFT_SHOULDER] = Landmark(0.5f, 0.5f, 0.2f)
        assertEquals(FrameResult.LowVisibility, PostureGeometry.analyze(lm, 100, 100))
    }

    @Test
    fun analyze_noPerson_whenLandmarksNull() {
        assertEquals(FrameResult.NoPerson, PostureGeometry.analyze(null, 100, 100))
    }

    @Test
    fun analyze_misaligned_whenShouldersFarApart() {
        // 躯干长 0.3，肩距 0.2 -> ratio 0.67 > 0.35
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, farShoulderX = 0.7f)
        val result = PostureGeometry.analyze(lm, 100, 100)
        assertTrue(result is FrameResult.Misaligned)
        val m = (result as FrameResult.Misaligned).measurement
        assertEquals(0.2f / 0.3f, m.shoulderOffsetRatio, 0.01f)
    }

    @Test
    fun analyze_alignedWhenFarShoulderOccluded() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, farShoulderX = 0.9f, farShoulderVis = 0.1f)
        assertTrue(PostureGeometry.analyze(lm, 100, 100) is FrameResult.Valid)
    }

    @Test
    fun analyze_torsoNull_whenHipHidden() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val result = PostureGeometry.analyze(lm, 100, 100) as FrameResult.NoTorso
        assertEquals(null, result.measurement.torsoInclinationDeg)
        assertEquals(null, result.measurement.hip)
    }

    @Test
    fun analyze_torsoInclination_computedFromHip() {
        // 髋在肩正下方 -> 躯干 0 度
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.5f, hipY = 0.8f)
        val result = PostureGeometry.analyze(lm, 100, 100) as FrameResult.Valid
        assertEquals(0f, result.measurement.torsoInclinationDeg!!, 1e-3f)
    }

    /** 左右两侧各给一组置信度，其余点留空。 */
    private fun bothSides(
        leftEar: Float, leftShoulder: Float, leftHip: Float,
        rightEar: Float, rightShoulder: Float, rightHip: Float,
    ): List<Landmark> {
        val lm = blankLandmarks()
        lm[PoseIndex.LEFT_EAR] = Landmark(0.4f, 0.3f, leftEar)
        lm[PoseIndex.LEFT_SHOULDER] = Landmark(0.4f, 0.5f, leftShoulder)
        lm[PoseIndex.LEFT_HIP] = Landmark(0.4f, 0.8f, leftHip)
        lm[PoseIndex.RIGHT_EAR] = Landmark(0.6f, 0.3f, rightEar)
        lm[PoseIndex.RIGHT_SHOULDER] = Landmark(0.6f, 0.5f, rightShoulder)
        lm[PoseIndex.RIGHT_HIP] = Landmark(0.6f, 0.8f, rightHip)
        return lm
    }

    @Test
    fun sideScore_isWeakestOfChain() {
        // 选侧看整条链最弱的一点，不是只看耳朵（v0.7）
        val lm = bothSides(0.91f, 0.54f, 0.52f, 0.82f, 0.94f, 0.96f)
        assertEquals(0.52f, PostureGeometry.sideScore(lm, BodySide.LEFT).full, 1e-6f)
        assertEquals(0.82f, PostureGeometry.sideScore(lm, BodySide.RIGHT).full, 1e-6f)
        // 旧逻辑只比耳朵会选左（0.91 > 0.82），新逻辑选整条链更稳的右侧
        assertEquals(BodySide.RIGHT, PostureGeometry.pickSide(lm))
    }

    @Test
    fun pickSide_isStickyWithinMargin() {
        val lm = bothSides(0.80f, 0.80f, 0.80f, 0.90f, 0.90f, 0.90f)
        assertEquals(BodySide.RIGHT, PostureGeometry.pickSide(lm))
        // 已经在用左侧：右侧只高 0.10，不到 0.15 的换边门槛，保持不翻转
        assertEquals(BodySide.LEFT, PostureGeometry.pickSide(lm, BodySide.LEFT))
        // 差距拉大到 0.19 才换
        val wider = bothSides(0.80f, 0.80f, 0.80f, 0.99f, 0.99f, 0.99f)
        assertEquals(BodySide.RIGHT, PostureGeometry.pickSide(wider, BodySide.LEFT))
        assertEquals(BodySide.RIGHT, PostureGeometry.pickSide(wider, BodySide.RIGHT))
    }

    @Test
    fun pickSide_fallsBackToNeckChain_whenBothHipsHidden() {
        // 髋被桌子挡住时两侧含髋得分都接近 0，此时改比耳肩而不是掷硬币
        val lm = bothSides(0.95f, 0.95f, 0.05f, 0.30f, 0.30f, 0.04f)
        assertEquals(BodySide.LEFT, PostureGeometry.pickSide(lm))
        assertEquals(BodySide.LEFT, PostureGeometry.pickSide(lm, BodySide.LEFT))
    }

    @Test
    fun pickSide_usesHip_whenOneSideHasIt() {
        // 只要有一侧的髋可用，就仍按含髋的整条链比
        val lm = bothSides(0.95f, 0.95f, 0.10f, 0.75f, 0.75f, 0.70f)
        assertEquals(BodySide.RIGHT, PostureGeometry.pickSide(lm))
    }

    @Test
    fun analyze_respectsCurrentSide() {
        val lm = sideView(earX = 0.5f, earY = 0.3f, shoulderX = 0.5f, shoulderY = 0.5f, side = BodySide.RIGHT)
        // 左侧三点全 0，右侧明显更好，传 LEFT 也会被 margin 判成该换边
        val result = PostureGeometry.analyze(lm, 100, 100, currentSide = BodySide.LEFT) as FrameResult.Valid
        assertEquals(BodySide.RIGHT, result.measurement.side)
    }

    @Test
    fun torsoHint_holdsAngle_whenHipBrieflyLost() {
        // 髋短暂丢失时沿用上次躯干方向，角度不该跳变（v0.7）
        val memory = SideAndTorsoMemory()
        val withHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.5f, hipY = 0.8f)
        val first = memory.analyze(withHip, 100, 100, 1_000L) as FrameResult.Valid
        assertEquals(NeckReference.TORSO, first.measurement.neckReference)

        // 下一帧髋没了，其余点不动：沿用方向，角度与上一帧一致
        val noHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val held = memory.analyze(noHip, 100, 100, 1_500L) as FrameResult.Valid
        assertEquals(NeckReference.TORSO_HELD, held.measurement.neckReference)
        assertEquals(first.measurement.neckInclinationDeg, held.measurement.neckInclinationDeg, 1e-3f)

        // 超过保留期（默认 2 秒）后退回竖直参考，并按 requireHip 跳过该帧
        val expired = memory.analyze(noHip, 100, 100, 4_000L) as FrameResult.NoTorso
        assertEquals(NeckReference.VERTICAL, expired.measurement.neckReference)
    }

    @Test
    fun heldHint_doesNotRenewItself() {
        // 沿用的帧不能给自己续期，否则保留期形同虚设
        val memory = SideAndTorsoMemory()
        val withHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.5f, hipY = 0.8f)
        memory.analyze(withHip, 100, 100, 0L)
        val noHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        for (t in longArrayOf(500L, 1_000L, 1_500L, 2_000L)) {
            val r = memory.analyze(noHip, 100, 100, t)
            assertEquals(NeckReference.TORSO_HELD, r.measurementOrNull!!.neckReference)
        }
        val expired = memory.analyze(noHip, 100, 100, 2_500L)
        assertEquals(NeckReference.VERTICAL, expired.measurementOrNull!!.neckReference)
    }

    @Test
    fun torsoHint_preventsFalseAlarm_whenLeaningOverDesk() {
        // 伏案时髋一丢，退回竖直会让角度从 11 度跳到 35 度，直接越过阈值误报
        val memory = SideAndTorsoMemory()
        // 躯干前倾（髋在肩后下方），头再前伸一点
        val withHip = sideView(earX = 0.64f, earY = 0.30f, shoulderX = 0.50f, shoulderY = 0.50f, hipX = 0.36f, hipY = 0.82f)
        val first = memory.analyze(withHip, 100, 100, 0L) as FrameResult.Valid
        assertTrue(first.measurement.neckInclinationDeg < 20f)

        val noHip = sideView(earX = 0.64f, earY = 0.30f, shoulderX = 0.50f, shoulderY = 0.50f, hipX = null, hipY = null)
        val held = memory.analyze(noHip, 100, 100, 500L) as FrameResult.Valid
        assertEquals(NeckReference.TORSO_HELD, held.measurement.neckReference)
        assertEquals(first.measurement.neckInclinationDeg, held.measurement.neckInclinationDeg, 1e-3f)

        // 对照：不带记忆直接算，角度会跳到 35 度附近
        val bare = PostureGeometry.analyze(noHip, 100, 100) as FrameResult.NoTorso
        assertEquals(NeckReference.VERTICAL, bare.measurement.neckReference)
        assertTrue(bare.measurement.neckInclinationDeg - first.measurement.neckInclinationDeg > 20f)
    }

    @Test
    fun referenceDirection_matchesTheAngleBasis() {
        // 叠加层按 referenceDirection 画参考线，它必须和算角度用的方向一致
        val memory = SideAndTorsoMemory()
        val withHip = sideView(earX = 0.64f, earY = 0.30f, shoulderX = 0.50f, shoulderY = 0.50f, hipX = 0.36f, hipY = 0.82f)
        val first = (memory.analyze(withHip, 100, 100, 0L) as FrameResult.Valid).measurement
        val hip = first.hip!!
        val dx = first.shoulder.x - hip.x
        val dy = first.shoulder.y - hip.y
        val len = kotlin.math.hypot(dx, dy)
        val dir = first.referenceDirection!!
        assertEquals(dx / len, dir.x, 1e-5f)
        assertEquals(dy / len, dir.y, 1e-5f)
        // 用它反算角度，应与 measurement 里的角度一致
        val recomputed = PostureGeometry.angleBetweenVectors(
            dir.x, dir.y, first.ear.x - first.shoulder.x, first.ear.y - first.shoulder.y,
        )
        assertEquals(first.neckInclinationDeg, recomputed, 1e-3f)

        // 髋丢失沿用时，方向不变，参考线不会突然跳成竖直
        val noHip = sideView(earX = 0.64f, earY = 0.30f, shoulderX = 0.50f, shoulderY = 0.50f, hipX = null, hipY = null)
        val held = (memory.analyze(noHip, 100, 100, 500L) as FrameResult.Valid).measurement
        assertEquals(NeckReference.TORSO_HELD, held.neckReference)
        assertEquals(null, held.hip)
        assertEquals(dir.x, held.referenceDirection!!.x, 1e-5f)
        assertEquals(dir.y, held.referenceDirection!!.y, 1e-5f)

        // 退回竖直参考时为 null，由调用方画竖直线
        val expired = memory.analyze(noHip, 100, 100, 5_000L).measurementOrNull!!
        assertEquals(NeckReference.VERTICAL, expired.neckReference)
        assertEquals(null, expired.referenceDirection)
    }

    @Test
    fun torsoHint_disabledByZeroHold() {
        val memory = SideAndTorsoMemory(GeometryConfig(torsoHoldMillis = 0L))
        val withHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = 0.5f, hipY = 0.8f)
        memory.analyze(withHip, 100, 100, 0L)
        val noHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val result = memory.analyze(noHip, 100, 100, 100L) as FrameResult.NoTorso
        assertEquals(NeckReference.VERTICAL, result.measurement.neckReference)
    }

    @Test
    fun analyze_withoutNowMillis_keepsOldBehaviour() {
        // 不传时间戳时行为与 v0.6 完全一致
        val noHip = sideView(earX = 0.6f, earY = 0.35f, shoulderX = 0.5f, shoulderY = 0.5f, hipX = null, hipY = null)
        val result = PostureGeometry.analyze(noHip, 100, 100) as FrameResult.NoTorso
        assertEquals(NeckReference.VERTICAL, result.measurement.neckReference)
    }
}
