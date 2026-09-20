package com.local.neckguard.monitor

import com.local.neckguard.pose.WindowSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class MonitorPhase { IDLE, STARTING, WAITING, SAMPLING, ERROR }

data class MonitorState(
    val running: Boolean = false,
    val phase: MonitorPhase = MonitorPhase.IDLE,
    val startedAtMillis: Long? = null,
    val nextSampleAtMillis: Long? = null,
    val lastSampleAtMillis: Long? = null,
    val lastSummary: WindowSummary? = null,
    val badStreak: Int = 0,
    val thresholdDeg: Float? = null,
    val baselineDeg: Float? = null,
    val windowsRun: Int = 0,
    val invalidWindows: Int = 0,
    val alertsSent: Int = 0,
    val lastError: String? = null,
    /** 最近一次通知附带的快照路径，UI 直接显示。 */
    val lastSnapshotPath: String? = null,
)

/** 服务与 UI 之间的进程内状态总线（同进程，无需 AIDL）。 */
object MonitorBus {
    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state

    fun update(transform: (MonitorState) -> MonitorState) = _state.update(transform)

    fun reset() = _state.update { MonitorState() }
}
