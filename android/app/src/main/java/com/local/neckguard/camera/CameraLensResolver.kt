@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.local.neckguard.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.atan

/**
 * 一个候选镜头的可判定属性。不含任何 CameraX 类型，便于纯函数单测。
 *
 * @param intrinsicZoomRatio CameraX 给出的固有变焦比，小于 1 表示比默认镜头更广
 * @param minZoomRatio 该相机可设的最小变焦比，小于 1 通常意味着逻辑相机能切到超广角
 */
data class LensCandidate(
    val id: String,
    val facingBack: Boolean,
    val minFocalMm: Float? = null,
    val sensorWidthMm: Float? = null,
    val intrinsicZoomRatio: Float? = null,
    val minZoomRatio: Float? = null,
    val physicalCandidates: List<LensCandidate> = emptyList(),
) {
    /** 水平视场角。焦距或传感器尺寸缺失时为 null。 */
    val horizontalFovDeg: Float?
        get() {
            val f = minFocalMm ?: return null
            val w = sensorWidthMm ?: return null
            if (f <= 0f || w <= 0f) return null
            return CameraLensResolver.horizontalFovDeg(f, w)
        }
}

/** 选中哪一路。 */
enum class LensStrategy {
    FRONT,
    BACK,
    /** availableCameraInfos 里直接存在更广的独立后摄。 */
    ULTRA_WIDE_DIRECT,
    /** 逻辑后摄的物理子镜头。 */
    ULTRA_WIDE_PHYSICAL,
    /** 变焦下限小于 1，靠 setZoomRatio 让 HAL 切到超广角。 */
    ULTRA_WIDE_ZOOM,
    /** 设备未开放超广角，退回普通后摄。 */
    ULTRA_WIDE_FALLBACK,
}

/** 纯函数的挑选结果。 */
sealed class LensChoice {
    data class ById(val id: String, val strategy: LensStrategy) : LensChoice()
    data class ByPhysical(val logicalId: String, val physicalId: String) : LensChoice()
    data class ByZoom(val id: String, val zoomRatio: Float) : LensChoice()
    data class Fallback(val id: String?, val facingBack: Boolean, val note: String) : LensChoice()
}

/** 解析结果，含可直接用于绑定的 CameraSelector。 */
data class LensResolution(
    val lens: CameraLens,
    val selector: CameraSelector,
    val strategy: LensStrategy,
    val cameraId: String?,
    val physicalId: String?,
    val zoomRatio: Float?,
    val description: String,
)

/** 相机诊断信息，用于设置页的「相机信息」对话框。 */
data class CameraDiag(
    val id: String,
    val facing: String,
    val focalLengthsMm: List<Float>,
    val sensorWidthMm: Float?,
    val hFovDeg: Float?,
    val zoomRange: String?,
    val physicalIds: List<String>,
    val logicalMultiCamera: Boolean,
    val visibleToCameraX: Boolean,
    val hidden: Boolean,
    val error: String?,
) {
    fun toLine(): String = buildString {
        append("ID ").append(id).append("  ").append(facing)
        if (focalLengthsMm.isNotEmpty()) {
            append("  f=").append(focalLengthsMm.joinToString("/") { String.format("%.1f", it) }).append("mm")
        }
        hFovDeg?.let { append("  FOV ").append(String.format("%.0f", it)).append("°") }
        zoomRange?.let { append("  zoom ").append(it) }
        if (physicalIds.isNotEmpty()) append("  物理 ").append(physicalIds.joinToString("/"))
        if (logicalMultiCamera) append("  逻辑多摄")
        append(if (hidden) "  [系统隐藏]" else if (visibleToCameraX) "  [可用]" else "  [CameraX 不可见]")
        error?.let { append("  读取失败: ").append(it) }
    }
}

/**
 * 镜头解析。超广角按四条路径依次尝试：
 * (a) 独立的更广后摄 -> (b) 逻辑后摄的物理子镜头 -> (c) 变焦下限 < 1 -> (d) 退回普通后摄。
 *
 * MIUI 等 ROM 通常只向第三方暴露主摄与前摄，(c) 往往是唯一可行路径；
 * 所有失败都不抛异常，而是退回可用镜头并在 description 里说明，由界面展示给用户。
 */
object CameraLensResolver {

