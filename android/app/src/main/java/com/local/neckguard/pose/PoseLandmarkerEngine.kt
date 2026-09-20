package com.local.neckguard.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 一帧的推理输出：已旋转到正向的原图 + 关键点（画面里没人时为 null）。 */
class PoseFrame(
    val bitmap: Bitmap,
    val landmarks: List<Landmark>?,
    val timestampMillis: Long,
    /** 从送入到返回的耗时，用于监控性能。 */
    val inferenceMillis: Long,
)

/**
 * MediaPipe Pose Landmarker 的 LIVE_STREAM 封装。
 * - submit 在 CameraX 分析线程调用，做 ImageProxy -> Bitmap（旋转）-> MPImage -> detectAsync。
 * - 结果回调在 MediaPipe 内部串行队列上，通过 onFrame 上抛。
 * - 所有异常通过 onError 上抛，不抛出到调用方。
 */
class PoseLandmarkerEngine(
    private val context: Context,
    private val onFrame: (PoseFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val minIntervalMillis: Long = 100L,
    private val useGpu: Boolean = false,
) {
    private var landmarker: PoseLandmarker? = null
    private val closed = AtomicBoolean(false)
    private val lastSubmitAt = AtomicLong(0L)
    private val lastTimestamp = AtomicLong(0L)
    private val inFlight = AtomicLong(0L)

    /** 同步加载模型；失败时调用 onError 并返回 false。 */
    fun start(): Boolean {
        if (landmarker != null) return true
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: PoseLandmarkerResult, input: MPImage -> handleResult(result, input) }
                .setErrorListener { e: RuntimeException -> onError(e) }
                .build()
            landmarker = PoseLandmarker.createFromOptions(context, options)
            closed.set(false)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "load pose model failed", e)
            onError(IllegalStateException("姿态模型加载失败: ${e.message}", e))
            false
        }
    }

    /**
     * 送入一帧。无论成功与否都会关闭 imageProxy。
     * 按 minIntervalMillis 节流，避免 JPEG 压缩与推理把 CPU 打满。
     */
    fun submit(imageProxy: ImageProxy) {
        try {
            val lm = landmarker
            if (lm == null || closed.get()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastSubmitAt.get() < minIntervalMillis) return
            // 有一帧在推理时不再堆积，KEEP_ONLY_LATEST 会丢弃中间帧；
            // 若 MediaPipe 内部丢帧导致回调缺失，超过 STALL_MILLIS 后强制复位，避免永久卡死
            if (inFlight.get() > 0) {
                if (now - lastSubmitAt.get() < STALL_MILLIS) return
                Log.w(TAG, "inference stalled, resetting in-flight counter")
                inFlight.set(0L)
            }
            lastSubmitAt.set(now)

            val bitmap = toUprightBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap).build()
            // LIVE_STREAM 要求时间戳严格递增
            val ts = maxOf(now, lastTimestamp.get() + 1)
            lastTimestamp.set(ts)
            inFlight.incrementAndGet()
            try {
                lm.detectAsync(mpImage, ts)
            } catch (e: Throwable) {
                inFlight.decrementAndGet()
                throw e
            }
        } catch (e: Throwable) {
            Log.w(TAG, "submit frame failed", e)
            onError(e)
        } finally {
            try {
                imageProxy.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleResult(result: PoseLandmarkerResult, input: MPImage) {
        inFlight.decrementAndGet()
        if (closed.get()) return
        try {
            val bitmap = BitmapExtractor.extract(input)
            val poses = result.landmarks()
            val landmarks = if (poses.isNullOrEmpty()) {
                null
            } else {
                poses[0].map { lm ->
                    Landmark(
                        x = lm.x(),
                        y = lm.y(),
                        visibility = lm.visibility().orElse(1f),
                    )
                }
            }
            val ts = result.timestampMs()
            val elapsed = (SystemClock.uptimeMillis() - ts).coerceAtLeast(0L)
            onFrame(PoseFrame(bitmap, landmarks, ts, elapsed))
        } catch (e: Throwable) {
            Log.w(TAG, "handle result failed", e)
            onError(e)
        }
    }

    fun stop() {
        closed.set(true)
        val lm = landmarker
        landmarker = null
        try {
            lm?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close landmarker failed", e)
        }
        inFlight.set(0L)
    }

    companion object {
        private const val TAG = "PoseEngine"
        const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val STALL_MILLIS = 1_500L

        /** 把分析帧按 rotationDegrees 旋转到正向（与 targetRotation 对齐）。 */
        fun toUprightBitmap(imageProxy: ImageProxy): Bitmap {
            val raw = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation == 0) return raw
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated !== raw) raw.recycle()
            return rotated
        }
    }
}
