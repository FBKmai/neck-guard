package com.local.neckguard.camera

import android.util.Size

/**
 * 用户可选的镜头。
 * BACK_ULTRA_WIDE 在部分机型（尤其是 MIUI）上可能不向第三方开放，
 * 解析失败时会退回普通后置并在界面上说明。
 */
enum class CameraLens(val label: String) {
    FRONT("前置"),
    BACK("后置"),
    BACK_ULTRA_WIDE("后置超广角"),
    ;

    /** 三态循环切换。 */
    fun next(): CameraLens = when (this) {
        FRONT -> BACK
        BACK -> BACK_ULTRA_WIDE
        BACK_ULTRA_WIDE -> FRONT
    }

    companion object {
        fun parse(name: String?): CameraLens? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/** 分析帧分辨率。越大截图越清晰，YUV 转换与 JPEG 编码开销也越大。 */
enum class AnalysisResolution(val width: Int, val height: Int) {
    R640x480(640, 480),
    R960x720(960, 720),
    R1280x960(1280, 960),
    ;

    val size: Size get() = Size(width, height)
    val label: String get() = width.toString() + "x" + height

    companion object {
        fun parse(name: String?): AnalysisResolution? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
