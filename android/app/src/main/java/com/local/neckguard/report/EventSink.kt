package com.local.neckguard.report

import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.monitor.AlertNotifier
import java.io.File

/**
 * 事件出口。v1 只有本机通知与局域网电脑；v2 增加 ServerSink（上报到服务器再推送到主手机）。
 * 实现必须自己吞掉异常，不得让调用方（MonitorService）崩溃。
 */
interface EventSink {
    suspend fun deliver(event: PostureEvent, snapshot: File?)
}

/** 本机通知：按事件类型分派为前倾提醒或恢复提示。 */
class LocalNotificationSink(private val notifier: AlertNotifier) : EventSink {
    override suspend fun deliver(event: PostureEvent, snapshot: File?) {
        when (event.type) {
            EventType.RECOVERED -> notifier.postRecovered(event.neckDeg, event.forwardHeadMillis ?: 0L)
            else -> notifier.postAlert(event.neckDeg, event.thresholdDeg, snapshot)
        }
    }
}

/** 顺序调用多个出口，任一失败不影响其他。 */
class CompositeSink(private val sinks: List<EventSink>) : EventSink {
    override suspend fun deliver(event: PostureEvent, snapshot: File?) {
        for (sink in sinks) {
            try {
                sink.deliver(event, snapshot)
            } catch (e: Exception) {
                android.util.Log.w("EventSink", "sink ${sink::class.simpleName} failed", e)
            }
        }
    }
}
