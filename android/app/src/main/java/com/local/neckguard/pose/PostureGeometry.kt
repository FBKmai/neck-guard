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

    /**
     * 髋刚丢失，沿用最近一次可靠的躯干方向。
     * 口径与 [TORSO] 相同，角度可直接与之比较，所以照常参与判定。
     */
    TORSO_HELD,

    /** 竖直向上，仅在髋不可见、方向提示也过期且允许回退时使用。 */
    VERTICAL,
}

/**
 * 最近一次可靠的躯干方向，由调用方持有并逐帧传入。
 *
 * 髋被手或桌子短暂挡住时，与其让整帧作废（[FrameResult.NoTorso] 不参与判定）
 * 或改用竖直参考（换了口径，角度会跳十几度），不如沿用刚才那条躯干线：
 * 躯干方向变化远比帧间隔慢，一两秒内几乎不动。
 *
 * [dx] [dy] 是单位化的躯干方向（由髋指向肩），[atMillis] 是它来自哪一帧。
 */
data class TorsoHint(val dx: Float, val dy: Float, val atMillis: Long)

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
    /**
     * 实际用来算角度的参考方向（已单位化，由髋指向肩）。
     * [NeckReference.TORSO_HELD] 时髋为 null，但角度是按这条沿用的方向算的，
     * 叠加层要用它画参考线，否则线是竖直的而数字不是，看着对不上。
     * VERTICAL 时为 null（参考线就是竖直向上）。
     */
    val referenceDirection: PixelPoint? = null,
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
    /** 另一侧的可用度要高出当前侧这么多才换边，避免逐帧翻转导致角度跳变。 */
    val sideSwitchMargin: Float = 0.15f,
    /** 髋丢失后，最近一次可靠的躯干方向还能沿用多久。0 表示关掉这个兜底。 */
    val torsoHoldMillis: Long = 2_000L,
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

    /** 两个向量的夹角，单位度，范围 0..180。任一向量长度为 0 时返回 0。 */
    fun angleBetweenVectors(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-4f || lb < 1e-4f) return 0f
        val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /**
     * 两个向量 (a1 -> a2) 与 (b1 -> b2) 的夹角，单位度，范围 0..180。
     * 任一向量长度为 0 时返回 0。
     */
    fun angleBetween(a1: PixelPoint, a2: PixelPoint, b1: PixelPoint, b2: PixelPoint): Float =
        angleBetweenVectors(a2.x - a1.x, a2.y - a1.y, b2.x - b1.x, b2.y - b1.y)

    /** 从一帧可靠的髋肩点生成方向提示。两点重合时返回 null。 */
    fun torsoHintFrom(hip: PixelPoint, shoulder: PixelPoint, nowMillis: Long): TorsoHint? {
        val dx = shoulder.x - hip.x
        val dy = shoulder.y - hip.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return null
        return TorsoHint(dx / len, dy / len, nowMillis)
    }

    /** 方向提示是否还在保留期内。缺时间戳或关掉保留期时一律不用。 */
    private fun hintUsable(hint: TorsoHint?, nowMillis: Long?, holdMillis: Long): Boolean {
        if (hint == null || nowMillis == null || holdMillis <= 0L) return false
        val age = nowMillis - hint.atMillis
        return age in 0L..holdMillis
    }

    /**
     * 颈部前倾角：耳肩连线与躯干延长线的夹角。
     * 躯干方向取 hip -> shoulder，颈部方向取 shoulder -> ear，
     * 头与身体共线（包括躺平）时为 0，头越往躯干前方伸角度越大。
     */
    fun neckRelativeToTorso(hip: PixelPoint, shoulder: PixelPoint, ear: PixelPoint): Float =
        angleBetween(hip, shoulder, shoulder, ear)

    fun distance(a: PixelPoint, b: PixelPoint): Float = hypot(b.x - a.x, b.y - a.y)

    /** 一侧的可用度：[neck] 是耳肩链，[full] 是含髋的整条链，各取链上最弱的一点。 */
    data class SideScore(val neck: Float, val full: Float)

    /**
     * 一侧的可用度，分耳肩与含髋两档，都取各自链上最弱的一点。
     *
     * 耳肩髋是串联关系，任何一点崩掉整条向量都会崩，所以取 min 而不是平均。
     * 只看耳朵会在「耳朵清楚但肩很糊」时选错边。
     *
     * 分两档是因为髋经常被桌子挡住：那时两侧的含髋得分都接近 0，直接比会
     * 退化成掷硬币，此时应该回到耳肩这一档上比较。
     */
    fun sideScore(landmarks: List<Landmark>, side: BodySide): SideScore {
        val ear = if (side == BodySide.LEFT) PoseIndex.LEFT_EAR else PoseIndex.RIGHT_EAR
        val shoulder = if (side == BodySide.LEFT) PoseIndex.LEFT_SHOULDER else PoseIndex.RIGHT_SHOULDER
        val hip = if (side == BodySide.LEFT) PoseIndex.LEFT_HIP else PoseIndex.RIGHT_HIP
        val neck = minOf(landmarks[ear].visibility, landmarks[shoulder].visibility)
        return SideScore(neck, minOf(neck, landmarks[hip].visibility))
    }

    /**
     * 选用哪一侧的关键点。
     *
     * 先比含髋的整条链；两侧的髋都不可用时退回只比耳肩，避免被两个同样接近 0
     * 的髋置信度主导。[current] 是上一帧用的侧别，给了它就带切换粘性：另一侧要
     * 高出 [switchMargin] 才换边，否则两边得分接近时会逐帧翻转让角度跳变。
     */
    fun pickSide(
        landmarks: List<Landmark>,
        current: BodySide? = null,
        switchMargin: Float = 0.15f,
        minVisibility: Float = 0.5f,
    ): BodySide {
        val leftScore = sideScore(landmarks, BodySide.LEFT)
        val rightScore = sideScore(landmarks, BodySide.RIGHT)
        // 两侧都没有可用的髋时，含髋得分没有区分度，改比耳肩
        val bothHipsHidden = leftScore.full < minVisibility && rightScore.full < minVisibility
        val left = if (bothHipsHidden) leftScore.neck else leftScore.full
        val right = if (bothHipsHidden) rightScore.neck else rightScore.full
        if (current == null) return if (left >= right) BodySide.LEFT else BodySide.RIGHT
        val other = if (current == BodySide.LEFT) BodySide.RIGHT else BodySide.LEFT
        val currentScore = if (current == BodySide.LEFT) left else right
        val otherScore = if (current == BodySide.LEFT) right else left
        return if (otherScore > currentScore + switchMargin) other else current
    }

    /**
     * [currentSide] 是上一帧用的侧别，传了就带切换粘性（见 [pickSide]）。
     * [torsoHint] 是最近一次可靠的躯干方向，配合 [nowMillis] 在髋短暂丢失时兜底。
     * 保持纯函数：状态由调用方持有（见 [SideAndTorsoMemory]），本函数不记忆任何东西。
     */
    fun analyze(
        landmarks: List<Landmark>?,
        imageWidth: Int,
        imageHeight: Int,
        config: GeometryConfig = GeometryConfig(),
        currentSide: BodySide? = null,
        torsoHint: TorsoHint? = null,
        nowMillis: Long? = null,
    ): FrameResult {
        if (landmarks == null || landmarks.size < PoseIndex.COUNT) return FrameResult.NoPerson
        require(imageWidth > 0 && imageHeight > 0) { "image size must be positive" }

        val side = pickSide(landmarks, currentSide, config.sideSwitchMargin, config.minVisibility)
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

        val useHint = hip == null && hintUsable(torsoHint, nowMillis, config.torsoHoldMillis)
        val reference = when {
            hip != null -> NeckReference.TORSO
            useHint -> NeckReference.TORSO_HELD
            else -> NeckReference.VERTICAL
        }
        val neck = when {
            hip != null -> neckRelativeToTorso(hip, shoulder, ear)
            // 髋刚丢，沿用最近一次可靠的躯干方向：口径不变，角度可以直接跟之前比
            useHint -> angleBetweenVectors(
                torsoHint!!.dx, torsoHint.dy, ear.x - shoulder.x, ear.y - shoulder.y,
            )
            else -> inclinationFromVertical(shoulder, ear)
        }
        // 叠加层按它画参考线，保证线与角度始终同源
        val referenceDirection = when {
            hip != null -> {
                val dx = shoulder.x - hip.x
                val dy = shoulder.y - hip.y
                val len = hypot(dx, dy)
                if (len < 1e-4f) null else PixelPoint(dx / len, dy / len)
            }
            useHint -> PixelPoint(torsoHint!!.dx, torsoHint.dy)
            else -> null
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
            referenceDirection = referenceDirection,
        )
        return when {
            // 对齐问题优先提示：摆放不对时算出来的角度本来也不可信
            !aligned -> FrameResult.Misaligned(measurement)
            // TORSO_HELD 与 TORSO 是同一个口径，可以照常参与判定；
            // 只有退到竖直参考（换了口径）才在 requireHip 下跳过这一帧
            reference == NeckReference.VERTICAL && config.requireHip -> FrameResult.NoTorso(measurement)
            else -> FrameResult.Valid(measurement)
        }
    }
}

