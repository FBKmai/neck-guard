package com.local.neckguard.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.camera.lifecycle.ProcessCameraProvider
import com.local.neckguard.camera.AnalysisResolution
import com.local.neckguard.camera.CameraLens
import com.local.neckguard.camera.CameraLensResolver
import com.local.neckguard.data.DetectionMode
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.monitor.SnapshotStore
import com.local.neckguard.report.PcDiscovery
import com.local.neckguard.report.PcSink
import com.local.neckguard.data.RecoveredNotify
import com.local.neckguard.data.Settings
import com.local.neckguard.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    eventLog: EventLog,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState(initial = Settings())
    var message by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<List<String>?>(null) }
    // 扫描与两个测试按钮共用一个忙标记，避免连点并发导致结果互相覆盖
    var pcBusy by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<PcDiscovery.Receiver>?>(null) }

    fun save(transform: (Settings) -> Settings) {
        scope.launch {
            try {
                settingsRepo.update(transform)
                message = "已保存"
            } catch (e: Exception) {
                message = "保存失败: ${e.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("检测方式", style = MaterialTheme.typography.titleMedium)
        Text(
            "「手机检测」是手机本机推理并提醒；「电脑检测」下手机只当无线相机把画面推给电脑，" +
                "由电脑用更大的模型判定并弹通知，手机侧不提醒也不存事件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            label = "在哪里检测",
            hint = "切换后需要重新点「开始」才生效",
            options = DetectionMode.entries,
            selected = settings.detectionMode,
            optionLabel = { it.label },
            onSelect = { v -> save { it.copy(detectionMode = v) } },
        )

        if (settings.detectionMode == DetectionMode.PC_STREAM) {
            HorizontalDivider()
            Text("无线相机（推流给电脑）", style = MaterialTheme.typography.titleMedium)
            Text(
                "在电脑上运行 pc/neck_camera_monitor.py，它会自动扫描到这台手机并开始拉流。" +
                    "也可以用浏览器打开手机的推流地址先确认画面。改端口或密钥后要重新开始推流才生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IntField(
                label = "推流端口",
                hint = "电脑访问 http://手机IP:端口/video，默认 8767",
                value = settings.streamPort,
                validate = { it in 1024..65535 },
                onSave = { v -> save { it.copy(streamPort = v) } },
                onInvalid = { message = it },
            )
            IntField(
                label = "推流帧率",
                hint = "每秒推多少帧，默认 10。电脑性能够就可以开高，但会更费手机电量和带宽",
                value = settings.streamFps,
                validate = { it in 1..30 },
                onSave = { v -> save { it.copy(streamFps = v) } },
                onInvalid = { message = it },
            )
            ChoiceRow(
                label = "推流分辨率",
                hint = "电脑端检测用的画面尺寸，越大越准也越占带宽",
                options = AnalysisResolution.entries,
                selected = settings.streamResolution,
                optionLabel = { it.label },
                onSelect = { v -> save { it.copy(streamResolution = v) } },
            )
            IntField(
                label = "JPEG 质量",
                hint = "30 到 95，默认 70。越高越清晰，占带宽也越多",
                value = settings.streamJpegQuality,
                validate = { it in 30..95 },
                onSave = { v -> save { it.copy(streamJpegQuality = v) } },
                onInvalid = { message = it },
            )
            TextField(
                label = "推流密钥",
                hint = "留空则同一 Wi-Fi 下任何设备都能看到画面；填了电脑端要用同样的 --token",
                stored = settings.streamToken,
                keyboardType = KeyboardType.Text,
                onSave = { v -> save { it.copy(streamToken = v.trim()) } },
            )
            IntField(
                label = "自动发现端口（UDP）",
                hint = "电脑扫描手机用，默认 8768，与电脑端 --discovery-port 一致",
                value = settings.streamDiscoveryPort,
                validate = { it in 1024..65535 },
                onSave = { v -> save { it.copy(streamDiscoveryPort = v) } },
                onInvalid = { message = it },
            )
        }

        HorizontalDivider()
        Text("连续检测", style = MaterialTheme.typography.titleMedium)
        Text(
            "相机常开：平时低帧率巡检，发现疑似前倾立即切到高帧率确认，连续多个确认窗判为前倾才提醒。" +
                if (settings.detectionMode == DetectionMode.PC_STREAM) "（电脑检测模式下这组参数由电脑端命令行控制）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntField(
            label = "巡检间隔（毫秒）",
            hint = "平时每隔多久分析一帧，默认 700（约 1.4 fps），越小越灵敏也越费电",
            value = settings.slowIntervalMillis,
            validate = { it in 200..5000 },
            onSave = { v -> save { it.copy(slowIntervalMillis = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "确认间隔（毫秒）",
            hint = "确认模式下每隔多久分析一帧，默认 125（约 8 fps）",
            value = settings.fastIntervalMillis,
            validate = { it in 60..1000 },
            onSave = { v -> save { it.copy(fastIntervalMillis = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "确认窗时长（秒）",
            hint = "每个确认窗持续多久，默认 3，范围 1 到 15",
            value = settings.confirmWindowSec,
            validate = { it in 1..15 },
            onSave = { v -> save { it.copy(confirmWindowSec = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "触发时长（毫秒）",
            hint = "巡检中超过阈值持续多久就进入确认，默认 1400",
            value = settings.triggerMillis,
            validate = { it in 100..30_000 },
            onSave = { v -> save { it.copy(triggerMillis = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "恢复时长（毫秒）",
            hint = "前倾后低于阈值减迟滞持续多久才算恢复端正，默认 3500",
            value = settings.recoverMillis,
            validate = { it in 100..60_000 },
            onSave = { v -> save { it.copy(recoverMillis = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "看不清容忍（毫秒）",
            hint = "计时途中短暂看不清（摸脸、手挡住）多久之内不清零，默认 1000",
            value = settings.invalidGraceMillis,
            validate = { it in 0..10_000 },
            onSave = { v -> save { it.copy(invalidGraceMillis = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "重新确认间隔（秒）",
            hint = "确认前倾后至少隔多久才再次进入确认，默认 30",
            value = settings.retriggerHoldSec,
            validate = { it in 0..600 },
            onSave = { v -> save { it.copy(retriggerHoldSec = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "无效窗上限",
            hint = "确认模式下连续多少个无效窗（看不到人）就退回巡检，默认 2",
            value = settings.maxInvalidWindows,
            validate = { it in 1..10 },
            onSave = { v -> save { it.copy(maxInvalidWindows = v) } },
            onInvalid = { message = it },
        )
        FloatField(
            label = "有效帧占比下限",
            hint = "确认窗内有效帧少于「按帧率推算的期望帧数 × 此值」视为无效窗，默认 0.33",
            value = settings.minValidRatio,
            validate = { it in 0.05f..1f },
            onSave = { v -> save { it.copy(minValidRatio = v) } },
            onInvalid = { message = it },
        )

        HorizontalDivider()
        Text("判定阈值", style = MaterialTheme.typography.titleMedium)
        Text(
            "前倾角 = 耳肩连线与髋肩连线（躯干线）的夹角。头与身体成一条直线时为 0°，" +
                "所以躺着或身体整体前倾都不会误报，只有头相对躯干往前伸才算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前基线")
                Text(
                    settings.baselineDeg?.let { String.format(Locale.US, "%.1f°（校准后阈值 = 基线 + 增量）", it) }
                        ?: "未校准，使用绝对阈值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { save { it.copy(baselineDeg = null) } },
                enabled = settings.baselineDeg != null,
            ) { Text("清除校准") }
        }
        FloatField(
            label = "绝对阈值（度）",
            hint = "未校准时前倾角超过此值判为前倾，默认 35",
            value = settings.absoluteThresholdDeg,
            validate = { it in 10f..80f },
            onSave = { v -> save { it.copy(absoluteThresholdDeg = v) } },
            onInvalid = { message = it },
        )
        FloatField(
            label = "校准增量（度）",
            hint = "校准后阈值 = 基线 + 增量，再限制在 20 到 50 度之间，默认 12",
            value = settings.calibrationDeltaDeg,
            validate = { it in 0f..40f },
            onSave = { v -> save { it.copy(calibrationDeltaDeg = v) } },
            onInvalid = { message = it },
        )
        FloatField(
            label = "迟滞（度）",
            hint = "进入前倾需超过阈值，退出需低于阈值减去此值，默认 4",
            value = settings.hysteresisDeg,
            validate = { it in 0f..20f },
            onSave = { v -> save { it.copy(hysteresisDeg = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "连续前倾窗数",
            hint = "连续多少个确认窗判为前倾才提醒，默认 2",
            value = settings.consecutiveBadWindows,
            validate = { it >= 1 },
            onSave = { v -> save { it.copy(consecutiveBadWindows = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "提醒冷却（分钟）",
            hint = "两次提醒之间的最小间隔，期间仍记录事件，默认 10",
            value = settings.cooldownMin,
            validate = { it >= 0 },
            onSave = { v -> save { it.copy(cooldownMin = v) } },
            onInvalid = { message = it },
        )
        FloatField(
            label = "侧面对齐容差",
            hint = "左右肩水平距离 / 躯干长度，小于此值视为正侧面，默认 0.35",
            value = settings.maxShoulderOffsetRatio,
            validate = { it in 0.05f..1f },
            onSave = { v -> save { it.copy(maxShoulderOffsetRatio = v) } },
            onInvalid = { message = it },
        )
        SwitchRow(
            label = "必须看到髋部",
            hint = "开启（默认）时髋不可见的帧不参与判定；关闭则退回「与竖直线的夹角」继续判定，" +
                "适合髋部长期被桌子挡住，但躺卧时会误报",
            checked = settings.requireHip,
            onChange = { v -> save { it.copy(requireHip = v) } },
        )
        IntField(
            label = "躯干方向保留（毫秒）",
            hint = "髋被手或桌子短暂挡住时，沿用上次躯干方向多久。躯干方向变化很慢，" +
                "这段时间内角度口径不变，比退回竖直参考稳。0 关闭，默认 2000",
            value = settings.torsoHoldMillis,
            validate = { it in 0..10_000 },
            onSave = { v -> save { it.copy(torsoHoldMillis = v) } },
            onInvalid = { message = it },
        )

        HorizontalDivider()
        Text("电脑接收端（局域网）", style = MaterialTheme.typography.titleMedium)
        Text(
            "在电脑上运行 pc/neck_receiver.py，手机检测到前倾后把事件和截图发到电脑，电脑弹通知并响铃。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            label = "上报到电脑",
            hint = "开启后每次提醒都会同步发送到下面的地址",
            checked = settings.pcEnabled,
            onChange = { v -> save { it.copy(pcEnabled = v) } },
        )
        TextField(
            label = "电脑地址",
            hint = "点下面「扫描电脑」自动填；手填格式为 http://192.168.1.23:8765",
            stored = settings.pcEndpoint,
            keyboardType = KeyboardType.Uri,
            onSave = { v -> save { it.copy(pcEndpoint = v.trim()) } },
        )
        TextField(
            label = "共享密钥",
            hint = "与接收脚本 --token 参数一致；两边都留空则不校验",
            stored = settings.pcToken,
            keyboardType = KeyboardType.Password,
            onSave = { v -> save { it.copy(pcToken = v.trim()) } },
        )
        OutlinedButton(
            onClick = {
                if (pcBusy) return@OutlinedButton
                pcBusy = true
                message = "正在扫描局域网内的电脑"
                scope.launch {
                    val found = PcDiscovery.discover()
                    pcBusy = false
                    if (found.isEmpty()) {
                        message = "没有扫描到电脑：确认接收端已启动、手机和电脑连同一个 Wi-Fi，" +
                            "若路由器开启了 AP 隔离则需手填地址"
                        toast(context, "没有扫描到电脑")
                    } else {
                        discovered = found
                    }
                }
            },
            enabled = !pcBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (pcBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (pcBusy) "扫描中" else "扫描电脑")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val base = settings.pcBaseUrl
                    if (base == null) {
                        message = "请先填写电脑地址，或点「扫描电脑」自动填"
                    } else {
                        pcBusy = true
                        message = "正在连接 $base"
                        scope.launch {
                            val r = PcSink(base, settings.pcToken, "settings-test").ping()
                            pcBusy = false
                            when (r) {
                                is PcSink.Result.Ok -> {
                                    message = "连接成功（$base）"
                                    toast(context, "连接成功")
                                }
                                is PcSink.Result.Failed -> {
                                    message = "连接失败（$base）：" + PcSink.humanize(r.message)
                                    toast(context, "连接失败")
                                }
                            }
                        }
                    }
                },
                enabled = !pcBusy,
                modifier = Modifier.weight(1f),
            ) { Text("测试连接") }
            OutlinedButton(
                onClick = {
                    val base = settings.pcBaseUrl
                    if (base == null) {
                        message = "请先填写电脑地址，或点「扫描电脑」自动填"
                    } else {
                        pcBusy = true
                        message = "正在发送测试事件"
                        scope.launch {
                            val event = PostureEvent(
                                timestampMillis = System.currentTimeMillis(),
                                type = EventType.INFO,
                                neckDeg = 47.3f,
                                torsoDeg = 6.1f,
                                thresholdDeg = 40f,
                                message = "手机端测试事件",
                            )
                            // 扫描快照目录是磁盘 IO，别放在主线程上
                            val snapshot = withContext(Dispatchers.IO) {
                                SnapshotStore(context).listFiles().firstOrNull()
                            }
                            val r = PcSink(base, settings.pcToken, "settings-test").sendTest(event, snapshot)
                            pcBusy = false
                            when (r) {
                                is PcSink.Result.Ok -> {
                                    message = "发送成功，电脑应已弹出通知" +
                                        if (snapshot == null) "（本地还没有截图，这次只发了事件）" else ""
                                    toast(context, "发送成功")
                                }
                                is PcSink.Result.Failed -> {
                                    message = "发送失败（$base）：" + PcSink.humanize(r.message)
                                    toast(context, "发送失败")
                                }
                            }
                        }
                    }
                },
                enabled = !pcBusy,
                modifier = Modifier.weight(1f),
            ) { Text("发送测试事件") }
        }

        HorizontalDivider()
        Text("恢复提醒", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            label = "坐正后提醒到",
            hint = "前倾后重新坐正时的提示，手机端为静音通知，电脑端需开启上报",
            options = RecoveredNotify.entries.toList(),
            selected = settings.recoveredNotify,
            optionLabel = { it.label },
            onSelect = { v -> save { it.copy(recoveredNotify = v) } },
        )

        HorizontalDivider()
        Text("相机与性能", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            label = "镜头",
            hint = "超广角适合近距离拍到完整上半身；部分机型不向第三方开放，会自动退回普通后摄",
            options = CameraLens.entries.toList(),
            selected = settings.cameraLens,
            optionLabel = { it.label },
            onSelect = { v -> save { it.copy(cameraLens = v) } },
        )
        ChoiceRow(
            label = "分析分辨率",
            hint = "越高截图越清晰，转换与编码开销也越大，默认 640x480",
            options = AnalysisResolution.entries.toList(),
            selected = settings.analysisResolution,
            optionLabel = { it.label },
            onSelect = { v -> save { it.copy(analysisResolution = v) } },
        )
        SwitchRow(
            label = "GPU 加速",
            hint = "部分机型 GPU 委托不可用，失败会自动回退 CPU，默认关闭",
            checked = settings.useGpu,
            onChange = { v -> save { it.copy(useGpu = v) } },
        )
        OutlinedButton(
            onClick = {
                message = "正在读取相机信息"
                scope.launch {
                    diagnostics = withContext(Dispatchers.IO) {
                        try {
                            val provider = ProcessCameraProvider.getInstance(context).get()
                            CameraLensResolver.diagnostics(context, provider).map { it.toLine() }
                        } catch (e: Exception) {
                            listOf("读取失败: " + (e.message ?: e::class.java.simpleName))
                        }
                    }
                    message = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("相机信息") }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                // 基线、镜头、检测方式与两处电脑配置都是用户一次性设好的，
                // 只恢复调参项，不该被"恢复默认"一把清掉
                onClick = {
                    save {
                        Settings(
                            baselineDeg = it.baselineDeg,
                            cameraLens = it.cameraLens,
                            pcEndpoint = it.pcEndpoint,
                            pcToken = it.pcToken,
                            pcEnabled = it.pcEnabled,
                            detectionMode = it.detectionMode,
                            streamPort = it.streamPort,
                            streamDiscoveryPort = it.streamDiscoveryPort,
                            streamToken = it.streamToken,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("恢复默认")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        eventLog.clear()
                        message = "事件日志已清空"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("清空事件日志") }
        }
        message?.let {
            val failed = it.contains("失败") || it.contains("没有扫描到") || it.contains("请先填写")
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    discovered?.let { list ->
        AlertDialog(
            onDismissRequest = { discovered = null },
            confirmButton = { TextButton(onClick = { discovered = null }) { Text("取消") } },
            title = { Text("扫描到 ${list.size} 台电脑") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "点一项即可填入地址。地址由电脑那侧按手机所在网段算出，不会填到虚拟网卡上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    list.forEach { r ->
                        OutlinedButton(
                            onClick = {
                                save { it.copy(pcEndpoint = r.baseUrl) }
                                discovered = null
                                message = if (r.tokenRequired && settings.pcToken.isBlank()) {
                                    "已填入 ${r.baseUrl}，该电脑启用了密钥，请在「共享密钥」里填上再测试"
                                } else {
                                    "已填入 ${r.baseUrl}，可以点「测试连接」了"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(r.name)
                                Text(
                                    r.baseUrl + if (r.tokenRequired) "（需要密钥）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    diagnostics?.let { lines ->
        AlertDialog(
            onDismissRequest = { diagnostics = null },
            confirmButton = { TextButton(onClick = { diagnostics = null }) { Text("关闭") } },
            title = { Text("相机信息") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "标注为系统隐藏的镜头无法被第三方应用打开。若后置镜头的 zoom 下限小于 1，超广角可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lines.isEmpty()) {
                        Text("未读取到任何相机")
                    } else {
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
        )
    }
}

/** 即时反馈，避免失败时页面上只剩一行不显眼的小字。 */
private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 一排单选芯片，用于镜头、分辨率这类少量互斥选项。 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    hint: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { if (option != selected) onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun IntField(
    label: String,
    hint: String,
    value: Int,
    validate: (Int) -> Boolean,
    onSave: (Int) -> Unit,
    onInvalid: (String) -> Unit,
) {
    NumberField(
        label = label,
        hint = hint,
        stored = value.toString(),
        parse = { text -> text.trim().toIntOrNull()?.takeIf(validate) },
        onSave = onSave,
        onInvalid = onInvalid,
    )
}

@Composable
private fun FloatField(
    label: String,
    hint: String,
    value: Float,
    validate: (Float) -> Boolean,
    onSave: (Float) -> Unit,
    onInvalid: (String) -> Unit,
) {
    NumberField(
        label = label,
        hint = hint,
        stored = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.'),
        parse = { text -> text.trim().toFloatOrNull()?.takeIf(validate) },
        onSave = onSave,
        onInvalid = onInvalid,
    )
}

/**
 * 通用数字输入：失焦或点「保存」时解析；解析失败回退到已存值并提示。
 * stored 变化（例如恢复默认）时同步刷新输入框。
 */
@Composable
private fun <T : Any> NumberField(
    label: String,
    hint: String,
    stored: String,
    parse: (String) -> T?,
    onSave: (T) -> Unit,
    onInvalid: (String) -> Unit,
) {
    var text by rememberSaveable(stored) { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!focused) text = stored }

    fun commit() {
        if (text == stored) return
        val parsed = parse(text)
        if (parsed == null) {
            onInvalid("「$label」输入无效，已恢复为 $stored")
            text = stored
        } else {
            onSave(parsed)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state ->
                        val wasFocused = focused
                        focused = state.isFocused
                        if (wasFocused && !state.isFocused) commit()
                    },
            )
            TextButton(onClick = { commit() }, enabled = text != stored) { Text("保存") }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

/** 通用文本输入：失焦或点「保存」时提交，stored 变化时同步刷新。 */
@Composable
private fun TextField(
    label: String,
    hint: String,
    stored: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(stored) { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!focused) text = stored }

    fun commit() {
        if (text != stored) onSave(text)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state ->
                        val wasFocused = focused
                        focused = state.isFocused
                        if (wasFocused && !state.isFocused) commit()
                    },
            )
            TextButton(onClick = { commit() }, enabled = text != stored) { Text("保存") }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
