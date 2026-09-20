package com.local.neckguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.local.neckguard.data.EventLog
import com.local.neckguard.data.SettingsRepository
import com.local.neckguard.ui.MonitorScreen
import com.local.neckguard.ui.SettingsScreen
import com.local.neckguard.ui.SetupScreen

enum class Screen(val label: String) {
    SETUP("摆放/校准"),
    MONITOR("监测"),
    SETTINGS("设置"),
}

class MainActivity : ComponentActivity() {

    private var requestedScreen by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val settingsRepo = SettingsRepository(applicationContext)
        val eventLog = EventLog(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Root(settingsRepo, eventLog, requestedScreen) { requestedScreen = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_SCREEN)) {
            EXTRA_SCREEN_MONITOR -> requestedScreen = Screen.MONITOR
            EXTRA_SCREEN_SETUP -> requestedScreen = Screen.SETUP
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_SCREEN_MONITOR = "monitor"
        const val EXTRA_SCREEN_SETUP = "setup"
    }
}

@Composable
private fun Root(
    settingsRepo: SettingsRepository,
    eventLog: EventLog,
    requestedScreen: Screen?,
    onRequestConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.SETUP) }
    if (requestedScreen != null) {
        screen = requestedScreen
        onRequestConsumed()
    }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        if (Build.VERSION.SDK_INT >= 33) {
            notificationGranted = result[Manifest.permission.POST_NOTIFICATIONS] ?: notificationGranted
        }
    }

    if (!cameraGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要相机权限才能检测姿态", style = MaterialTheme.typography.titleMedium)
            Text(
                "相机画面只在本机分析，不会上传。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }) { Text("授予权限") }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = {},
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (screen) {
            Screen.SETUP -> SetupScreen(
                settingsRepo = settingsRepo,
                notificationGranted = notificationGranted,
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                },
                onMonitoringStarted = { screen = Screen.MONITOR },
                modifier = modifier,
            )
            Screen.MONITOR -> MonitorScreen(eventLog = eventLog, modifier = modifier)
            Screen.SETTINGS -> SettingsScreen(settingsRepo = settingsRepo, eventLog = eventLog, modifier = modifier)
        }
    }
}
