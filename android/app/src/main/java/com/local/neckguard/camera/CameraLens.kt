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

/**
 * 分析帧分辨率。越大截图越清晰，YUV 转换与 JPEG 编码开销也越大。
 *
 * 本机检测（手机自己跑 MediaPipe）建议不超过 960x720，再高只是让推理变慢。
 * 推流给电脑检测时可以往上走：电脑端 YOLO 的 imgsz 应与推流分辨率取齐，
 * imgsz 超过源图尺寸只是把画面插值放大，实测反而会让关键点定位漂移。
 * 1920x1440 及以上受 Wi-Fi 带宽限制，掉帧比分辨率低更伤判定，按需再选。
 */
enum class AnalysisResolution(val width: Int, val height: Int) {
    R640x480(640, 480),
    R960x720(960, 720),
    R1280x960(1280, 960),
    R1600x1200(1600, 1200),
    R1920x1440(1920, 1440),
    ;

    val size: Size get() = Size(width, height)
    val label: String get() = width.toString() + "x" + height

    companion object {
        fun parse(name: String?): AnalysisResolution? = name?.let { n -> entries.firstOrNull { it.name == n } }

        /**
         * 手机本机跑 MediaPipe 时可选的档位。
         * 更高的分辨率对 lite 模型没有收益，只会拖慢推理并增加发热，所以不开放。
         */
        val forOnDeviceAnalysis: List<AnalysisResolution> = listOf(R640x480, R960x720, R1280x960)

        /** 推流给电脑检测时可选的档位：电脑算力充裕，瓶颈在 Wi-Fi 带宽。 */
        val forStreaming: List<AnalysisResolution> = entries.toList()
    }
}
