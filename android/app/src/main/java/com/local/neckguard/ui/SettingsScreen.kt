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
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.Settings
import com.local.neckguard.data.SettingsRepository
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    eventLog: EventLog,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState(initial = Settings())
    var message by remember { mutableStateOf<String?>(null) }

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
        Text("采样调度", style = MaterialTheme.typography.titleMedium)
        IntField(
            label = "采样间隔（秒）",
            hint = "两次采样之间的基础等待时间，默认 45，最小 5",
            value = settings.intervalSec,
            validate = { it >= 5 },
            onSave = { v -> save { it.copy(intervalSec = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "随机抖动（秒）",
            hint = "在间隔上叠加正负随机量，实现不定时采样，默认 15，0 表示固定间隔",
            value = settings.jitterSec,
            validate = { it >= 0 },
            onSave = { v -> save { it.copy(jitterSec = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "采样窗时长（秒）",
            hint = "每次打开相机连续分析的时间，默认 3，范围 1 到 15",
            value = settings.windowSec,
            validate = { it in 1..15 },
            onSave = { v -> save { it.copy(windowSec = v) } },
            onInvalid = { message = it },
        )
        IntField(
            label = "有效帧下限（帧）",
            hint = "一个采样窗内有效帧少于此值视为无效窗，默认 8",
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
            hint = "连续多少个采样窗判为前倾才提醒，默认 2",
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
        Text("相机与调试", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            label = "使用前置摄像头",
            hint = "关闭则使用后置摄像头，默认开启",
            checked = settings.useFrontCamera,
            onChange = { v -> save { it.copy(useFrontCamera = v) } },
        )
        SwitchRow(
            label = "每个采样窗都保存截图",
            hint = "调试用，会显著增加存储占用与耗电，默认关闭",
            checked = settings.saveEveryWindowSnapshot,
            onChange = { v -> save { it.copy(saveEveryWindowSnapshot = v) } },
        )

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save { Settings(baselineDeg = it.baselineDeg) } }, modifier = Modifier.weight(1f)) {
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