    private const val TAG = "LensResolver"

    /** 认定「更广」所需的视场角相对增幅。 */
    private const val FOV_GAIN = 1.10f

    /** intrinsicZoomRatio 低于此值即视为超广角。 */
    private const val INTRINSIC_ZOOM_MAX = 0.95f

    /** 变焦下限低于此值才值得尝试 setZoomRatio。 */
    private const val MIN_ZOOM_MAX = 0.99f

    /** 水平视场角，单位度。 */
    fun horizontalFovDeg(focalMm: Float, sensorWidthMm: Float): Float =
        Math.toDegrees(2.0 * atan((sensorWidthMm / (2f * focalMm)).toDouble())).toFloat()

    /**
     * 纯挑选逻辑，可单测。
     * @param defaultBackId availableCameraInfos 中第一个后置相机的 id（通常是主摄）
     */
    fun choose(
        lens: CameraLens,
        candidates: List<LensCandidate>,
        defaultBackId: String?,
        defaultFrontId: String?,
    ): LensChoice {
        when (lens) {
            CameraLens.FRONT -> return defaultFrontId?.let { LensChoice.ById(it, LensStrategy.FRONT) }
                ?: LensChoice.Fallback(null, facingBack = false, note = "未找到前置摄像头")
            CameraLens.BACK -> return defaultBackId?.let { LensChoice.ById(it, LensStrategy.BACK) }
                ?: LensChoice.Fallback(null, facingBack = true, note = "未找到后置摄像头")
            CameraLens.BACK_ULTRA_WIDE -> Unit
        }

        val back = candidates.filter { it.facingBack }
        val default = back.firstOrNull { it.id == defaultBackId } ?: back.firstOrNull()
            ?: return LensChoice.Fallback(null, facingBack = true, note = "未找到后置摄像头")

        // (a) 独立的更广后摄
        val others = back.filter { it.id != default.id }
        others.firstOrNull { (it.intrinsicZoomRatio ?: 1f) < INTRINSIC_ZOOM_MAX }
            ?.let { return LensChoice.ById(it.id, LensStrategy.ULTRA_WIDE_DIRECT) }
        val defaultFov = default.horizontalFovDeg
        val widest = others.filter { it.horizontalFovDeg != null }.maxByOrNull { it.horizontalFovDeg!! }
        if (widest != null && defaultFov != null && widest.horizontalFovDeg!! > defaultFov * FOV_GAIN) {
            return LensChoice.ById(widest.id, LensStrategy.ULTRA_WIDE_DIRECT)
        }

        // (b) 逻辑后摄的物理子镜头
        val physicals = default.physicalCandidates
        val widestPhysical = physicals
            .filter { it.horizontalFovDeg != null }
            .maxByOrNull { it.horizontalFovDeg!! }
        if (widestPhysical != null) {
            val wider = defaultFov == null || widestPhysical.horizontalFovDeg!! > defaultFov * FOV_GAIN
            if (wider) return LensChoice.ByPhysical(default.id, widestPhysical.id)
        }
        physicals.firstOrNull { (it.intrinsicZoomRatio ?: 1f) < INTRINSIC_ZOOM_MAX }
            ?.let { return LensChoice.ByPhysical(default.id, it.id) }

        // (c) 变焦下限小于 1
        val minZoom = default.minZoomRatio
        if (minZoom != null && minZoom < MIN_ZOOM_MAX && minZoom > 0f) {
            return LensChoice.ByZoom(default.id, minZoom)
        }

        // (d) 放弃
        return LensChoice.Fallback(default.id, facingBack = true, note = "设备未向第三方开放超广角，已使用普通后摄")
    }

    /** 读取 CameraX 可见的所有相机属性。任何一项读取失败都不影响其余项。 */
    fun candidatesOf(provider: ProcessCameraProvider): List<LensCandidate> =
        provider.availableCameraInfos.mapNotNull { info ->
            try {
                toCandidate(info, includePhysical = true)
            } catch (e: Throwable) {
                Log.w(TAG, "read camera info failed", e)
                null
            }
        }

