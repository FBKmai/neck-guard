package com.local.neckguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** 打开全屏预览的请求：哪条事件、从第几张开始。 */
private data class PreviewRequest(val event: PostureEvent, val startIndex: Int)

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
    LaunchedEffect(monitor.windowsRun, monitor.alertsSent, monitor.lastError, monitor.triggers, monitor.recoveries, monitor.cameraRebinds) {
        events = eventLog.readLatest(200)
    }

    var preview by remember { mutableStateOf<PreviewRequest?>(null) }
    var showAllOthers by remember { mutableStateOf(false) }

    val windowEvents = remember(events) { events.filter { it.type == EventType.WINDOW }.take(RECENT_WINDOW_COUNT) }
    val alertEvents = remember(events) {
        events.filter { it.type == EventType.ALERT || it.type == EventType.CONFIRMED }
    }
    val otherEvents = remember(events) {
        events.filter {
            it.type == EventType.ERROR || it.type == EventType.INFO ||
                it.type == EventType.TRIGGER || it.type == EventType.RECOVERED || it.type == EventType.CAMERA
        }
    }
    val shownOthers = if (showAllOthers) otherEvents else otherEvents.take(OTHER_COLLAPSED_COUNT)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(monitor, now) }
        if (monitor.streaming) {
            item { StreamCard(monitor, context) }
        } else {
            item { LastSampleCard(monitor) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (monitor.running) {
                    OutlinedButton(onClick = { MonitorService.stop(context) }, modifier = Modifier.weight(1f)) {
                        Text(if (monitor.streaming) "停止推流" else "停止监测")
                    }
                } else {
                    Button(onClick = { MonitorService.start(context) }, modifier = Modifier.weight(1f)) {
                        Text("开始监测")
                    }
                }
            }
        }

        // 推流模式下手机不做判定，没有确认窗与前倾事件可展示，这两段整体跳过
        if (!monitor.streaming) {
            // 最近采样：横向卡片
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最近确认窗", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        scope.launch {
                            eventLog.clear()
                            events = emptyList()
                        }
                    }) { Text("清空事件") }
                }
            }
            item {
                if (windowEvents.isEmpty()) {
                    Text("暂无确认窗记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(
                            windowEvents,
                            key = { index, it -> "w_" + it.timestampMillis.toString() + "_" + index },
                        ) { _, event ->
                            WindowCard(event) { preview = PreviewRequest(event, 0) }
                        }
                    }
                }
            }

            // 前倾事件：每条带多帧缩略图
            item { Text("前倾事件", style = MaterialTheme.typography.titleMedium) }
            if (alertEvents.isEmpty()) {
                item { Text("暂无前倾事件", style = MaterialTheme.typography.bodyMedium) }
            } else {
                itemsIndexed(
                    alertEvents,
                    key = { index, it -> "a_" + it.timestampMillis.toString() + "_" + index },
                ) { _, event ->
                    AlertEventRow(event) { startIndex -> preview = PreviewRequest(event, startIndex) }
                    HorizontalDivider()
                }
            }
        }

        // 其他事件：错误与信息，默认折叠
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("其他事件", style = MaterialTheme.typography.titleMedium)
                if (otherEvents.size > OTHER_COLLAPSED_COUNT) {
                    TextButton(onClick = { showAllOthers = !showAllOthers }) {
                        Text(if (showAllOthers) "收起" else "展开全部 (${otherEvents.size})")
                    }
                }
            }
        }
        if (shownOthers.isEmpty()) {
            item { Text("暂无", style = MaterialTheme.typography.bodyMedium) }
        } else {
            itemsIndexed(
                shownOthers,
                key = { index, it -> "o_" + it.timestampMillis.toString() + "_" + index },
            ) { _, event ->
                OtherEventRow(event)
            }
        }
    }

    preview?.takeIf { it.event.snapshotPaths.isNotEmpty() }?.let { req ->
        SnapshotPreviewDialog(event = req.event, startIndex = req.startIndex, onDismiss = { preview = null })
    }
}

