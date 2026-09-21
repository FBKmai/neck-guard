package com.local.neckguard.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.local.neckguard.camera.CameraLensResolver
import com.local.neckguard.camera.CameraUseCases
import com.local.neckguard.camera.LensResolution
import com.local.neckguard.data.DetectionMode
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.data.Settings
import com.local.neckguard.data.SettingsRepository
import com.local.neckguard.pose.FrameResult
import com.local.neckguard.pose.ModeChangeReason
import com.local.neckguard.pose.PoseFrame
import com.local.neckguard.pose.PoseLandmarkerEngine
import com.local.neckguard.pose.PostureGeometry
import com.local.neckguard.pose.PostureMeasurement
import com.local.neckguard.pose.PostureTracker
import com.local.neckguard.pose.TrackerEvent
import com.local.neckguard.pose.TrackerMode
import com.local.neckguard.pose.WindowOutcome
import com.local.neckguard.pose.WindowVerdict
import com.local.neckguard.report.CameraDiscoveryResponder
import com.local.neckguard.report.CompositeSink
import com.local.neckguard.report.EventSink
import com.local.neckguard.report.LocalNotificationSink
import com.local.neckguard.report.MjpegServer
import com.local.neckguard.report.PcSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 前台服务（camera 类型）：相机常开，按「巡检 -> 确认」双速循环工作。
 *
 * 巡检期约 1.4 fps，发现疑似前倾后切到约 8 fps 的确认窗，连续 K 个窗判为前倾才提醒。
 * 相比 v0.2 的定时采样窗，两次采样之间不再有监控盲区。
 */