    private fun toCandidate(info: CameraInfo, includePhysical: Boolean): LensCandidate {
        val c2 = Camera2CameraInfo.from(info)
        val focals = runCatching {
            c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        }.getOrNull()
        val sensor = runCatching {
            c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        }.getOrNull()
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
        } else {
            null
        }
        val zoomStateMin = runCatching { info.zoomState.value?.minZoomRatio }.getOrNull()
        val physicals = if (includePhysical) {
            runCatching {
                info.physicalCameraInfos.map { toCandidate(it, includePhysical = false) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return LensCandidate(
            id = c2.getCameraId(),
            facingBack = info.lensFacing == CameraSelector.LENS_FACING_BACK,
            minFocalMm = focals?.minOrNull(),
            sensorWidthMm = sensor?.width,
            // 未知时 CameraX 返回 INTRINSIC_ZOOM_RATIO_UNKNOWN(1.0f)，与真实的 1.0x 无法区分，一律当作未知
            intrinsicZoomRatio = runCatching { info.intrinsicZoomRatio }.getOrNull()
                ?.takeIf { it > 0f && it != CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN },
            // 两个来源取更小者：zoomState 在部分机型上固定报 1.0
            minZoomRatio = listOfNotNull(zoomRange?.lower, zoomStateMin).minOrNull(),
            physicalCandidates = physicals,
        )
    }

    /** 把用户选择的镜头解析成可绑定的 selector。失败时退回可用镜头，不抛异常。 */
    fun resolve(provider: ProcessCameraProvider, lens: CameraLens): LensResolution {
        val candidates = runCatching { candidatesOf(provider) }.getOrDefault(emptyList())
        val defaultBackId = firstIdMatching(provider, CameraSelector.DEFAULT_BACK_CAMERA)
        val defaultFrontId = firstIdMatching(provider, CameraSelector.DEFAULT_FRONT_CAMERA)
        val choice = choose(lens, candidates, defaultBackId, defaultFrontId)
        return toResolution(lens, choice, candidates)
    }

    private fun firstIdMatching(provider: ProcessCameraProvider, selector: CameraSelector): String? = runCatching {
        val filtered = selector.filter(provider.availableCameraInfos)
        filtered.firstOrNull()?.let { Camera2CameraInfo.from(it).getCameraId() }
    }.getOrNull()

    private fun toResolution(
        lens: CameraLens,
        choice: LensChoice,
        candidates: List<LensCandidate>,
    ): LensResolution = when (choice) {
        is LensChoice.ById -> LensResolution(
            lens = lens,
            selector = selectorForId(choice.id, candidates.firstOrNull { it.id == choice.id }?.facingBack ?: true),
            strategy = choice.strategy,
            cameraId = choice.id,
            physicalId = null,
            zoomRatio = null,
            description = describe(choice.strategy, candidates.firstOrNull { it.id == choice.id }, null),
        )
        is LensChoice.ByPhysical -> LensResolution(
            lens = lens,
            selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .setPhysicalCameraId(choice.physicalId)
                .build(),
            strategy = LensStrategy.ULTRA_WIDE_PHYSICAL,
            cameraId = choice.logicalId,
            physicalId = choice.physicalId,
            zoomRatio = null,
            description = "超广角(物理镜头 " + choice.physicalId + ")",
        )
        is LensChoice.ByZoom -> LensResolution(
            lens = lens,
            selector = CameraSelector.DEFAULT_BACK_CAMERA,
            strategy = LensStrategy.ULTRA_WIDE_ZOOM,
            cameraId = choice.id,
            physicalId = null,
            zoomRatio = choice.zoomRatio,
            description = "超广角(变焦 " + String.format("%.1f", choice.zoomRatio) + "x)",
        )
        is LensChoice.Fallback -> LensResolution(
            lens = lens,
            selector = if (choice.facingBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
            strategy = LensStrategy.ULTRA_WIDE_FALLBACK,
            cameraId = choice.id,
            physicalId = null,
            zoomRatio = null,
            description = choice.note,
        )
    }

    /** 按 cameraId 精确选相机；该 id 不可用时由 CameraX 抛出，调用方会记错误并重试。 */
    private fun selectorForId(id: String, facingBack: Boolean): CameraSelector =
        CameraSelector.Builder()
            .requireLensFacing(if (facingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).getCameraId() == id } }
            .build()

    private fun describe(strategy: LensStrategy, candidate: LensCandidate?, zoom: Float?): String {
        val fov = candidate?.horizontalFovDeg
        val suffix = if (fov != null) "  FOV " + String.format("%.0f", fov) + "°" else ""
        return when (strategy) {
            LensStrategy.FRONT -> "前置" + suffix
            LensStrategy.BACK -> "后置" + suffix
            LensStrategy.ULTRA_WIDE_DIRECT -> "超广角(独立镜头)" + suffix
            LensStrategy.ULTRA_WIDE_PHYSICAL -> "超广角(物理镜头)" + suffix
            LensStrategy.ULTRA_WIDE_ZOOM -> "超广角(变焦 " + String.format("%.1f", zoom ?: 1f) + "x)"
            LensStrategy.ULTRA_WIDE_FALLBACK -> "未开放超广角，已用普通后摄"
        }
    }

    /**
     * 绑定后的收尾动作。变焦路径必须在每次绑定后重新设置，否则重连相机时会回到 1.0x。
     * 先用公开的 setZoomRatio；若被 CameraX 的 zoomState 下限挡住（部分机型固定报 1.0），
     * 再用 Camera2 直接写 CONTROL_ZOOM_RATIO。
     */
    fun afterBind(camera: Camera, resolution: LensResolution) {
        val ratio = resolution.zoomRatio ?: return
        try {
            val min = camera.cameraInfo.zoomState.value?.minZoomRatio
            if (min != null && ratio >= min) {
                camera.cameraControl.setZoomRatio(ratio)
                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "setZoomRatio failed, falling back to camera2", e)
        }
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
                .build()
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
        } catch (e: Throwable) {
            Log.w(TAG, "camera2 zoom ratio failed", e)
        }
    }

    /**
     * 列出系统里所有相机（含 CameraX 不可见的隐藏 id），用于判断机型到底开放了什么。
     * 任何一个 id 读取失败都只记在该行的 error 字段里。
     */
    fun diagnostics(context: Context, provider: ProcessCameraProvider?): List<CameraDiag> {
        val visibleIds = provider?.let { p ->
            runCatching { candidatesOf(p).map { it.id }.toSet() }.getOrDefault(emptySet())
        } ?: emptySet()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return listOf(errorDiag("-", "无法获取 CameraManager"))
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val probe = (listed + PROBE_IDS).distinct()
        return probe.mapNotNull { id -> diagOf(manager, id, listed, visibleIds) }
    }

    private fun diagOf(
        manager: CameraManager,
        id: String,
        listed: List<String>,
        visibleIds: Set<String>,
    ): CameraDiag? {
        val hidden = id !in listed
        val chars = try {
            manager.getCameraCharacteristics(id)
        } catch (e: Throwable) {
            // 隐藏 id 读不到属于正常情况，不列出来以免刷屏
            return if (hidden) null else errorDiag(id, e.message ?: e::class.java.simpleName)
        }
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
            else -> "未知"
        }
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        val fov = focals.minOrNull()?.let { f ->
            sensorWidth?.takeIf { it > 0f && f > 0f }?.let { horizontalFovDeg(f, it) }
        }
        val zoom: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val physicals = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { chars.physicalCameraIds.toList() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val logical = capabilities?.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        } ?: false
        return CameraDiag(
            id = id,
            facing = facing,
            focalLengthsMm = focals,
            sensorWidthMm = sensorWidth,
            hFovDeg = fov,
            zoomRange = zoom?.let { String.format("%.1f-%.1f", it.lower, it.upper) },
            physicalIds = physicals,
            logicalMultiCamera = logical,
            visibleToCameraX = id in visibleIds,
            hidden = hidden,
            error = null,
        )
    }

    private fun errorDiag(id: String, message: String) = CameraDiag(
        id = id,
        facing = "?",
        focalLengthsMm = emptyList(),
        sensorWidthMm = null,
        hFovDeg = null,
        zoomRange = null,
        physicalIds = emptyList(),
        logicalMultiCamera = false,
        visibleToCameraX = false,
        hidden = true,
        error = message,
    )

    /** 常见的副摄隐藏 id，用于探测系统是否对第三方屏蔽了它们。 */
    private val PROBE_IDS = listOf("2", "3", "4", "5", "6", "7", "8", "9", "20", "21", "22", "23")
}
