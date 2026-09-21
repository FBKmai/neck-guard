package com.local.neckguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.neckguard.data.SettingsRepository
import com.local.neckguard.monitor.MonitorBus
import com.local.neckguard.monitor.MonitorService
import com.local.neckguard.pose.FrameResult
import java.util.Locale

@Composable
fun SetupScreen(
    settingsRepo: SettingsRepository,
    notificationGranted: Boolean,
    onRequestNotification: () -> Unit,
    onMonitoringStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val monitor by MonitorBus.state.collectAsStateWithLifecycle()

    if (monitor.running) {
        // 服务占用相机时不开预览，避免两边争抢
        Column(
            modifier = modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("监测进行中，预览已关闭以释放相机。", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("如需重新摆放或校准，请先在「监测」页停止。")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onMonitoringStarted) { Text("前往监测页") }
        }
        return
    }

    val controller = remember { LivePreviewController(context.applicationContext, scope, settingsRepo) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val live by controller.state.collectAsState()

    DisposableEffect(lifecycleOwner) {
        controller.start(lifecycleOwner, previewView)
        onDispose { controller.stop() }
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            PoseOverlay(
                result = live.result,
                imageWidth = live.imageWidth,
                imageHeight = live.imageHeight,
                mirrored = live.mirrored,
                modifier = Modifier.fillMaxSize(),
            )
            StatusBadge(live, modifier = Modifier.padding(12.dp))
        }

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            live.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("摆放要点", style = MaterialTheme.typography.titleSmall)
                    Text("1. 手机竖放，摄像头在身体正侧面，距离 1 到 1.5 米，高度与肩齐平。")
                    Text("2. 画面里要同时看到耳朵、肩膀和髋部。前倾角以髋肩连线为基准，看不到髋部就不判定。")
                    Text("3. 上方状态显示「已对齐」后，端正坐好，点「校准」。")
                }
            }

            if (live.calibrating) {
                LinearProgressIndicator(progress = { live.calibrationProgress }, modifier = Modifier.fillMaxWidth())
                Text("采样中：${live.calibrationSamples} 帧")
            }
            live.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text(
                buildString {
                    append("基线：")
                    append(live.baselineDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "未校准")
                    append("    报警阈值：")
                    append(String.format(Locale.US, "%.1f°", live.thresholdDeg))
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.startCalibration() }, enabled = live.ready && !live.calibrating) { Text("校准") }
                OutlinedButton(onClick = { controller.clearBaseline() }, enabled = live.baselineDeg != null) { Text("清除校准") }
                TextButton(onClick = { controller.switchCamera(lifecycleOwner, previewView) }, enabled = live.ready) {
                    Text("镜头：" + live.lens.label)
                }
            }

            if (!notificationGranted) {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("尚未授予通知权限，前倾提醒无法显示。", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRequestNotification) { Text("去授权") }
                    }
                }
            }

            Button(
                onClick = {
                    controller.stop()
                    MonitorService.start(context)
                    onMonitoringStarted()
                },
                enabled = live.ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始监测") }
        }
    }
}

@Composable
private fun StatusBadge(live: LivePreviewController.LiveState, modifier: Modifier = Modifier) {
    val (text, color) = when (val r = live.result) {
        null -> "等待画面" to Color.Gray
        is FrameResult.NoPerson -> "未检测到人" to Color(0xFFFF7043)
        is FrameResult.LowVisibility -> "耳朵或肩膀不可见" to Color(0xFFFF7043)
        is FrameResult.Misaligned -> String.format(
            Locale.US, "未对齐(肩偏 %.2f) 颈 %.0f°", r.measurement.shoulderOffsetRatio, r.measurement.neckInclinationDeg,
        ) to Color(0xFFFFA000)
        is FrameResult.NoTorso -> String.format(
            Locale.US, "髋部不可见，暂不判定 颈 %.0f°(竖直)", r.measurement.neckInclinationDeg,
        ) to Color(0xFFFFA000)
        is FrameResult.Valid -> {
            val m = r.measurement
            val bad = m.neckInclinationDeg > live.thresholdDeg
            String.format(Locale.US, "已对齐 颈 %.0f° %s", m.neckInclinationDeg, if (bad) "前倾" else "正常") to
                if (bad) Color(0xFFE53935) else Color(0xFF43A047)
        }
    }
    Column(modifier = modifier) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier.background(color.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            buildString {
                append(String.format(Locale.US, "%.1f fps  %d ms", live.fps, live.inferenceMillis))
                live.delegate?.let { append("  ").append(it) }
                live.analysisSize?.let { append("  ").append(it) }
            },
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        live.lensDescription?.let { desc ->
            Text(
                desc,
                color = Color.White,
                modifier = Modifier.padding(top = 2.dp).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