class MonitorService : LifecycleService() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var eventLog: EventLog
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var notifier: AlertNotifier
    private lateinit var tracker: PostureTracker

    @Volatile
    private var sink: EventSink? = null

    @Volatile
    private var pcSink: PcSink? = null

    @Volatile
    private var engine: PoseLandmarkerEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile
    private var lensResolution: LensResolution? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** JPEG 编码专用单线程：FIFO 保证窗结束时此前排队的编码都已落桶。 */
    private val encodeExecutor = Executors.newSingleThreadExecutor()
    private val encodeDispatcher = encodeExecutor.asCoroutineDispatcher()
    private val pendingEncodes = AtomicInteger(0)

    private val eventChannel = Channel<List<TrackerEvent>>(Channel.UNLIMITED)
    private val cameraMutex = Mutex()
    private val cameraBound = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val boundAt = AtomicLong(0L)

    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null
    private var gpuFallbackDone = false

    // ---- 无线相机模式 ----
    /** 本次运行是推流而非本机检测。startMonitoring 之后不再变。 */
    @Volatile
    private var streaming = false

    @Volatile
    private var mjpegServer: MjpegServer? = null

    @Volatile
    private var discoveryResponder: CameraDiscoveryResponder? = null

    /** 推流节流：上一帧送出的时刻。只在相机分析线程读写。 */
    private var lastStreamFrameAt = 0L
    private val streamFramesSent = AtomicLong(0L)

    @Volatile
    private var settings: Settings = Settings()

    // 确认窗内的截图缓存。背靠背的相邻窗共用 index 空间，所以必须按 windowSeq 分桶。
    private val windowJpegs = HashMap<Long, HashMap<Int, ByteArray>>()
    private val windowMeasurements = HashMap<Long, HashMap<Int, PostureMeasurement>>()
    private val windowLock = Any()

    private val deviceId: String by lazy {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android"
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        eventLog = EventLog(applicationContext)
        snapshotStore = SnapshotStore(applicationContext)
        notifier = AlertNotifier(applicationContext)
        tracker = PostureTracker()
        sink = CompositeSink(listOf(LocalNotificationSink(notifier)))
    }

    /** 按当前设置重建事件出口：本机通知固定有，电脑上报按开关。 */
    private fun rebuildSink() {
        val sinks = ArrayList<EventSink>()
        sinks.add(LocalNotificationSink(notifier))
        val base = settings.pcBaseUrl
        val pc = if (settings.pcEnabled && base != null) PcSink(base, settings.pcToken, deviceId) else null
        pc?.let(sinks::add)
        pcSink = pc
        sink = CompositeSink(sinks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                START_NOT_STICKY
            }
            else -> {
                // 被系统重建（START_STICKY）时 intent 为 null，按设置里的模式恢复
                val forceStream = intent?.action == ACTION_START_STREAM
                if (loopJob?.isActive != true) startMonitoring(forceStream)
                START_STICKY
            }
        }
    }

    private fun startMonitoring(forceStream: Boolean = false) {
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
                streaming = forceStream || settings.detectionMode == DetectionMode.PC_STREAM
                if (streaming) {
                    startStreaming()
                    return@launch
                }
                tracker = PostureTracker(settings.toAnalyzerConfig(), settings.toTrackerConfig())
                tracker.setBaseline(settings.baselineDeg)
                rebuildSink()

                val eng = createEngine(settings.useGpu, tracker.desiredIntervalMillis)
                if (eng == null) {
                    fail("姿态模型加载失败，已停止监测")
                    return@launch
                }
                engine = eng

                cameraProvider = awaitCameraProvider()
                val resolution = withContext(Dispatchers.Main) {
                    CameraLensResolver.resolve(cameraProvider!!, settings.cameraLens)
                }
                lensResolution = resolution

                MonitorBus.update {
                    it.copy(
                        thresholdDeg = tracker.thresholdDeg,
                        baselineDeg = tracker.baselineDeg,
                        lens = resolution.description,
                        delegate = eng.delegateName,
                        analysisSize = settings.analysisResolution.label,
                    )
                }
                eventLog.append(
                    PostureEvent(
                        System.currentTimeMillis(),
                        EventType.INFO,
                        message = "监测启动，阈值 ${fmt(tracker.thresholdDeg)}°，镜头 ${resolution.description}，${eng.delegateName}",
                    ),
                )

                bindCameraWithRetry()
                launch { consumeEvents() }
                launch { collectSettings() }
                supervise()
            } catch (_: CancellationException) {
                // 正常停止
            } catch (e: Throwable) {
                Log.e(TAG, "monitor loop crashed", e)
                fail("监测循环异常: ${e.message}")
            }
        }
    }

    /**
     * 无线相机模式：不加载姿态模型，相机帧直接编码成 JPEG 推给电脑。
     * 相机绑定、断流重连、WakeLock、常驻通知全部复用本机检测那一套。
     *
     * 声明成 CoroutineScope 的扩展，是为了让里面的 launch 挂在 loopJob 下，
     * 停止监测时能跟着一起取消。
     */
    private suspend fun CoroutineScope.startStreaming() {
        val server = MjpegServer(
            port = settings.streamPort,
            token = settings.streamToken,
            deviceName = Build.MODEL ?: "Android",
            lensDescription = { lensResolution?.description },
            targetFps = { settings.streamFps },
        )
        if (!server.start()) {
            fail("推流端口 ${settings.streamPort} 占用失败：${server.lastError ?: "未知原因"}，请换一个端口")
            return
        }
        mjpegServer = server

        val responder = CameraDiscoveryResponder(
            discoveryPort = settings.streamDiscoveryPort,
            streamPort = { settings.streamPort },
            tokenRequired = { settings.streamToken.isNotBlank() },
            deviceName = Build.MODEL ?: "Android",
        )
        // 发现服务绑不上不致命：电脑端手填地址一样能用
        val discoveryOk = responder.start()
        discoveryResponder = responder

        cameraProvider = awaitCameraProvider()
        val resolution = withContext(Dispatchers.Main) {
            CameraLensResolver.resolve(cameraProvider!!, settings.cameraLens)
        }
        lensResolution = resolution

        val url = "http://${localIpv4() ?: "手机IP"}:${settings.streamPort}/video"
        MonitorBus.update {
            it.copy(
                phase = MonitorPhase.STREAMING,
                streaming = true,
                streamUrl = url,
                streamDiscoveryOn = discoveryOk,
                lens = resolution.description,
                analysisSize = settings.streamResolution.label,
                delegate = "不推理（电脑检测）",
            )
        }
        eventLog.append(
            PostureEvent(
                System.currentTimeMillis(),
                EventType.INFO,
                message = "无线相机启动，$url，${settings.streamResolution.label} @ ${settings.streamFps} fps，" +
                    "镜头 ${resolution.description}" + if (discoveryOk) "，自动发现已开" else "，自动发现未启用",
            ),
        )

        bindCameraWithRetry()
        launch { collectSettings() }
        superviseStream()
    }

    /** 推流模式的看门狗：断流重连 + 刷新常驻通知，不需要驱动确认窗。 */
    private suspend fun superviseStream() {
        var backoffMillis = 1_000L
        var lastStatusAt = 0L
        var lastFpsAt = System.currentTimeMillis()
        var lastFpsFrames = 0L
        while (true) {
            delay(TICK_MILLIS)
            val now = System.currentTimeMillis()

            val silentSince = maxOf(lastFrameAt.get(), boundAt.get())
            if (cameraBound.get() && silentSince > 0L && now - silentSince > NO_FRAME_TIMEOUT_MILLIS) {
                MonitorBus.update { it.copy(phase = MonitorPhase.CAMERA_LOST, cameraRebinds = it.cameraRebinds + 1) }
                recordEvent(EventType.CAMERA, "${NO_FRAME_TIMEOUT_MILLIS / 1000} 秒未收到相机帧，重新绑定（退避 ${backoffMillis / 1000} 秒）")
                unbindCamera()
                delay(backoffMillis)
                bindCameraWithRetry()
                backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            } else if (lastFrameAt.get() > boundAt.get()) {
                backoffMillis = 1_000L
            }

            if (now - lastStatusAt > STATUS_REFRESH_MILLIS) {
                val server = mjpegServer
                val frames = streamFramesSent.get()
                val elapsed = (now - lastFpsAt).coerceAtLeast(1L)
                val fps = (frames - lastFpsFrames) * 1000f / elapsed
                lastFpsAt = now
                lastFpsFrames = frames
                MonitorBus.update {
                    it.copy(
                        phase = if (cameraBound.get()) MonitorPhase.STREAMING else MonitorPhase.CAMERA_LOST,
                        streamClients = server?.clientCount ?: 0,
                        streamFps = fps,
                        streamFramesSent = frames,
                        streamBytesSent = server?.totalBytesSent ?: 0L,
                    )
                }
                notifier.updateStatus(streamStatusText(fps))
                lastStatusAt = now
            }
        }
    }

    private fun streamStatusText(fps: Float): String {
        val st = MonitorBus.state.value
        val where = if (cameraBound.get()) "推流中" else "相机重连中"
        val clients = st.streamClients
        val target = if (clients > 0) "电脑 $clients 台" else "等待电脑连接"
        return "$where，$target，${String.format(Locale.US, "%.1f", fps)} fps，${st.streamUrl ?: ""}"
    }

    /**
     * 本机在局域网里的 IPv4，用于把推流地址显示给用户。
     * 手机上可能同时有 Wi-Fi、数据网络和热点地址，这里优先取私网段的那个。
     * 拿不到返回 null，界面上退化成占位文字，用户仍可靠自动发现连上。
     */
    private fun localIpv4(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() && !it.startsWith("127.") }
        candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: candidates.firstOrNull()
    } catch (e: Exception) {
        Log.d(TAG, "list local ip failed: ${e.message}")
        null
    }

    /**
     * 推流模式的帧处理：按目标帧率丢帧，通过的帧才转 Bitmap 与编码。
     * 跑在相机分析线程，必须自己关掉 ImageProxy。
     */
    private fun streamFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val minInterval = (1000L / settings.streamFps.coerceIn(1, 60)).coerceAtLeast(16L)
            // 节流放在像素转换之前，被丢的帧不做任何解码工作
            if (now - lastStreamFrameAt < minInterval) return
            lastStreamFrameAt = now
            val server = mjpegServer ?: return

            val bitmap = PoseLandmarkerEngine.toUprightBitmap(proxy)
            try {
                val jpeg = snapshotStore.encodeJpeg(bitmap, settings.streamJpegQuality.coerceIn(30, 95))
                server.publish(jpeg, bitmap.width, bitmap.height)
                streamFramesSent.incrementAndGet()
                lastFrameAt.set(now)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stream frame failed", e)
        } finally {
            proxy.close()
        }
    }

    /** GPU 委托创建失败时立刻回退 CPU；CPU 也失败才算致命。 */
    private fun createEngine(useGpu: Boolean, intervalMillis: Long): PoseLandmarkerEngine? {
        if (useGpu) {
            val gpu = PoseLandmarkerEngine(applicationContext, ::onPoseFrame, ::onEngineError, intervalMillis, useGpu = true)
            if (gpu.start()) return gpu
            gpuFallbackDone = true
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU")
            lifecycleScope.launch { recordInfo("GPU 加速不可用，已回退 CPU") }
        }
        val cpu = PoseLandmarkerEngine(applicationContext, ::onPoseFrame, ::onEngineError, intervalMillis, useGpu = false)
        return if (cpu.start()) cpu else null
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

    /**
     * 看门狗：驱动确认窗到期，并在相机断流时重新绑定。
     * 相机常开，任何一次断流都会让检测彻底失效，所以必须主动探活。
     */
    private suspend fun supervise() {
        var backoffMillis = 1_000L
        var lastStatusAt = 0L
        while (true) {
            delay(TICK_MILLIS)
            val now = System.currentTimeMillis()

            // 确认窗内长时间没有帧时，靠 tick 让窗到期（结果为 INVALID）
            tracker.tick(now).takeIf { it.isNotEmpty() }?.let { eventChannel.send(it) }

            val silentSince = maxOf(lastFrameAt.get(), boundAt.get())
            if (cameraBound.get() && silentSince > 0L && now - silentSince > NO_FRAME_TIMEOUT_MILLIS) {
                MonitorBus.update { it.copy(phase = MonitorPhase.CAMERA_LOST, cameraRebinds = it.cameraRebinds + 1) }
                recordEvent(EventType.CAMERA, "${NO_FRAME_TIMEOUT_MILLIS / 1000} 秒未收到相机帧，重新绑定（退避 ${backoffMillis / 1000} 秒）")
                unbindCamera()
                delay(backoffMillis)
                bindCameraWithRetry()
                backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            } else if (lastFrameAt.get() > boundAt.get()) {
                backoffMillis = 1_000L
            }

            if (now - lastStatusAt > STATUS_REFRESH_MILLIS) {
                notifier.updateStatus(statusText())
                lastStatusAt = now
            }
        }
    }

    /**
     * MediaPipe 回调线程。这里只做轻量工作：几何计算、状态机推进、投递任务。
     * JPEG 编码与磁盘/网络操作都交给别的线程，否则会直接拖慢推理帧率。
     */
    private fun onPoseFrame(frame: PoseFrame) {
        var bitmapHandedOff = false
        try {
            val now = System.currentTimeMillis()
            lastFrameAt.set(now)
            val result = PostureGeometry.analyze(
                frame.landmarks, frame.bitmap.width, frame.bitmap.height, settings.toGeometryConfig(),
            )
            val events = tracker.onFrame(result, now)

            // 模式切换后立刻改送帧间隔，巡检与确认的帧率差异全靠它
            if (events.any { it is TrackerEvent.ModeChanged }) {
                engine?.setMinIntervalMillis(tracker.desiredIntervalMillis)
            }

            // 只有进入确认窗的有效帧才值得存图
            val added = events.firstOrNull { it is TrackerEvent.FrameAdded } as? TrackerEvent.FrameAdded
            if (added != null && result is FrameResult.Valid && pendingEncodes.get() < MAX_PENDING_ENCODES) {
                bitmapHandedOff = enqueueEncode(added.windowSeq, added.index, result.measurement, frame)
            }

            val heavy = events.filter { it !is TrackerEvent.FrameAdded }
            if (heavy.isNotEmpty()) eventChannel.trySend(heavy)

            val neck = result.measurementOrNull?.neckInclinationDeg
            MonitorBus.update {
                it.copy(
                    phase = phaseOf(),
                    forwardHead = tracker.inForwardHead,
                    lastNeckDeg = neck,
                    lastFrameResult = frameResultText(result),
                    lastFrameAtMillis = now,
                    inferenceMillis = frame.inferenceMillis,
                    framesAnalyzed = it.framesAnalyzed + 1,
                    badStreak = tracker.badStreak,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "process frame failed", e)
        } finally {
            if (!bitmapHandedOff) frame.bitmap.recycle()
        }
    }

    /** 把这一帧的位图交给编码线程；返回 true 表示所有权已转移，调用方不要再回收。 */
    private fun enqueueEncode(seq: Long, index: Int, measurement: PostureMeasurement, frame: PoseFrame): Boolean {
        val bitmap = frame.bitmap
        pendingEncodes.incrementAndGet()
        return try {
            encodeExecutor.execute {
                try {
                    val jpeg = snapshotStore.encodeJpeg(bitmap)
                    synchronized(windowLock) {
                        val bucket = windowJpegs.getOrPut(seq) { HashMap() }
                        if (bucket.size < MAX_JPEGS_PER_WINDOW) {
                            bucket[index] = jpeg
                            windowMeasurements.getOrPut(seq) { HashMap() }[index] = measurement
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "encode snapshot failed", e)
                } finally {
                    bitmap.recycle()
                    pendingEncodes.decrementAndGet()
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            // 服务正在停止，编码线程已关闭：位图仍归调用方
            pendingEncodes.decrementAndGet()
            false
        }
    }

    /** 单协程顺序消费，保证事件日志与通知的先后关系与状态机一致。 */
    private suspend fun consumeEvents() {
        for (batch in eventChannel) {
            for (event in batch) {
                try {
                    handleTrackerEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "handle tracker event failed", e)
                }
            }
        }
    }

    private suspend fun handleTrackerEvent(event: TrackerEvent) {
        when (event) {
            is TrackerEvent.ModeChanged -> onModeChanged(event)
            is TrackerEvent.WindowEnded -> {
                // 切到编码线程再取缓存：单线程 FIFO 保证此前排队的编码都已写入桶
                val cache = withContext(encodeDispatcher) { takeWindowCache(event.windowSeq) }
                handleOutcome(event.outcome, System.currentTimeMillis(), cache.first, cache.second)
            }
            is TrackerEvent.Recovered -> onRecovered(event)
            is TrackerEvent.FrameAdded -> Unit
        }
    }

    private suspend fun onModeChanged(event: TrackerEvent.ModeChanged) {
        MonitorBus.update {
            it.copy(
                phase = phaseOf(),
                forwardHead = tracker.inForwardHead,
                triggers = it.triggers + if (event.to == TrackerMode.FAST) 1 else 0,
            )
        }
        when {
            event.to == TrackerMode.FAST -> {
                val why = if (event.reason == ModeChangeReason.RETRIGGERED) {
                    "冷却已过仍未恢复，再次确认"
                } else {
                    "连续 ${settings.triggerFrames} 帧超阈值，进入确认"
                }
                eventLog.append(
                    PostureEvent(
                        timestampMillis = System.currentTimeMillis(),
                        type = EventType.TRIGGER,
                        neckDeg = event.neckDeg,
                        thresholdDeg = tracker.thresholdDeg,
                        message = why,
                    ),
                )
            }
            event.reason == ModeChangeReason.TOO_MANY_INVALID ->
                recordInfo("确认窗连续无效，退回巡检")
        }
        notifier.updateStatus(statusText())
    }

    private suspend fun onRecovered(event: TrackerEvent.Recovered) {
        MonitorBus.update {
            it.copy(forwardHead = false, recoveries = it.recoveries + 1, phase = phaseOf(), badStreak = tracker.badStreak)
        }
        val seconds = event.forwardHeadMillis / 1000L
        val posture = PostureEvent(
            timestampMillis = System.currentTimeMillis(),
            type = EventType.RECOVERED,
            neckDeg = event.neckDeg,
            thresholdDeg = tracker.thresholdDeg,
            message = if (seconds > 0) "前倾持续 $seconds 秒后恢复" else "已恢复端正坐姿",
            forwardHeadMillis = event.forwardHeadMillis,
        )
        eventLog.append(posture)
        val target = settings.recoveredNotify
        if (target.toPhone) notifier.postRecovered(event.neckDeg, event.forwardHeadMillis)
        if (target.toPc) {
            try {
                pcSink?.deliver(posture, null)
            } catch (e: Exception) {
                Log.w(TAG, "deliver recovered to pc failed", e)
            }
        }
        notifier.updateStatus(statusText())
    }

    private suspend fun handleOutcome(
        outcome: WindowOutcome,
        now: Long,
        jpegs: Map<Int, ByteArray>,
        measurements: Map<Int, PostureMeasurement>,
    ) {
        val s = outcome.summary
        MonitorBus.update {
            it.copy(
                lastWindowAtMillis = now,
                lastSummary = s,
                badStreak = outcome.badStreak,
                thresholdDeg = s.thresholdDeg,
                baselineDeg = tracker.baselineDeg,
                forwardHead = tracker.inForwardHead,
                windowsRun = it.windowsRun + 1,
                invalidWindows = it.invalidWindows + if (s.verdict == WindowVerdict.INVALID) 1 else 0,
            )
        }
        val representativeJpeg = s.representativeFrameIndex?.let { jpegs[it] }

        // 每个确认窗都记一条 WINDOW 事件，供监测页「最近采样」展示；有效窗附代表帧截图
        val windowSnapshot = if (s.verdict != WindowVerdict.INVALID) {
            representativeJpeg?.let { snapshotStore.save(it, s.representative, s.medianNeckDeg, s.thresholdDeg, now, "win") }
        } else {
            null
        }
        eventLog.append(
            PostureEvent(
                timestampMillis = now,
                type = EventType.WINDOW,
                neckDeg = s.medianNeckDeg,
                torsoDeg = s.medianTorsoDeg,
                thresholdDeg = s.thresholdDeg,
                message = "有效帧 ${s.validFrames}/${s.totalFrames}" +
                    (if (s.misalignedFrames > 0) "，未对齐 ${s.misalignedFrames}" else "") +
                    (if (s.noTorsoFrames > 0) "，髋不可见 ${s.noTorsoFrames}" else ""),
                snapshotPaths = listOfNotNull(windowSnapshot?.absolutePath),
                verdict = s.verdict.name,
            ),
        )

        if (outcome.shouldRecord) {
            // 前倾事件：保存本窗内超阈值的多帧（最多 MAX_BAD_FRAMES），每帧标注各自角度
            val badFrameFiles = ArrayList<File>()
            for ((index, angle) in s.badFrames) {
                val jpeg = jpegs[index] ?: continue
                val file = snapshotStore.save(jpeg, measurements[index], angle, s.thresholdDeg, now, "bad", badFrameFiles.size)
                if (file != null) badFrameFiles.add(file)
            }
            // 万一没有超阈值帧缓存，退回代表帧
            if (badFrameFiles.isEmpty()) {
                windowSnapshot?.let(badFrameFiles::add)
            }
            val type = if (outcome.shouldNotify) EventType.ALERT else EventType.CONFIRMED
            val event = PostureEvent(
                timestampMillis = now,
                type = type,
                neckDeg = s.medianNeckDeg,
                torsoDeg = s.medianTorsoDeg,
                thresholdDeg = s.thresholdDeg,
                message = "连续 ${outcome.badStreak} 窗前倾，${badFrameFiles.size} 帧截图",
                snapshotPaths = badFrameFiles.map { it.absolutePath },
                verdict = s.verdict.name,
            )
            eventLog.append(event)
            // 通知与电脑上报都用中位数代表帧，多帧留在 App 内查看
            val primary = windowSnapshot ?: badFrameFiles.firstOrNull()
            if (outcome.shouldNotify) {
                // 过了冷却：手机弹通知 + 电脑告警响铃
                sink?.deliver(event, primary)
                MonitorBus.update { it.copy(alertsSent = it.alertsSent + 1, lastSnapshotPath = primary?.absolutePath) }
            } else {
                // 冷却期内的确认前倾：只发电脑（弹通知不响铃），手机侧保持安静，
                // 这样长时间低头能持续看到提示，又不会每个确认窗都被铃声打断。
                try {
                    pcSink?.deliver(event, primary)
                } catch (e: Exception) {
                    Log.w(TAG, "deliver confirmed to pc failed", e)
                }
                MonitorBus.update { it.copy(lastSnapshotPath = primary?.absolutePath) }
            }
        }
        notifier.updateStatus(statusText())
    }

    /** 取走该窗的缓存并顺手清掉更早的桶，避免异常路径下残留。 */
    private fun takeWindowCache(seq: Long): Pair<Map<Int, ByteArray>, Map<Int, PostureMeasurement>> =
        synchronized(windowLock) {
            val jpegs = windowJpegs.remove(seq) ?: emptyMap<Int, ByteArray>()
            val measurements = windowMeasurements.remove(seq) ?: emptyMap<Int, PostureMeasurement>()
            windowJpegs.keys.filter { it < seq }.toList().forEach { windowJpegs.remove(it) }
            windowMeasurements.keys.filter { it < seq }.toList().forEach { windowMeasurements.remove(it) }
            jpegs to measurements
        }

    /** 设置变化即时生效：阈值与节奏直接改，镜头/分辨率/委托需要重建。 */
    private suspend fun collectSettings() {
        settingsRepo.settings.drop(1).collect { updated ->
            val old = settings
            settings = updated
            if (streaming) {
                collectStreamSettings(old, updated)
                return@collect
            }
            tracker.updateConfig(updated.toAnalyzerConfig(), updated.toTrackerConfig())
            if (tracker.baselineDeg != updated.baselineDeg) tracker.setBaseline(updated.baselineDeg)
            engine?.setMinIntervalMillis(tracker.desiredIntervalMillis)
            rebuildSink()

            if (old.useGpu != updated.useGpu) restartEngine(updated.useGpu)
            if (old.cameraLens != updated.cameraLens || old.analysisResolution != updated.analysisResolution) {
                val provider = cameraProvider
                if (provider != null) {
                    lensResolution = withContext(Dispatchers.Main) {
                        CameraLensResolver.resolve(provider, updated.cameraLens)
                    }
                }
                bindCameraWithRetry()
            }
            MonitorBus.update {
                it.copy(
                    thresholdDeg = tracker.thresholdDeg,
                    baselineDeg = tracker.baselineDeg,
                    lens = lensResolution?.description,
                    delegate = engine?.delegateName,
                    analysisSize = updated.analysisResolution.label,
                )
            }
        }
    }

    /**
     * 推流模式下的设置热更新。
     * 帧率与 JPEG 质量下一帧就生效；镜头与分辨率要重绑相机；
     * 端口、密钥和检测模式改了必须重启服务才生效，这里只提示用户。
     */
    private suspend fun collectStreamSettings(old: Settings, updated: Settings) {
        if (old.cameraLens != updated.cameraLens || old.streamResolution != updated.streamResolution) {
            val provider = cameraProvider
            if (provider != null) {
                lensResolution = withContext(Dispatchers.Main) {
                    CameraLensResolver.resolve(provider, updated.cameraLens)
                }
            }
            bindCameraWithRetry()
        }
        val needsRestart = old.streamPort != updated.streamPort ||
            old.streamToken != updated.streamToken ||
            old.streamDiscoveryPort != updated.streamDiscoveryPort ||
            old.detectionMode != updated.detectionMode
        if (needsRestart) {
            recordInfo("端口、密钥或检测方式已改，停止后重新开始推流才会生效")
        }
        MonitorBus.update {
            it.copy(
                lens = lensResolution?.description,
                analysisSize = updated.streamResolution.label,
            )
        }
    }

    private suspend fun restartEngine(useGpu: Boolean) {
        val old = engine
        engine = null
        old?.stop()
        gpuFallbackDone = !useGpu
        val fresh = createEngine(useGpu, tracker.desiredIntervalMillis)
        if (fresh == null) {
            fail("姿态模型重建失败，已停止监测")
            return
        }
        engine = fresh
        recordInfo("推理委托切换为 ${fresh.delegateName}")
    }

    private fun onEngineError(e: Throwable) {
        Log.w(TAG, "engine error", e)
        val current = engine
        if (current != null && current.isGpu && !gpuFallbackDone) {
            // GPU 常常要到第一次推理才报错，这里做一次性回退
            gpuFallbackDone = true
            lifecycleScope.launch(Dispatchers.Default) {
                recordInfo("GPU 推理报错，回退 CPU: ${e.message}")
                restartEngine(false)
            }
            return
        }
        lifecycleScope.launch { recordError("推理错误: ${e.message}") }
    }

    private suspend fun bindCameraWithRetry() {
        cameraMutex.withLock {
            withContext(Dispatchers.Main) {
                val provider = cameraProvider ?: return@withContext
                val resolution = lensResolution ?: return@withContext
                try {
                    val analysis = CameraUseCases.buildAnalysis(settings.activeResolution, analysisExecutor) { proxy ->
                        if (streaming) {
                            streamFrame(proxy)
                        } else {
                            // 引擎重建期间可能为 null，务必关掉帧，否则相机队列会卡死
                            val current = engine
                            if (current == null) proxy.close() else current.submit(proxy)
                        }
                    }
                    provider.unbindAll()
                    val bound = provider.bindToLifecycle(this@MonitorService, resolution.selector, analysis)
                    camera = bound
                    // 变焦路径必须每次绑定后重设，重连相机会回到 1.0x
                    CameraLensResolver.afterBind(bound, resolution)
                    cameraBound.set(true)
                    boundAt.set(System.currentTimeMillis())
                    lastFrameAt.set(0L)
                    lastStreamFrameAt = 0L
                    MonitorBus.update { it.copy(phase = phaseOf()) }
                } catch (e: Exception) {
                    Log.e(TAG, "bind camera failed", e)
                    cameraBound.set(false)
                    recordEvent(EventType.CAMERA, "相机打开失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun unbindCamera() {
        cameraMutex.withLock {
            withContext(Dispatchers.Main) {
                try {
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                    Log.w(TAG, "unbind camera failed", e)
                }
                camera = null
                cameraBound.set(false)
            }
        }
    }

    private fun phaseOf(): MonitorPhase = when {
        !cameraBound.get() -> MonitorPhase.CAMERA_LOST
        streaming -> MonitorPhase.STREAMING
        tracker.mode == TrackerMode.FAST -> MonitorPhase.CONFIRMING
        else -> MonitorPhase.PATROL
    }

    private fun frameResultText(result: FrameResult): String = when (result) {
        is FrameResult.Valid -> "已对齐"
        is FrameResult.Misaligned -> "未对齐"
        is FrameResult.NoTorso -> "髋部不可见"
        FrameResult.LowVisibility -> "看不清耳肩"
        FrameResult.NoPerson -> "画面中无人"
    }

    private suspend fun recordError(message: String) = recordEvent(EventType.ERROR, message)

    private suspend fun recordInfo(message: String) {
        Log.i(TAG, message)
        eventLog.append(PostureEvent(System.currentTimeMillis(), EventType.INFO, message = message))
    }

    private suspend fun recordEvent(type: EventType, message: String) {
        Log.w(TAG, message)
        MonitorBus.update { it.copy(lastError = message) }
        eventLog.append(PostureEvent(System.currentTimeMillis(), type, message = message))
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
        val mode = when (st.phase) {
            MonitorPhase.CONFIRMING -> "确认中"
            MonitorPhase.CAMERA_LOST -> "相机重连中"
            else -> if (st.forwardHead) "前倾中" else "巡检中"
        }
        val last = st.lastSummary
        val lastText = when {
            last == null -> "尚未确认过"
            last.verdict == WindowVerdict.INVALID -> "上次确认无效(有效帧 ${last.validFrames})"
            else -> "上次 ${fmt(last.medianNeckDeg)}° / 阈值 ${fmt(last.thresholdDeg)}°"
        }
        return "$mode，$lastText，提醒 ${st.alertsSent} 次"
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
        eventChannel.close()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraBound.set(false)
        camera = null
        // 先停引擎再关编码线程：停掉回调后不会再有新的编码任务被提交
        engine?.stop()
        engine = null
        // 推流相关：先停发现服务再停推流，避免电脑刚发现就连了个正在关的端口
        discoveryResponder?.stop()
        discoveryResponder = null
        mjpegServer?.stop()
        mjpegServer = null
        streaming = false
        streamFramesSent.set(0L)
        synchronized(windowLock) {
            windowJpegs.clear()
            windowMeasurements.clear()
        }
        MonitorBus.update {
            it.copy(
                running = false,
                forwardHead = false,
                streaming = false,
                streamClients = 0,
                streamFps = 0f,
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
        encodeExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_START = "com.local.neckguard.action.START"
        const val ACTION_START_STREAM = "com.local.neckguard.action.START_STREAM"
        const val ACTION_STOP = "com.local.neckguard.action.STOP"
        private const val MAX_JPEGS_PER_WINDOW = 40
        private const val MAX_PENDING_ENCODES = 8
        private const val TICK_MILLIS = 250L
        private const val NO_FRAME_TIMEOUT_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L
        private const val STATUS_REFRESH_MILLIS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        /** 无线相机模式：只推流，检测交给电脑。 */
        fun startStream(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_START_STREAM }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun fmt(v: Float?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
    }
}
