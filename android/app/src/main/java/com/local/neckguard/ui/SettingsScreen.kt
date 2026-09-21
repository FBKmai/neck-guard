package com.local.neckguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.EventType
import com.local.neckguard.data.PostureEvent
import com.local.neckguard.monitor.SnapshotStore
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
        Text("连续检测", style = MaterialTheme.typography.titleMedium)
        Text(
            "相机常开：平时低帧率巡检，发现疑似前倾立即切到高帧率确认，连续多个确认窗判为前倾才提醒。",
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
            label = "触发帧数",
            hint = "巡检中连续多少帧超过阈值就进入确认，默认 2",
            value = settings.triggerFrames,
            validate = { it in 1..10 },
            onSave = { v -> save { it.copy(triggerFrames = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "恢复帧数",
            hint = "前倾后连续多少帧低于阈值减迟滞才算恢复端正，默认 5",
            value = settings.recoverFrames,
            validate = { it in 1..30 },
            onSave = { v -> save { it.copy(recoverFrames = v) } },
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
        IntField(
            label = "有效帧下限（帧）",
            hint = "一个确认窗内有效帧少于此值视为无效窗，默认 8",
            value = settings.minValidFrames,
            validate = { it >= 1 },
            onSave = { v -> save { it.copy(minValidFrames = v) } },
            onInvalid = { message = it },
        )

        HorizontalDivider()
        Text("判定阈值", style = MaterialTheme.typography.titleMedium)
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
            hint = "未校准时颈部倾角超过此值判为前倾，默认 40",
            value = settings.absoluteThresholdDeg,
            validate = { it in 10f..80f },
            onSave = { v -> save { it.copy(absoluteThresholdDeg = v) } },
            onInvalid = { message = it },
        )
        FloatField(
            label = "校准增量（度）",
            hint = "校准后阈值 = 基线 + 增量，再限制在 30 到 50 度之间，默认 12",
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
            hint = "接收脚本启动时打印的地址，例如 http://192.168.1.23:8765",
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val base = settings.pcBaseUrl
                    if (base == null) {
                        message = "请先填写电脑地址"
                    } else {
                        message = "正在连接 $base"
                        scope.launch {
                            message = when (val r = PcSink(base, settings.pcToken, "settings-test").ping()) {
                                is PcSink.Result.Ok -> "连接成功: ${r.body.take(80)}"
                                is PcSink.Result.Failed -> "连接失败: ${r.message}"
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("测试连接") }
            OutlinedButton(
                onClick = {
                    val base = settings.pcBaseUrl
                    if (base == null) {
                        message = "请先填写电脑地址"
                    } else {
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
                            val snapshot = SnapshotStore(context).listFiles().firstOrNull()
                            message = when (val r = PcSink(base, settings.pcToken, "settings-test").sendTest(event, snapshot)) {
                                is PcSink.Result.Ok -> "发送成功，电脑应已弹出通知"
                                is PcSink.Result.Failed -> "发送失败: ${r.message}"
                            }
                        }
                    }
                },
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
                onClick = { save { Settings(baselineDeg = it.baselineDeg, cameraLens = it.cameraLens) } },
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
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
