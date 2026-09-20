package com.local.neckguard.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.monitor.MonitorBus
import com.local.neckguard.monitor.MonitorPhase
import com.local.neckguard.monitor.MonitorService
import com.local.neckguard.monitor.MonitorState
import com.local.neckguard.pose.WindowVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorScreen(eventLog: EventLog, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitor by MonitorBus.state.collectAsStateWithLifecycle()

    // 每秒刷新一次，用于倒计时与运行时长
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    var events by remember { mutableStateOf<List<PostureEvent>>(emptyList()) }
    LaunchedEffect(monitor.windowsRun, monitor.alertsSent, monitor.lastError) {
        events = eventLog.readLatest(50)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(monitor, now) }
        item { LastSampleCard(monitor) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (monitor.running) {
                    OutlinedButton(onClick = { MonitorService.stop(context) }, modifier = Modifier.weight(1f)) {
                        Text("停止监测")
                    }
                } else {
                    Button(onClick = { MonitorService.start(context) }, modifier = Modifier.weight(1f)) {
                        Text("开始监测")
                    }
                }
            }
        }
        monitor.lastSnapshotPath?.let { path ->
            item { SnapshotCard(path) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近事件", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    scope.launch {
                        eventLog.clear()
                        events = emptyList()
                    }
                }) { Text("清空事件") }
            }
        }
        if (events.isEmpty()) {
            item { Text("暂无事件", style = MaterialTheme.typography.bodyMedium) }
        } else {
            itemsIndexed(events, key = { index, it -> it.timestampMillis.toString() + "_" + index }) { _, event ->
                EventRow(event)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatusCard(monitor: MonitorState, now: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (monitor.running) "监测中 · ${phaseText(monitor.phase)}" else "未运行 · ${phaseText(monitor.phase)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (monitor.phase == MonitorPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            monitor.startedAtMillis?.let { started ->
                Text("启动于 ${TIME_FMT.format(Date(started))}，已运行 ${durationText(now - started)}")
            }
            if (monitor.running) {
                val next = monitor.nextSampleAtMillis
                val text = when {
                    monitor.phase == MonitorPhase.SAMPLING -> "正在采样"
                    next == null -> "等待调度"
                    else -> {
                        val remain = ((next - now) / 1000L).coerceAtLeast(0L)
                        "下次采样：${remain} 秒后"
                    }
                }
                Text(text)
            }
            Text(
                buildString {
                    append("阈值 ")
                    append(fmtDeg(monitor.thresholdDeg))
                    append("    基线 ")
                    append(monitor.baselineDeg?.let { fmtDeg(it) } ?: "未校准")
                },
            )
            Text("采样 ${monitor.windowsRun} 次，无效 ${monitor.invalidWindows} 次，提醒 ${monitor.alertsSent} 次，连续前倾 ${monitor.badStreak} 窗")
            monitor.lastError?.let {
                Text("最近异常：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LastSampleCard(monitor: MonitorState) {
    val s = monitor.lastSummary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("上次采样", style = MaterialTheme.typography.titleSmall)
            if (s == null) {
                Text("尚未采样")
                return@Column
            }
            monitor.lastSampleAtMillis?.let { Text("时间 ${TIME_FMT.format(Date(it))}") }
            val verdictColor = when (s.verdict) {
                WindowVerdict.BAD -> MaterialTheme.colorScheme.error
                WindowVerdict.GOOD -> MaterialTheme.colorScheme.primary
                WindowVerdict.INVALID -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("判定：${verdictText(s.verdict)}", color = verdictColor, style = MaterialTheme.typography.titleMedium)
            Text("颈部 ${fmtDeg(s.medianNeckDeg)}    躯干 ${fmtDeg(s.medianTorsoDeg)}    阈值 ${fmtDeg(s.thresholdDeg)}")
            Text(
                "帧数 ${s.totalFrames}，有效 ${s.validFrames}，未对齐 ${s.misalignedFrames}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SnapshotCard(path: String) {
    val bitmap = remember(path) { decodeFile(path, sampleSize = 2) }
    if (bitmap == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近一次提醒截图", style = MaterialTheme.typography.titleSmall)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "最近一次提醒截图",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun EventRow(event: PostureEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = event.snapshotPath?.let { path -> remember(path) { decodeFile(path, sampleSize = 4) } }
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val typeColor = when (event.type) {
                EventType.ALERT -> MaterialTheme.colorScheme.error
                EventType.CONFIRMED -> MaterialTheme.colorScheme.tertiary
                EventType.ERROR -> MaterialTheme.colorScheme.error
                EventType.WINDOW, EventType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(TIME_FMT.format(Date(event.timestampMillis)), style = MaterialTheme.typography.labelMedium)
                Text(eventTypeText(event.type), color = typeColor, style = MaterialTheme.typography.labelLarge)
            }
            if (event.neckDeg != null || event.thresholdDeg != null) {
                Text(
                    buildString {
                        append("颈部 ").append(fmtDeg(event.neckDeg))
                        event.torsoDeg?.let { append("  躯干 ").append(fmtDeg(it)) }
                        event.thresholdDeg?.let { append("  阈值 ").append(fmtDeg(it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            event.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun decodeFile(path: String, sampleSize: Int): Bitmap? {
    return try {
        val file = File(path)
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, opts)
    } catch (_: Throwable) {
        null
    }
}

private fun phaseText(phase: MonitorPhase): String = when (phase) {
    MonitorPhase.IDLE -> "空闲"
    MonitorPhase.STARTING -> "启动中"
    MonitorPhase.WAITING -> "等待下次采样"
    MonitorPhase.SAMPLING -> "采样中"
    MonitorPhase.ERROR -> "异常"
}

private fun verdictText(v: WindowVerdict): String = when (v) {
    WindowVerdict.GOOD -> "正常"
    WindowVerdict.BAD -> "前倾"
    WindowVerdict.INVALID -> "无效"
}

private fun eventTypeText(t: EventType): String = when (t) {
    EventType.ALERT -> "已提醒"
    EventType.CONFIRMED -> "确认前倾(冷却中)"
    EventType.WINDOW -> "采样"
    EventType.ERROR -> "错误"
    EventType.INFO -> "信息"
}

private fun fmtDeg(v: Float?): String = v?.let { String.format(Locale.US, "%.1f°", it) } ?: "--"

private fun durationText(millis: Long): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d时%02d分%02d秒", h, m, s) else String.format(Locale.US, "%d分%02d秒", m, s)
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
