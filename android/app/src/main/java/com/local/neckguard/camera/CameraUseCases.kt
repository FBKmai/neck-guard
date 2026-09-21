package com.local.neckguard.camera

import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.Executor

/**
 * 预览页与监测服务共用的 use case 构建器。
 * 两处必须用完全一致的分辨率与宽高比策略，否则同一姿势在两边算出的角度会有差异。
 */
object CameraUseCases {

    private const val TAG = "CameraUseCases"

    private fun aspectRatio4x3(): AspectRatioStrategy = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

    fun analysisResolutionSelector(resolution: AnalysisResolution): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio4x3())
            .setResolutionStrategy(
                ResolutionStrategy(resolution.size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()

    /**
     * 构建分析用例。
     * 输出 YUV_420_888：被节流丢弃的帧不会触发 CameraX 内部的 RGBA 转换，省下大量无用拷贝。
     * analyzer 必须自己关闭 ImageProxy，否则 KEEP_ONLY_LATEST 队列会卡住。
     */
    fun buildAnalysis(
        resolution: AnalysisResolution,
        executor: Executor,
        analyzer: (ImageProxy) -> Unit,
    ): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector(resolution))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            // 服务里没有窗口，固定按竖屏自然方向旋转，要求手机竖放
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        analyzer(proxy)
                    } catch (e: Throwable) {
                        // 兜底：分析器自身异常时也要关掉帧，否则相机会停止出帧
                        Log.w(TAG, "analyzer threw, closing frame", e)
                        try {
                            proxy.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

    fun buildPreview(): Preview =
        Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatio4x3()).build())
            .setTargetRotation(Surface.ROTATION_0)
            .build()
}
