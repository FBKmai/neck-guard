package com.local.neckguard.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.data.Settings
import com.local.neckguard.data.SettingsRepository
import com.local.neckguard.pose.FrameResult
import com.local.neckguard.pose.PoseFrame
import com.local.neckguard.pose.PoseLandmarkerEngine
import com.local.neckguard.pose.PostureAnalyzer
import com.local.neckguard.pose.PostureGeometry
import com.local.neckguard.pose.WindowOutcome
import com.local.neckguard.pose.WindowVerdict
import com.local.neckguard.report.CompositeSink
import com.local.neckguard.report.EventSink
import com.local.neckguard.report.LocalNotificationSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * 前台服务（camera 类型）：按「等待 -> 采样窗 -> 判定」循环工作。
 * 只在采样窗内绑定相机，窗外释放，省电且不常亮相机指示灯。
 */
class MonitorService : LifecycleService() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var eventLog: EventLog
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var notifier: AlertNotifier
    private lateinit var sink: EventSink

    private val analyzer = PostureAnalyzer()
    private var engine: PoseLandmarkerEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    @Volatile
    private var settings: Settings = Settings()

    // 采样窗内状态：MediaPipe 回调线程写，调度协程读；用 windowOpen 与 windowLock 保护
    private val windowOpen = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val windowJpegs = HashMap<Int, ByteArray>()
    private val windowLock = Any()

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        eventLog = EventLog(applicationContext)
        snapshotStore = SnapshotStore(applicationContext)
        notifier = AlertNotifier(applicationContext)
        sink = CompositeSink(listOf(LocalNotificationSink(notifier)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                START_NOT_STICKY
            }
            else -> {
                if (loopJob?.isActive != true) startMonitoring()
                START_STICKY
            }
        }
    }

    private fun startMonitoring() {
        val notification = notifier.buildStatusNotification("正在启动")
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, AlertNotifier.STATUS_ID, notification, type)
        } catch (e: Exception) {
            // Android 14+ 在后台启动 camera 类型前台服务会抛 ForegroundServiceStartNotAllowedException
            Log.e(TAG, "startForeground failed", e)
            val message = "无法启动前台服务，请重新打开应用并点击开始监测: ${e.message}"
            MonitorBus.update { it.copy(running = false, phase = MonitorPhase.ERROR, lastError = message) }
            notifier.postError(message)
            stopSelf()
            return
        }
        acquireWakeLock()
        MonitorBus.update {
            MonitorState(running = true, phase = MonitorPhase.STARTING, startedAtMillis = System.currentTimeMillis())
        }

        loopJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                settings = settingsRepo.current()
                analyzer.config = settings.toAnalyzerConfig()
                analyzer.setBaseline(settings.baselineDeg)
                MonitorBus.update { it.copy(thresholdDeg = analyzer.thresholdDeg, baselineDeg = analyzer.baselineDeg) }

                val eng = PoseLandmarkerEngine(applicationContext, ::onPoseFrame, ::onEngineError)
                if (!eng.start()) {
                    fail("姿态模型加载失败，已停止监测")
                    return@launch
                }
                engine = eng
                cameraProvider = awaitCameraProvider()
                eventLog.append(
                    PostureEvent(System.currentTimeMillis(), EventType.INFO, message = "监测启动，阈值 ${fmt(analyzer.thresholdDeg)}°"),
                )

                // 启动后先立刻采一窗，便于用户确认工作正常
                var waitMillis = 3_000L
                while (true) {
                    val nextAt = System.currentTimeMillis() + waitMillis
                    MonitorBus.update { it.copy(phase = MonitorPhase.WAITING, nextSampleAtMillis = nextAt) }
                    notifier.updateStatus(statusText())
                    delay(waitMillis)

                    // 每个窗前重新读一次设置，用户改参数即时生效
                    settings = settingsRepo.current()
                    analyzer.config = settings.toAnalyzerConfig()
                    if (analyzer.baselineDeg != settings.baselineDeg) analyzer.setBaseline(settings.baselineDeg)

                    runWindow()
                    waitMillis = nextInterval()
                }
            } catch (_: CancellationException) {
                // 正常停止
            } catch (e: Throwable) {
                Log.e(TAG, "monitor loop crashed", e)
                fail("监测循环异常: ${e.message}")
            }
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(applicationContext),
        )
    }

    private fun nextInterval(): Long {
        val base = settings.intervalSec.coerceAtLeast(5)
        val jitter = settings.jitterSec.coerceAtLeast(0)
        val sec = base + if (jitter == 0) 0 else Random.nextInt(-jitter, jitter + 1)
        return sec.coerceAtLeast(5) * 1000L
    }

    private suspend fun runWindow() {
        val windowMillis = settings.windowSec.coerceIn(1, 15) * 1000L
        MonitorBus.update { it.copy(phase = MonitorPhase.SAMPLING) }
        synchronized(windowLock) { windowJpegs.clear() }
        analyzer.beginWindow()
        lastFrameAt.set(0L)
        windowOpen.set(true)

        val bound = bindCamera()
        if (!bound) {
            windowOpen.set(false)
            return
        }
        try {
            // 相机启动约需 0.5-1 s，窗计时从第一帧到达开始；最多等 5 s
            val deadline = System.currentTimeMillis() + 5_000L
            while (lastFrameAt.get() == 0L && System.currentTimeMillis() < deadline) delay(100L)
            if (lastFrameAt.get() == 0L) {
                recordError("采样窗 5 秒内没有收到相机帧（相机可能被占用）")
            } else {
                delay(windowMillis)
            }
        } finally {
            windowOpen.set(false)
            unbindCamera()
        }

        val now = System.currentTimeMillis()
        val outcome = analyzer.endWindow(now)
        handleOutcome(outcome, now)
    }

    private suspend fun handleOutcome(outcome: WindowOutcome, now: Long) {
        val s = outcome.summary
        MonitorBus.update {
            it.copy(
                lastSampleAtMillis = now,
                lastSummary = s,
                badStreak = outcome.badStreak,
                thresholdDeg = s.thresholdDeg,
                baselineDeg = analyzer.baselineDeg,
                windowsRun = it.windowsRun + 1,
                invalidWindows = it.invalidWindows + if (s.verdict == WindowVerdict.INVALID) 1 else 0,
            )
        }
        val jpeg = s.representativeFrameIndex?.let { idx -> synchronized(windowLock) { windowJpegs[idx] } }
        synchronized(windowLock) { windowJpegs.clear() }

        if (settings.saveEveryWindowSnapshot && s.verdict != WindowVerdict.INVALID) {
            val file = jpeg?.let { snapshotStore.save(it, s.representative, s.medianNeckDeg, s.thresholdDeg, now) }
            eventLog.append(
                PostureEvent(
                    timestampMillis = now,
                    type = EventType.WINDOW,
                    neckDeg = s.medianNeckDeg,
                    torsoDeg = s.medianTorsoDeg,
                    thresholdDeg = s.thresholdDeg,
                    message = "${s.verdict} 有效帧 ${s.validFrames}/${s.totalFrames}",
                    snapshotPath = file?.absolutePath,
                ),
            )
        }

        if (outcome.shouldRecord) {
            val file = jpeg?.let { snapshotStore.save(it, s.representative, s.medianNeckDeg, s.thresholdDeg, now) }
            val type = if (outcome.shouldNotify) EventType.ALERT else EventType.CONFIRMED
            val event = PostureEvent(
                timestampMillis = now,
                type = type,
                neckDeg = s.medianNeckDeg,
                torsoDeg = s.medianTorsoDeg,
                thresholdDeg = s.thresholdDeg,
                message = "连续 ${outcome.badStreak} 窗前倾",
                snapshotPath = file?.absolutePath,
            )
            eventLog.append(event)
            if (outcome.shouldNotify) {
                sink.deliver(event, file)
                MonitorBus.update { it.copy(alertsSent = it.alertsSent + 1, lastSnapshotPath = file?.absolutePath) }
            }
        }
        notifier.updateStatus(statusText())
    }

    /** MediaPipe 回调线程。 */
    private fun onPoseFrame(frame: PoseFrame) {
        try {
            if (!windowOpen.get()) return
            lastFrameAt.set(System.currentTimeMillis())
            val result = PostureGeometry.analyze(
                frame.landmarks, frame.bitmap.width, frame.bitmap.height, settings.toGeometryConfig(),
            )
            val index = analyzer.addFrame(result)
            if (result is FrameResult.Valid) {
                val jpeg = snapshotStore.encodeJpeg(frame.bitmap)
                synchronized(windowLock) {
                    if (windowJpegs.size < MAX_JPEGS_PER_WINDOW) windowJpegs[index] = jpeg
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "process frame failed", e)
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun onEngineError(e: Throwable) {
        Log.w(TAG, "engine error", e)
        lifecycleScope.launch { recordError("推理错误: ${e.message}") }
    }

    private suspend fun bindCamera(): Boolean = withContext(Dispatchers.Main) {
        val provider = cameraProvider ?: return@withContext false
        val eng = engine ?: return@withContext false
        try {
            val selector = if (settings.useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // 服务里没有窗口，固定按竖屏自然方向旋转，要求手机竖放
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> eng.submit(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this@MonitorService, selector, analysis)
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind camera failed", e)
            recordError("相机打开失败: ${e.message}")
            false
        }
    }

    private suspend fun unbindCamera() = withContext(Dispatchers.Main) {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind camera failed", e)
        }
    }

    private suspend fun recordError(message: String) {
        Log.w(TAG, message)
        MonitorBus.update { it.copy(lastError = message) }
        eventLog.append(PostureEvent(System.currentTimeMillis(), EventType.ERROR, message = message))
        notifier.updateStatus("异常: $message")
    }

    private suspend fun fail(message: String) {
        recordError(message)
        notifier.postError(message)
        MonitorBus.update { it.copy(running = false, phase = MonitorPhase.ERROR) }
        withContext(Dispatchers.Main) { stopSelfSafely() }
    }

    private fun statusText(): String {
        val st = MonitorBus.state.value
        val last = st.lastSummary
        val lastText = when {
            last == null -> "尚未采样"
            last.verdict == WindowVerdict.INVALID -> "上次采样无效(有效帧 ${last.validFrames})"
            else -> "上次 ${fmt(last.medianNeckDeg)}° / 阈值 ${fmt(last.thresholdDeg)}° " +
                if (last.verdict == WindowVerdict.BAD) "前倾" else "正常"
        }
        return "$lastText，已采样 ${st.windowsRun} 次，提醒 ${st.alertsSent} 次"
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeckGuard:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock failed", e)
        }
    }

    private fun releaseResources() {
        loopJob?.cancel()
        loopJob = null
        windowOpen.set(false)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        engine?.stop()
        engine = null
        MonitorBus.update {
            it.copy(
                running = false,
                nextSampleAtMillis = null,
                phase = if (it.phase == MonitorPhase.ERROR) it.phase else MonitorPhase.IDLE,
            )
        }
    }

    private fun stopSelfSafely() {
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseResources()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_START = "com.local.neckguard.action.START"
        const val ACTION_STOP = "com.local.neckguard.action.STOP"
        private const val MAX_JPEGS_PER_WINDOW = 40

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun fmt(v: Float?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
    }
}
