package com.local.neckguard.pose

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/** MediaPipe Pose Landmarker 33 点中本项目用到的索引。 */
object PoseIndex {
    const val NOSE = 0
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val COUNT = 33
}

/** 归一化关键点：x、y 取值 0..1（相对图像宽高），visibility 取值 0..1。 */
data class Landmark(val x: Float, val y: Float, val visibility: Float)

/** 像素坐标点（已按图像宽高还原，角度计算必须用它，否则非正方形图像会畸变）。 */
data class PixelPoint(val x: Float, val y: Float)

enum class BodySide { LEFT, RIGHT }

/** 单帧姿态测量结果。角度单位均为度。 */
data class PostureMeasurement(
    val side: BodySide,
    val ear: PixelPoint,
    val shoulder: PixelPoint,
    val hip: PixelPoint?,
    /** 耳肩连线与竖直向上方向的夹角，0 表示耳朵在肩正上方，越大越前倾。 */
    val neckInclinationDeg: Float,
    /** 髋肩连线与竖直向上方向的夹角，髋不可见时为 null。 */
    val torsoInclinationDeg: Float?,
    /** 左右肩水平距离 / 躯干长度，用于判断摄像头是否在正侧面。 */
    val shoulderOffsetRatio: Float,
    val aligned: Boolean,
)

sealed class FrameResult {
    /** 关键点可见且摄像头在正侧面，可用于判定。 */
    data class Valid(val measurement: PostureMeasurement) : FrameResult()

    /** 关键点可见但摄像头不在正侧面，仅供 UI 提示摆放角度。 */
    data class Misaligned(val measurement: PostureMeasurement) : FrameResult()

    /** 耳或肩 visibility 过低。 */
    data object LowVisibility : FrameResult()

    /** 画面中没有人。 */
    data object NoPerson : FrameResult()

    val measurementOrNull: PostureMeasurement?
        get() = when (this) {
            is Valid -> measurement
            is Misaligned -> measurement
            else -> null
        }
}

data class GeometryConfig(
    val minVisibility: Float = 0.5f,
    val maxShoulderOffsetRatio: Float = 0.35f,
)

/**
 * 纯几何计算，不依赖 Android，Python 原型 tools/posture_probe.py 与此保持一致。
 */
object PostureGeometry {

    /**
     * 向量 (from -> to) 与竖直向上方向 (0, -1) 的夹角，单位度，范围 0..180。
     * 图像坐标 y 向下，所以 cos = (from.y - to.y) / |v|。
     */
    fun inclinationFromVertical(from: PixelPoint, to: PixelPoint): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return 0f
        val cos = ((from.y - to.y) / len).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    fun distance(a: PixelPoint, b: PixelPoint): Float = hypot(b.x - a.x, b.y - a.y)

    /** 用耳朵 visibility 决定使用哪一侧的关键点。 */
    fun pickSide(landmarks: List<Landmark>): BodySide =
        if (landmarks[PoseIndex.LEFT_EAR].visibility >= landmarks[PoseIndex.RIGHT_EAR].visibility) {
            BodySide.LEFT
        } else {
            BodySide.RIGHT
        }

    fun analyze(
        landmarks: List<Landmark>?,
        imageWidth: Int,
        imageHeight: Int,
        config: GeometryConfig = GeometryConfig(),
    ): FrameResult {
        if (landmarks == null || landmarks.size < PoseIndex.COUNT) return FrameResult.NoPerson
        require(imageWidth > 0 && imageHeight > 0) { "image size must be positive" }

        val side = pickSide(landmarks)
        val earIdx = if (side == BodySide.LEFT) PoseIndex.LEFT_EAR else PoseIndex.RIGHT_EAR
        val shoulderIdx = if (side == BodySide.LEFT) PoseIndex.LEFT_SHOULDER else PoseIndex.RIGHT_SHOULDER
        val farShoulderIdx = if (side == BodySide.LEFT) PoseIndex.RIGHT_SHOULDER else PoseIndex.LEFT_SHOULDER
        val hipIdx = if (side == BodySide.LEFT) PoseIndex.LEFT_HIP else PoseIndex.RIGHT_HIP

        val earLm = landmarks[earIdx]
        val shoulderLm = landmarks[shoulderIdx]
        if (earLm.visibility < config.minVisibility || shoulderLm.visibility < config.minVisibility) {
            return FrameResult.LowVisibility
        }

        fun toPixel(lm: Landmark) = PixelPoint(lm.x * imageWidth, lm.y * imageHeight)

        val ear = toPixel(earLm)
        val shoulder = toPixel(shoulderLm)
        val hipLm = landmarks[hipIdx]
        val hip = if (hipLm.visibility >= config.minVisibility) toPixel(hipLm) else null
        val farShoulderLm = landmarks[farShoulderIdx]

        val neck = inclinationFromVertical(shoulder, ear)
        val torso = hip?.let { inclinationFromVertical(it, shoulder) }

        val torsoLen = if (hip != null) distance(shoulder, hip) else distance(ear, shoulder) * 2f
        val offsetRatio = if (farShoulderLm.visibility < config.minVisibility || torsoLen < 1e-3f) {
            // 远侧肩膀被身体挡住，恰好说明是正侧面
            0f
        } else {
            abs(toPixel(farShoulderLm).x - shoulder.x) / max(torsoLen, 1e-3f)
        }
        val aligned = offsetRatio < config.maxShoulderOffsetRatio

        val measurement = PostureMeasurement(
            side = side,
            ear = ear,
            shoulder = shoulder,
            hip = hip,
            neckInclinationDeg = neck,
            torsoInclinationDeg = torso,
            shoulderOffsetRatio = offsetRatio,
            aligned = aligned,
        )
        return if (aligned) FrameResult.Valid(measurement) else FrameResult.Misaligned(measurement)
    }
}
