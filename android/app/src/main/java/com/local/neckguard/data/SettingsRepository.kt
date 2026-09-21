package com.local.neckguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.neckguard.camera.AnalysisResolution
import com.local.neckguard.camera.CameraLens
import com.local.neckguard.pose.AnalyzerConfig
import com.local.neckguard.pose.GeometryConfig
import com.local.neckguard.pose.TrackerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "neckguard_settings")

/** 恢复端正后提醒到哪里。 */
enum class RecoveredNotify(val label: String) {
    NONE("不提醒"),
    PHONE("手机"),
    PC("电脑"),
    BOTH("手机+电脑"),
    ;

    val toPhone: Boolean get() = this == PHONE || this == BOTH
    val toPc: Boolean get() = this == PC || this == BOTH

    companion object {
        fun parse(name: String?): RecoveredNotify? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/** 用户可调参数。时间单位在字段名里标明。 */
data class Settings(
    val cameraLens: CameraLens = CameraLens.FRONT,
    val analysisResolution: AnalysisResolution = AnalysisResolution.R640x480,
    /** GPU 委托，失败时服务会自动回退 CPU。 */
    val useGpu: Boolean = false,
    /** 巡检模式送帧间隔，越小越灵敏也越费电。 */
    val slowIntervalMillis: Int = 700,
    /** 确认模式送帧间隔。 */
    val fastIntervalMillis: Int = 125,
    /** 每个确认窗的时长。 */
    val confirmWindowSec: Int = 3,
    /** 巡检下连续多少帧超阈值才进入确认。 */
    val triggerFrames: Int = 2,
    /** 前倾中连续多少帧低于退出线才算恢复。 */
    val recoverFrames: Int = 5,
    /** 确认后至少隔多久才允许再次确认。 */
    val retriggerHoldSec: Int = 30,
    /** 确认模式内连续多少个无效窗退回巡检。 */
    val maxInvalidWindows: Int = 2,
    val absoluteThresholdDeg: Float = 40f,
    val calibrationDeltaDeg: Float = 12f,
    val hysteresisDeg: Float = 4f,
    val minValidFrames: Int = 8,
    val consecutiveBadWindows: Int = 2,
    val cooldownMin: Int = 10,
    val maxShoulderOffsetRatio: Float = 0.35f,
    /** null 表示未校准。 */
    val baselineDeg: Float? = null,
    val recoveredNotify: RecoveredNotify = RecoveredNotify.PHONE,
    /** 局域网电脑接收端地址，例如 http://192.168.1.10:8765，空表示关闭。 */
    val pcEndpoint: String = "",
    /** 可选口令，随请求头 X-Neck-Token 发送。 */
    val pcToken: String = "",
    /** 是否把事件上报到电脑。 */
    val pcEnabled: Boolean = false,
) {
    /** 规范化后的电脑端根地址（去掉末尾斜杠、补 http://），未配置返回 null。 */
    val pcBaseUrl: String?
        get() {
            val raw = pcEndpoint.trim().trimEnd('/')
            if (raw.isEmpty()) return null
            return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        }

    fun toAnalyzerConfig(): AnalyzerConfig = AnalyzerConfig(
        absoluteThresholdDeg = absoluteThresholdDeg,
        calibrationDeltaDeg = calibrationDeltaDeg,
        hysteresisDeg = hysteresisDeg,
        minValidFramesPerWindow = minValidFrames,
        consecutiveBadWindows = consecutiveBadWindows,
        cooldownMillis = cooldownMin.toLong() * 60_000L,
    )

    fun toTrackerConfig(): TrackerConfig = TrackerConfig(
        slowIntervalMillis = slowIntervalMillis.toLong(),
        fastIntervalMillis = fastIntervalMillis.toLong(),
        confirmWindowMillis = confirmWindowSec.toLong() * 1000L,
        triggerFrames = triggerFrames,
        recoverFrames = recoverFrames,
        retriggerHoldMillis = retriggerHoldSec.toLong() * 1000L,
        maxInvalidWindows = maxInvalidWindows,
    ).sanitized()

    fun toGeometryConfig(): GeometryConfig = GeometryConfig(maxShoulderOffsetRatio = maxShoulderOffsetRatio)
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CAMERA_LENS = stringPreferencesKey("camera_lens")
        val ANALYSIS_RESOLUTION = stringPreferencesKey("analysis_resolution")
        val USE_GPU = booleanPreferencesKey("use_gpu")
        val SLOW_INTERVAL_MS = intPreferencesKey("slow_interval_ms")
        val FAST_INTERVAL_MS = intPreferencesKey("fast_interval_ms")
        // 沿用旧 key，v0.2 已设过的确认窗时长可直接继承
        val CONFIRM_WINDOW_SEC = intPreferencesKey("window_sec")
        val TRIGGER_FRAMES = intPreferencesKey("trigger_frames")
        val RECOVER_FRAMES = intPreferencesKey("recover_frames")
        val RETRIGGER_HOLD_SEC = intPreferencesKey("retrigger_hold_sec")
        val MAX_INVALID_WINDOWS = intPreferencesKey("max_invalid_windows")
        val ABSOLUTE_THRESHOLD = floatPreferencesKey("absolute_threshold_deg")
        val CALIBRATION_DELTA = floatPreferencesKey("calibration_delta_deg")
        val HYSTERESIS = floatPreferencesKey("hysteresis_deg")
        val MIN_VALID_FRAMES = intPreferencesKey("min_valid_frames")
        val CONSECUTIVE_BAD = intPreferencesKey("consecutive_bad_windows")
        val COOLDOWN_MIN = intPreferencesKey("cooldown_min")
        val MAX_SHOULDER_OFFSET = floatPreferencesKey("max_shoulder_offset_ratio")
        val BASELINE = floatPreferencesKey("baseline_deg")
        val RECOVERED_NOTIFY = stringPreferencesKey("recovered_notify")
        val PC_ENDPOINT = stringPreferencesKey("pc_endpoint")
        val PC_TOKEN = stringPreferencesKey("pc_token")
        val PC_ENABLED = booleanPreferencesKey("pc_enabled")

        // v0.2 遗留 key，只读用于迁移，写入时清除
        val LEGACY_USE_FRONT_CAMERA = booleanPreferencesKey("use_front_camera")
        val LEGACY_INTERVAL_SEC = intPreferencesKey("interval_sec")
        val LEGACY_JITTER_SEC = intPreferencesKey("jitter_sec")
        val LEGACY_SAVE_EVERY_WINDOW = booleanPreferencesKey("save_every_window_snapshot")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { readFrom(it) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsStore.edit { p ->
            val new = transform(readFrom(p))
            p[Keys.CAMERA_LENS] = new.cameraLens.name
            p[Keys.ANALYSIS_RESOLUTION] = new.analysisResolution.name
            p[Keys.USE_GPU] = new.useGpu
            p[Keys.SLOW_INTERVAL_MS] = new.slowIntervalMillis
            p[Keys.FAST_INTERVAL_MS] = new.fastIntervalMillis
            p[Keys.CONFIRM_WINDOW_SEC] = new.confirmWindowSec
            p[Keys.TRIGGER_FRAMES] = new.triggerFrames
            p[Keys.RECOVER_FRAMES] = new.recoverFrames
            p[Keys.RETRIGGER_HOLD_SEC] = new.retriggerHoldSec
            p[Keys.MAX_INVALID_WINDOWS] = new.maxInvalidWindows
            p[Keys.ABSOLUTE_THRESHOLD] = new.absoluteThresholdDeg
            p[Keys.CALIBRATION_DELTA] = new.calibrationDeltaDeg
            p[Keys.HYSTERESIS] = new.hysteresisDeg
            p[Keys.MIN_VALID_FRAMES] = new.minValidFrames
            p[Keys.CONSECUTIVE_BAD] = new.consecutiveBadWindows
            p[Keys.COOLDOWN_MIN] = new.cooldownMin
            p[Keys.MAX_SHOULDER_OFFSET] = new.maxShoulderOffsetRatio
            p[Keys.RECOVERED_NOTIFY] = new.recoveredNotify.name
            p[Keys.PC_ENDPOINT] = new.pcEndpoint
            p[Keys.PC_TOKEN] = new.pcToken
            p[Keys.PC_ENABLED] = new.pcEnabled
            val baseline = new.baselineDeg
            if (baseline == null) p.remove(Keys.BASELINE) else p[Keys.BASELINE] = baseline
            // 第一次写入即清掉 v0.2 的键，避免以后再走迁移分支
            p.remove(Keys.LEGACY_USE_FRONT_CAMERA)
            p.remove(Keys.LEGACY_INTERVAL_SEC)
            p.remove(Keys.LEGACY_JITTER_SEC)
            p.remove(Keys.LEGACY_SAVE_EVERY_WINDOW)
        }
    }

    suspend fun setBaseline(deg: Float?) = update { it.copy(baselineDeg = deg) }

    /** 读取与迁移集中在这一处，避免 Flow 与 edit 两条路径出现分歧。 */
    private fun readFrom(p: Preferences): Settings {
        val d = Settings()
        val lens = CameraLens.parse(p[Keys.CAMERA_LENS])
            ?: p[Keys.LEGACY_USE_FRONT_CAMERA]?.let { if (it) CameraLens.FRONT else CameraLens.BACK }
            ?: d.cameraLens
        return Settings(
            cameraLens = lens,
            analysisResolution = AnalysisResolution.parse(p[Keys.ANALYSIS_RESOLUTION]) ?: d.analysisResolution,
            useGpu = p[Keys.USE_GPU] ?: d.useGpu,
            slowIntervalMillis = p[Keys.SLOW_INTERVAL_MS] ?: d.slowIntervalMillis,
            fastIntervalMillis = p[Keys.FAST_INTERVAL_MS] ?: d.fastIntervalMillis,
            confirmWindowSec = p[Keys.CONFIRM_WINDOW_SEC] ?: d.confirmWindowSec,
            triggerFrames = p[Keys.TRIGGER_FRAMES] ?: d.triggerFrames,
            recoverFrames = p[Keys.RECOVER_FRAMES] ?: d.recoverFrames,
            retriggerHoldSec = p[Keys.RETRIGGER_HOLD_SEC] ?: d.retriggerHoldSec,
            maxInvalidWindows = p[Keys.MAX_INVALID_WINDOWS] ?: d.maxInvalidWindows,
            absoluteThresholdDeg = p[Keys.ABSOLUTE_THRESHOLD] ?: d.absoluteThresholdDeg,
            calibrationDeltaDeg = p[Keys.CALIBRATION_DELTA] ?: d.calibrationDeltaDeg,
            hysteresisDeg = p[Keys.HYSTERESIS] ?: d.hysteresisDeg,
            minValidFrames = p[Keys.MIN_VALID_FRAMES] ?: d.minValidFrames,
            consecutiveBadWindows = p[Keys.CONSECUTIVE_BAD] ?: d.consecutiveBadWindows,
            cooldownMin = p[Keys.COOLDOWN_MIN] ?: d.cooldownMin,
            maxShoulderOffsetRatio = p[Keys.MAX_SHOULDER_OFFSET] ?: d.maxShoulderOffsetRatio,
            baselineDeg = p[Keys.BASELINE],
            recoveredNotify = RecoveredNotify.parse(p[Keys.RECOVERED_NOTIFY]) ?: d.recoveredNotify,
            pcEndpoint = p[Keys.PC_ENDPOINT] ?: d.pcEndpoint,
            pcToken = p[Keys.PC_TOKEN] ?: d.pcToken,
            pcEnabled = p[Keys.PC_ENABLED] ?: d.pcEnabled,
        )
    }
}