@Composable
private fun StatusCard(monitor: MonitorState, now: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val running = if (monitor.streaming) "推流中" else "监测中"
            Text(
                text = if (monitor.running) "$running · ${phaseText(monitor.phase)}" else "未运行 · ${phaseText(monitor.phase)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (monitor.phase == MonitorPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            monitor.startedAtMillis?.let { started ->
                Text("启动于 ${TIME_FMT.format(Date(started))}，已运行 ${durationText(now - started)}")
            }
            if (monitor.running && monitor.forwardHead) {
                Text(
                    "前倾中，等待恢复",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            // 推流模式下手机不做判定，角度、阈值与窗计数都没有意义
            if (!monitor.streaming) {
                if (monitor.running) {
                    Text(
                        buildString {
                            append("当前 ").append(fmtDeg(monitor.lastNeckDeg))
                            monitor.lastFrameResult?.let { append("  ").append(it) }
                            if (monitor.inferenceMillis > 0) append("  ").append(monitor.inferenceMillis).append(" ms")
                            append("  已分析 ").append(monitor.framesAnalyzed).append(" 帧")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    buildString {
                        append("阈值 ")
                        append(fmtDeg(monitor.thresholdDeg))
                        append("    基线 ")
                        append(monitor.baselineDeg?.let { fmtDeg(it) } ?: "未校准")
                    },
                )
                Text(
                    "确认窗 ${monitor.windowsRun} 次，无效 ${monitor.invalidWindows} 次，触发 ${monitor.triggers} 次，" +
                        "恢复 ${monitor.recoveries} 次，提醒 ${monitor.alertsSent} 次，连续前倾 ${monitor.badStreak} 窗",
                )
            }
            if (monitor.lens != null || monitor.delegate != null) {
                Text(
                    buildString {
                        monitor.lens?.let { append(it) }
                        monitor.delegate?.let { append("  ·  ").append(it) }
                        monitor.analysisSize?.let { append("  ·  ").append(it) }
                        if (monitor.cameraRebinds > 0) append("  ·  重连 ").append(monitor.cameraRebinds).append(" 次")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            monitor.lastError?.let {
                Text("最近异常：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 无线相机模式的状态卡：推流地址、客户端数、实际帧率。 */
@Composable
private fun StreamCard(monitor: MonitorState, context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("无线相机", style = MaterialTheme.typography.titleSmall)
            Text(
                if (monitor.streamClients > 0) {
                    "已有 ${monitor.streamClients} 台电脑在拉流"
                } else {
                    "等待电脑连接。在电脑上运行 pc/neck_camera_monitor.py 即可自动找到这台手机"
                },
                color = if (monitor.streamClients > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            monitor.streamUrl?.let { url ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("NeckGuard 推流地址", url))
                        Toast.makeText(context, "已复制推流地址", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                }
            }
            Text(
                String.format(
                    Locale.US,
                    "%.1f fps    已推 %d 帧    %.1f MB",
                    monitor.streamFps,
                    monitor.streamFramesSent,
                    monitor.streamBytesSent / 1024f / 1024f,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!monitor.streamDiscoveryOn) {
                Text(
                    "自动发现未启用（UDP 端口被占用），电脑端请用 --url 手填上面的地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LastSampleCard(monitor: MonitorState) {
    val s = monitor.lastSummary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("上次确认窗", style = MaterialTheme.typography.titleSmall)
            if (s == null) {
                Text("尚未进入确认")
                return@Column
            }
            monitor.lastWindowAtMillis?.let { Text("时间 ${TIME_FMT.format(Date(it))}") }
            val verdictColor = when (s.verdict) {
                WindowVerdict.BAD -> MaterialTheme.colorScheme.error
                WindowVerdict.GOOD -> MaterialTheme.colorScheme.primary
                WindowVerdict.INVALID -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("判定：${verdictText(s.verdict)}", color = verdictColor, style = MaterialTheme.typography.titleMedium)
            Text("前倾 ${fmtDeg(s.medianNeckDeg)}    躯干 ${fmtDeg(s.medianTorsoDeg)}    阈值 ${fmtDeg(s.thresholdDeg)}")
            Text(
                "帧数 ${s.totalFrames}，有效 ${s.validFrames}，未对齐 ${s.misalignedFrames}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 最近采样的横向卡片：缩略图 + 时间 + 角度 + 判定。 */
@Composable
private fun WindowCard(event: PostureEvent, onOpen: () -> Unit) {
    val verdict = event.verdict ?: VERDICT_INVALID
    val color = verdictColor(verdict)
    val path = event.snapshotPath
    Card(modifier = Modifier.width(132.dp)) {
        Column {
            Thumbnail(
                path = path,
                placeholder = if (verdict == VERDICT_INVALID) "无效" else "无图",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clickable(enabled = path != null, onClick = onOpen),
            )
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(TIME_FMT.format(Date(event.timestampMillis)), style = MaterialTheme.typography.labelSmall)
                Text(fmtDeg(event.neckDeg), style = MaterialTheme.typography.titleSmall, color = color)
                Text(verdictLabel(verdict), style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/** 前倾事件行：文字信息 + 该事件全部截图的横向缩略图。 */
@Composable
private fun AlertEventRow(event: PostureEvent, onOpen: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val typeColor = when (event.type) {
            EventType.ALERT -> MaterialTheme.colorScheme.error
            EventType.CONFIRMED -> MaterialTheme.colorScheme.tertiary
            EventType.ERROR, EventType.CAMERA -> MaterialTheme.colorScheme.error
            EventType.TRIGGER -> MaterialTheme.colorScheme.tertiary
            EventType.RECOVERED -> MaterialTheme.colorScheme.primary
            EventType.WINDOW, EventType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(TIME_FMT.format(Date(event.timestampMillis)), style = MaterialTheme.typography.labelMedium)
            Text(eventTypeText(event.type), color = typeColor, style = MaterialTheme.typography.labelLarge)
        }
        if (event.neckDeg != null || event.thresholdDeg != null) {
            Text(
                buildString {
                    append("前倾 ").append(fmtDeg(event.neckDeg))
                    event.torsoDeg?.let { append("  躯干 ").append(fmtDeg(it)) }
                    event.thresholdDeg?.let { append("  阈值 ").append(fmtDeg(it)) }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        event.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (event.snapshotPaths.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(event.snapshotPaths, key = { index, p -> p + "_" + index }) { index, p ->
                    Thumbnail(
                        path = p,
                        placeholder = "无图",
                        modifier = Modifier
                            .size(72.dp)
                            .clickable { onOpen(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OtherEventRow(event: PostureEvent) {
    val color = when (event.type) {
        EventType.ERROR, EventType.CAMERA -> MaterialTheme.colorScheme.error
        EventType.RECOVERED -> MaterialTheme.colorScheme.primary
        EventType.TRIGGER -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(TIME_FMT.format(Date(event.timestampMillis)), style = MaterialTheme.typography.labelMedium)
        Text(eventTypeText(event.type), color = color, style = MaterialTheme.typography.labelMedium)
        Text(
            event.message?.takeIf { it.isNotBlank() } ?: "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 缩略图：解码失败或文件缺失时显示灰色占位。 */
@Composable
private fun Thumbnail(path: String?, placeholder: String, modifier: Modifier) {
    val bitmap = remember(path) { path?.let { decodeFile(it, sampleSize = 4) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF4A4A4A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(placeholder, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 全屏预览：左右滑动同一事件的多张截图。 */
@Composable
private fun SnapshotPreviewDialog(event: PostureEvent, startIndex: Int, onDismiss: () -> Unit) {
    val paths = event.snapshotPaths
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, paths.lastIndex),
        pageCount = { paths.size },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                val path = paths[page]
                val bitmap = remember(path) { decodeFile(path, sampleSize = 1) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("图片不存在或已被清理", color = Color.White)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("第 ${pagerState.currentPage + 1} / ${paths.size} 张", color = Color.White)
                Text(
                    buildString {
                        append(TIME_FMT.format(Date(event.timestampMillis)))
                        append("  ").append(eventTypeText(event.type))
                        append("  前倾 ").append(fmtDeg(event.neckDeg))
                        append("  阈值 ").append(fmtDeg(event.thresholdDeg))
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
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
    MonitorPhase.PATROL -> "巡检中"
    MonitorPhase.CONFIRMING -> "确认中"
    MonitorPhase.CAMERA_LOST -> "相机丢失，重连中"
    MonitorPhase.STREAMING -> "画面推送中"
    MonitorPhase.ERROR -> "异常"
}

private fun verdictText(v: WindowVerdict): String = when (v) {
    WindowVerdict.GOOD -> "正常"
    WindowVerdict.BAD -> "前倾"
    WindowVerdict.INVALID -> "无效"
}

private fun verdictLabel(v: String): String = when (v) {
    VERDICT_GOOD -> "正常"
    VERDICT_BAD -> "前倾"
    else -> "无效"
}

private fun verdictColor(v: String): Color = when (v) {
    VERDICT_GOOD -> Color(0xFF43A047)
    VERDICT_BAD -> Color(0xFFE53935)
    else -> Color(0xFF9E9E9E)
}

private fun eventTypeText(t: EventType): String = when (t) {
    EventType.ALERT -> "已提醒"
    EventType.CONFIRMED -> "已确认(冷却中)"
    EventType.WINDOW -> "确认窗"
    EventType.TRIGGER -> "进入确认"
    EventType.RECOVERED -> "已恢复"
    EventType.CAMERA -> "相机"
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

private const val RECENT_WINDOW_COUNT = 12
private const val OTHER_COLLAPSED_COUNT = 5
private const val VERDICT_GOOD = "GOOD"
private const val VERDICT_BAD = "BAD"
private const val VERDICT_INVALID = "INVALID"

private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
