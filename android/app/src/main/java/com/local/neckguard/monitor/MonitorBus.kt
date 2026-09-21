package com.local.neckguard.monitor

import com.local.neckguard.pose.WindowSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class MonitorPhase {
    IDLE,
    STARTING,
    /** 相机常开、低帧率巡检。 */
    PATROL,
    /** 高帧率确认窗。 */
    CONFIRMING,
    /** 超时没有收到相机帧，正在重连。 */
    CAMERA_LOST,

    /** 无线相机模式：只推流，不在手机上做检测。 */
    STREAMING,
    ERROR,
}

data class MonitorState(
    val running: Boolean = false,
    val phase: MonitorPhase = MonitorPhase.IDLE,
    val startedAtMillis: Long? = null,
    /** 已确认前倾且尚未恢复。 */
    val forwardHead: Boolean = false,
    // 实时帧信息
    val lastNeckDeg: Float? = null,
    val lastFrameResult: String? = null,
    val lastFrameAtMillis: Long? = null,
    val inferenceMillis: Long = 0L,
    val framesAnalyzed: Long = 0L,
    // 确认窗
    val lastWindowAtMillis: Long? = null,
    val lastSummary: WindowSummary? = null,
    val badStreak: Int = 0,
    val thresholdDeg: Float? = null,
    val baselineDeg: Float? = null,
    // 统计
    val windowsRun: Int = 0,
    val invalidWindows: Int = 0,
    val alertsSent: Int = 0,
    val triggers: Int = 0,
    val recoveries: Int = 0,
    val cameraRebinds: Int = 0,
    // 运行环境
    val lens: String? = null,
    val delegate: String? = null,
    val analysisSize: String? = null,
    val lastError: String? = null,
    /** 最近一次通知附带的快照路径，UI 直接显示。 */
    val lastSnapshotPath: String? = null,
    // 无线相机模式
    /** 本次运行是推流而非本机检测。 */
    val streaming: Boolean = false,
    /** 电脑端可直接访问的推流地址，例如 http://192.168.1.20:8767/video。 */
    val streamUrl: String? = null,
    /** 当前连着的拉流客户端数。 */
    val streamClients: Int = 0,
    /** 实际推出去的帧率。 */
    val streamFps: Float = 0f,
    val streamFramesSent: Long = 0L,
    val streamBytesSent: Long = 0L,
    /** 自动发现是否已开启，关掉时电脑端要手填地址。 */
    val streamDiscoveryOn: Boolean = false,
)

/** 服务与 UI 之间的进程内状态总线（同进程，无需 AIDL）。 */
object MonitorBus {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state

    fun update(transform: (MonitorState) -> MonitorState) = _state.update(transform)

    fun reset() = _state.update { MonitorState() }
}