/**
 * [PostureGeometry.analyze] 的逐帧记忆：上一帧的侧别 + 最近一次可靠的躯干方向。
 *
 * analyze 本身保持纯函数，这点状态放在这里，三个调用方（监测服务、摆放页预览、
 * 电脑脚本）共用同一套语义，不必各写一遍。
 *
 * 线程安全：方法加 synchronized，因为监测服务里由 MediaPipe 回调线程调用，
 * 停止时可能被主线程 reset。
 */
class SideAndTorsoMemory(@Volatile var config: GeometryConfig = GeometryConfig()) {

    private var side: BodySide? = null
    private var torsoHint: TorsoHint? = null

    @Synchronized
    fun analyze(
        landmarks: List<Landmark>?,
        imageWidth: Int,
        imageHeight: Int,
        nowMillis: Long? = null,
    ): FrameResult {
        val result = PostureGeometry.analyze(
            landmarks, imageWidth, imageHeight, config, side, torsoHint, nowMillis,
        )
        val m = result.measurementOrNull ?: return result
        side = m.side
        // 只有这一帧真的有可靠的髋，才刷新方向提示；沿用的帧不能自我续期，
        // 否则髋一直不出现也能无限续下去，保留期就形同虚设。
        val hip = m.hip
        if (m.neckReference == NeckReference.TORSO && hip != null && nowMillis != null) {
            PostureGeometry.torsoHintFrom(hip, m.shoulder, nowMillis)?.let { torsoHint = it }
        }
        return result
    }

    @Synchronized
    fun reset() {
        side = null
        torsoHint = null
    }
}
