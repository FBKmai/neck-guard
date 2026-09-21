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

/** 颈部角度用的参考方向。 */
enum class NeckReference {
    /** 髋肩连线（躯干线）的延长方向，躺卧与身体倾斜时都不会误判。 */
    TORSO,

    /** 竖直向上，仅在髋不可见且允许回退时使用。 */
    VERTICAL,
}

/** 单帧姿态测量结果。角度单位均为度。 */
data class PostureMeasurement(
    val side: BodySide,
    val ear: PixelPoint,
    val shoulder: PixelPoint,
    val hip: PixelPoint?,
    /**
     * 颈部前倾角：耳肩连线与参考方向的夹角，0 表示头在躯干延长线上。
     * 参考方向见 [neckReference]，默认是髋肩连线的延长方向。
     */
    val neckInclinationDeg: Float,
    /** 髋肩连线与竖直向上方向的夹角，髋不可见时为 null。仅用于显示与记录。 */
    val torsoInclinationDeg: Float?,
    /** 左右肩水平距离 / 躯干长度，用于判断摄像头是否在正侧面。 */
    val shoulderOffsetRatio: Float,
    val aligned: Boolean,
    /** [neckInclinationDeg] 用的参考方向。 */
    val neckReference: NeckReference = NeckReference.TORSO,
)

sealed class FrameResult {
    /** 关键点可见且摄像头在正侧面，可用于判定。 */
    data class Valid(val measurement: PostureMeasurement) : FrameResult()

    /** 关键点可见但摄像头不在正侧面，仅供 UI 提示摆放角度。 */
    data class Misaligned(val measurement: PostureMeasurement) : FrameResult()

    /**
     * 耳肩可见但髋不可见（或髋肩距离太短不可靠），没有躯干参考线。
     * measurement 里的角度退化为竖直参考，只供 UI 显示，不参与判定。
     */
    data class NoTorso(val measurement: PostureMeasurement) : FrameResult()

    /** 耳或肩 visibility 过低。 */
    data object LowVisibility : FrameResult()

    /** 画面中没有人。 */
    data object NoPerson : FrameResult()

    val measurementOrNull: PostureMeasurement?
        get() = when (this) {
            is Valid -> measurement
            is Misaligned -> measurement
            is NoTorso -> measurement
            else -> null
        }
}

data class GeometryConfig(
    val minVisibility: Float = 0.5f,
    val maxShoulderOffsetRatio: Float = 0.35f,
    /**
     * 是否必须有躯干线才判定。
     * true（默认）：髋不可见时该帧记为 [FrameResult.NoTorso]，不参与判定；
     * false：退回竖直参考并照常判定，适合髋部长期被桌子挡住的场景。
     */
    val requireHip: Boolean = true,
    /**
     * 髋肩距离至少要有耳肩距离的多少倍，低于此值认为髋点落在肩上、方向不可靠。
     */
    val minTorsoToNeckRatio: Float = 0.8f,
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

    /**
     * 两个向量 (a1 -> a2) 与 (b1 -> b2) 的夹角，单位度，范围 0..180。
     * 任一向量长度为 0 时返回 0。
     */
    fun angleBetween(a1: PixelPoint, a2: PixelPoint, b1: PixelPoint, b2: PixelPoint): Float {
        val ax = a2.x - a1.x
        val ay = a2.y - a1.y
        val bx = b2.x - b1.x
        val by = b2.y - b1.y
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-4f || lb < 1e-4f) return 0f
        val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /**
     * 颈部前倾角：耳肩连线与躯干延长线的夹角。
     * 躯干方向取 hip -> shoulder，颈部方向取 shoulder -> ear，
     * 头与身体共线（包括躺平）时为 0，头越往躯干前方伸角度越大。
     */
    fun neckRelativeToTorso(hip: PixelPoint, shoulder: PixelPoint, ear: PixelPoint): Float =
        angleBetween(hip, shoulder, shoulder, ear)

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
        val hipVisible = if (hipLm.visibility >= config.minVisibility) toPixel(hipLm) else null
        val farShoulderLm = landmarks[farShoulderIdx]

        val neckLen = distance(ear, shoulder)
        // 髋点落在肩膀附近时躯干方向是噪声，宁可当成没有躯干线
        val hip = hipVisible?.takeIf { distance(shoulder, it) >= neckLen * config.minTorsoToNeckRatio }
        val torso = hipVisible?.let { inclinationFromVertical(it, shoulder) }

        val reference = if (hip != null) NeckReference.TORSO else NeckReference.VERTICAL
        val neck = if (hip != null) {
            neckRelativeToTorso(hip, shoulder, ear)
        } else {
            inclinationFromVertical(shoulder, ear)
        }

        val torsoLen = if (hipVisible != null) distance(shoulder, hipVisible) else neckLen * 2f
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
            hip = hipVisible,
            neckInclinationDeg = neck,
            torsoInclinationDeg = torso,
            shoulderOffsetRatio = offsetRatio,
            aligned = aligned,
            neckReference = reference,
        )
        return when {
            // 对齐问题优先提示：摆放不对时算出来的角度本来也不可信
            !aligned -> FrameResult.Misaligned(measurement)
            hip == null && config.requireHip -> FrameResult.NoTorso(measurement)
            else -> FrameResult.Valid(measurement)
        }
    }
}
