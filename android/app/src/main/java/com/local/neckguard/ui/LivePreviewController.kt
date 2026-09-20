package com.local.neckguard.ui

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.local.neckguard.data.SettingsRepository
import com.local.neckguard.pose.FrameResult
import com.local.neckguard.pose.GeometryConfig
import com.local.neckguard.pose.PoseFrame
import com.local.neckguard.pose.PoseLandmarkerEngine
import com.local.neckguard.pose.PostureAnalyzer
import com.local.neckguard.pose.PostureGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

/** 摆放/校准页面的实时预览：Preview + ImageAnalysis 绑到 Activity 生命周期。 */
class LivePreviewController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
) {
    data class LiveState(
        val ready: Boolean = false,
        val error: String? = null,
        val result: FrameResult? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val inferenceMillis: Long = 0L,
        val fps: Float = 0f,
        val useFrontCamera: Boolean = true,
        val calibrating: Boolean = false,
        val calibrationProgress: Float = 0f,
        val calibrationSamples: Int = 0,
        val baselineDeg: Float? = null,
        val thresholdDeg: Float = 40f,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    private var engine: PoseLandmarkerEngine? = null
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var settingsJob: Job? = null

    private val calibrationSamples = ArrayList<Float>()

    @Volatile
    private var calibrationStart = 0L

    @Volatile
    private var calibrationDuration = 0L
    private var fpsWindowStart = 0L
    private var fpsCount = 0

    @Volatile
    private var geometryConfig = GeometryConfig()

    fun start(owner: LifecycleOwner, previewView: PreviewView) {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settings.collect { s ->
                geometryConfig = s.toGeometryConfig()
                _state.update {
                    it.copy(
                        useFrontCamera = s.useFrontCamera,
                        baselineDeg = s.baselineDeg,
                        thresholdDeg = PostureAnalyzer.thresholdFor(s.baselineDeg, s.toAnalyzerConfig()),
                    )
                }
            }
        }
        scope.launch {
            val s = settingsRepo.current()
            val eng = PoseLandmarkerEngine(context, ::onFrame, ::onError)
            if (!eng.start()) {
                _state.update { it.copy(error = "姿态模型加载失败") }
                return@launch
            }
            engine = eng
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val p = future.get()
                        provider = p
                        bind(p, owner, previewView, s.useFrontCamera)
                        _state.update { it.copy(ready = true, error = null) }
                    } catch (e: Exception) {
                        Log.e(TAG, "camera init failed", e)
                        _state.update { it.copy(error = "相机初始化失败: ${e.message}") }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView, front: Boolean) {
        val eng = engine ?: return
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        analysis.setAnalyzer(executor) { proxy -> eng.submit(proxy) }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        p.unbindAll()
        p.bindToLifecycle(owner, selector, preview, analysis)
    }

    fun switchCamera(owner: LifecycleOwner, previewView: PreviewView) {
        val p = provider ?: return
        val front = !_state.value.useFrontCamera
        scope.launch { settingsRepo.update { it.copy(useFrontCamera = front) } }
        try {
            bind(p, owner, previewView, front)
        } catch (e: Exception) {
            _state.update { it.copy(error = "切换摄像头失败: ${e.message}") }
        }
    }

    fun startCalibration(durationMillis: Long = 3_000L) {
        synchronized(calibrationSamples) { calibrationSamples.clear() }
        calibrationDuration = durationMillis
        calibrationStart = System.currentTimeMillis()
        _state.update {
            it.copy(calibrating = true, calibrationProgress = 0f, calibrationSamples = 0, message = "请保持端正坐姿")
        }
    }

    fun clearBaseline() {
        scope.launch {
            settingsRepo.setBaseline(null)
            _state.update { it.copy(message = "已清除校准，使用绝对阈值") }
        }
    }

    private fun onFrame(frame: PoseFrame) {
        try {
            val now = System.currentTimeMillis()
            if (fpsWindowStart == 0L) fpsWindowStart = now
            fpsCount++
            var fps = _state.value.fps
            if (now - fpsWindowStart >= 1_000L) {
                fps = fpsCount * 1000f / (now - fpsWindowStart)
                fpsWindowStart = now
                fpsCount = 0
            }
            val result = PostureGeometry.analyze(frame.landmarks, frame.bitmap.width, frame.bitmap.height, geometryConfig)
            _state.update {
                it.copy(
                    result = result,
                    imageWidth = frame.bitmap.width,
                    imageHeight = frame.bitmap.height,
                    inferenceMillis = frame.inferenceMillis,
                    fps = fps,
                )
            }
            if (calibrationStart > 0L) handleCalibration(result, now)
        } catch (e: Throwable) {
            Log.w(TAG, "onFrame failed", e)
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun handleCalibration(result: FrameResult, now: Long) {
        val elapsed = now - calibrationStart
        if (result is FrameResult.Valid) {
            synchronized(calibrationSamples) { calibrationSamples.add(result.measurement.neckInclinationDeg) }
        }
        val count = synchronized(calibrationSamples) { calibrationSamples.size }
        if (elapsed < calibrationDuration) {
            _state.update { it.copy(calibrationProgress = elapsed.toFloat() / calibrationDuration, calibrationSamples = count) }
            return
        }
        calibrationStart = 0L
        val samples = synchronized(calibrationSamples) { calibrationSamples.toList() }
        if (samples.size < MIN_CALIBRATION_SAMPLES) {
            _state.update {
                it.copy(calibrating = false, message = "校准失败：有效帧不足（${samples.size}），请确认侧面对齐后重试")
            }
            return
        }
        val baseline = PostureAnalyzer.median(samples)
        scope.launch {
            settingsRepo.setBaseline(baseline)
            _state.update {
                it.copy(calibrating = false, message = String.format(Locale.US, "校准完成，基线 %.1f°", baseline))
            }
        }
    }

    private fun onError(e: Throwable) {
        Log.w(TAG, "engine error", e)
        _state.update { it.copy(error = e.message) }
    }

    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        provider = null
        engine?.stop()
        engine = null
        executor.shutdown()
        _state.update { it.copy(ready = false, result = null) }
    }

    companion object {
        private const val TAG = "LivePreview"
        private const val MIN_CALIBRATION_SAMPLES = 10
    }
}
