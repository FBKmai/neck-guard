package com.local.neckguard.ui

import android.content.Context
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.local.neckguard.camera.AnalysisResolution
import com.local.neckguard.camera.CameraLens
import com.local.neckguard.camera.CameraLensResolver
import com.local.neckguard.camera.CameraUseCases
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
        val lens: CameraLens = CameraLens.FRONT,
        /** 实际解析到的镜头说明，例如"超广角(变焦 0.6x)"或"未开放超广角，已用普通后摄"。 */
        val lensDescription: String? = null,
        val delegate: String? = null,
        val analysisSize: String? = null,
        val calibrating: Boolean = false,
        val calibrationProgress: Float = 0f,
        val calibrationSamples: Int = 0,
        val baselineDeg: Float? = null,
        val thresholdDeg: Float = 40f,
        val message: String? = null,
    ) {
        /** 前置预览是镜像的，叠加层要跟着翻。 */
        val mirrored: Boolean get() = lens == CameraLens.FRONT
    }

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

    @Volatile
    private var analysisResolution = AnalysisResolution.R640x480

    fun start(owner: LifecycleOwner, previewView: PreviewView) {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settings.collect { s ->
                geometryConfig = s.toGeometryConfig()
                analysisResolution = s.analysisResolution
                _state.update {
                    it.copy(
                        lens = s.cameraLens,
                        analysisSize = s.analysisResolution.label,
                        baselineDeg = s.baselineDeg,
                        thresholdDeg = PostureAnalyzer.thresholdFor(s.baselineDeg, s.toAnalyzerConfig()),
                    )
                }
            }
        }
        scope.launch {
            val s = settingsRepo.current()
            analysisResolution = s.analysisResolution
            // 预览页按确认模式的帧率跑，校准需要足够的帧密度
            val eng = createEngine(s.fastIntervalMillis.toLong(), s.useGpu)
            if (eng == null) {
                _state.update { it.copy(error = "姿态模型加载失败") }
                return@launch
            }
            engine = eng
            _state.update { it.copy(delegate = eng.delegateName) }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val p = future.get()
                        provider = p
                        bind(p, owner, previewView, s.cameraLens)
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

    /** GPU 委托创建失败时自动回退 CPU，避免预览页直接白屏。 */
    private fun createEngine(intervalMillis: Long, useGpu: Boolean): PoseLandmarkerEngine? {
        if (useGpu) {
            val gpu = PoseLandmarkerEngine(context, ::onFrame, ::onError, intervalMillis, useGpu = true)
            if (gpu.start()) return gpu
            Log.w(TAG, "GPU delegate failed, falling back to CPU")
            _state.update { it.copy(message = "GPU 加速不可用，已回退 CPU") }
        }
        val cpu = PoseLandmarkerEngine(context, ::onFrame, ::onError, intervalMillis, useGpu = false)
        return if (cpu.start()) cpu else null
    }

    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView, lens: CameraLens) {
        val eng = engine ?: return
        val preview = CameraUseCases.buildPreview().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = CameraUseCases.buildAnalysis(analysisResolution, executor) { proxy ->
            val current = engine
            if (current == null) proxy.close() else current.submit(proxy)
        }
        val resolution = CameraLensResolver.resolve(p, lens)
        p.unbindAll()
        val camera = p.bindToLifecycle(owner, resolution.selector, preview, analysis)
        CameraLensResolver.afterBind(camera, resolution)
        _state.update { it.copy(lens = lens, lensDescription = resolution.description, delegate = eng.delegateName) }
    }

    /** 前置 -> 后置 -> 后置超广角 循环。 */
    fun switchCamera(owner: LifecycleOwner, previewView: PreviewView) {
        val p = provider ?: return
        val next = _state.value.lens.next()
        scope.launch { settingsRepo.update { it.copy(cameraLens = next) } }
        try {
            bind(p, owner, previewView, next)
        } catch (e: Exception) {
            Log.w(TAG, "switch camera failed", e)
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
                it.copy(
                    calibrating = false,
                    message = "校准失败：有效帧不足（${samples.size}），请确认已侧面对齐、且画面里能看到髋部后重试",
                )
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
