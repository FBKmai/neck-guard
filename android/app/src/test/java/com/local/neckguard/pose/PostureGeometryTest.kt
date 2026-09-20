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
        // 归一化坐标下 dx=0.1、dy=0.1 看似 45 度，但图像 640x480 时实际 dx=64、dy=48
        val lm = sideView(earX = 0.6f, earY = 0.4f, shoulderX = 0.5f, shoulderY = 0.5f)
        val result = PostureGeometry.analyze(lm, 640, 480)
        assertTrue(result is FrameResult.Valid)
        val m = (result as FrameResult.Valid).measurement
        val expected = Math.toDegrees(Math.atan2(64.0, 48.0)).toFloat()
        assertEquals(expected, m.neckInclinationDeg, 0.01f)
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
        val result = PostureGeometry.analyze(lm, 100, 100) as FrameResult.Valid
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
}
