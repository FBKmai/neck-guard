package com.local.neckguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.neckguard.pose.AnalyzerConfig
import com.local.neckguard.pose.GeometryConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "neckguard_settings")

/** 用户可调参数。时间单位在字段名里标明。 */
data class Settings(
    val useFrontCamera: Boolean = true,
    /** 两次采样之间的基础间隔。 */
    val intervalSec: Int = 45,
    /** 在基础间隔上叠加的随机抖动上限（正负）。 */
    val jitterSec: Int = 15,
    /** 每次采样窗持续时间。 */
    val windowSec: Int = 3,
    val absoluteThresholdDeg: Float = 40f,
    val calibrationDeltaDeg: Float = 12f,
    val hysteresisDeg: Float = 4f,
    val minValidFrames: Int = 8,
    val consecutiveBadWindows: Int = 2,
    val cooldownMin: Int = 10,
    val maxShoulderOffsetRatio: Float = 0.35f,
    /** null 表示未校准。 */
    val baselineDeg: Float? = null,
    /** 调试用：每个有效采样窗都保存快照。 */
    val saveEveryWindowSnapshot: Boolean = false,
) {
    fun toAnalyzerConfig(): AnalyzerConfig = AnalyzerConfig(
        absoluteThresholdDeg = absoluteThresholdDeg,
        calibrationDeltaDeg = calibrationDeltaDeg,
        hysteresisDeg = hysteresisDeg,
        minValidFramesPerWindow = minValidFrames,
        consecutiveBadWindows = consecutiveBadWindows,
        cooldownMillis = cooldownMin.toLong() * 60_000L,
    )

    fun toGeometryConfig(): GeometryConfig = GeometryConfig(maxShoulderOffsetRatio = maxShoulderOffsetRatio)
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val USE_FRONT_CAMERA = booleanPreferencesKey("use_front_camera")
        val INTERVAL_SEC = intPreferencesKey("interval_sec")
        val JITTER_SEC = intPreferencesKey("jitter_sec")
        val WINDOW_SEC = intPreferencesKey("window_sec")
        val ABSOLUTE_THRESHOLD = floatPreferencesKey("absolute_threshold_deg")
        val CALIBRATION_DELTA = floatPreferencesKey("calibration_delta_deg")
        val HYSTERESIS = floatPreferencesKey("hysteresis_deg")
        val MIN_VALID_FRAMES = intPreferencesKey("min_valid_frames")
        val CONSECUTIVE_BAD = intPreferencesKey("consecutive_bad_windows")
        val COOLDOWN_MIN = intPreferencesKey("cooldown_min")
        val MAX_SHOULDER_OFFSET = floatPreferencesKey("max_shoulder_offset_ratio")
        val BASELINE = floatPreferencesKey("baseline_deg")
        val SAVE_EVERY_WINDOW = booleanPreferencesKey("save_every_window_snapshot")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        val d = Settings()
        Settings(
            useFrontCamera = p[Keys.USE_FRONT_CAMERA] ?: d.useFrontCamera,
            intervalSec = p[Keys.INTERVAL_SEC] ?: d.intervalSec,
            jitterSec = p[Keys.JITTER_SEC] ?: d.jitterSec,
            windowSec = p[Keys.WINDOW_SEC] ?: d.windowSec,
            absoluteThresholdDeg = p[Keys.ABSOLUTE_THRESHOLD] ?: d.absoluteThresholdDeg,
            calibrationDeltaDeg = p[Keys.CALIBRATION_DELTA] ?: d.calibrationDeltaDeg,
            hysteresisDeg = p[Keys.HYSTERESIS] ?: d.hysteresisDeg,
            minValidFrames = p[Keys.MIN_VALID_FRAMES] ?: d.minValidFrames,
            consecutiveBadWindows = p[Keys.CONSECUTIVE_BAD] ?: d.consecutiveBadWindows,
            cooldownMin = p[Keys.COOLDOWN_MIN] ?: d.cooldownMin,
            maxShoulderOffsetRatio = p[Keys.MAX_SHOULDER_OFFSET] ?: d.maxShoulderOffsetRatio,
            baselineDeg = p[Keys.BASELINE],
            saveEveryWindowSnapshot = p[Keys.SAVE_EVERY_WINDOW] ?: d.saveEveryWindowSnapshot,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsStore.edit { p ->
            val old = readFrom(p)
            val new = transform(old)
            p[Keys.USE_FRONT_CAMERA] = new.useFrontCamera
            p[Keys.INTERVAL_SEC] = new.intervalSec
            p[Keys.JITTER_SEC] = new.jitterSec
            p[Keys.WINDOW_SEC] = new.windowSec
            p[Keys.ABSOLUTE_THRESHOLD] = new.absoluteThresholdDeg
            p[Keys.CALIBRATION_DELTA] = new.calibrationDeltaDeg
            p[Keys.HYSTERESIS] = new.hysteresisDeg
            p[Keys.MIN_VALID_FRAMES] = new.minValidFrames
            p[Keys.CONSECUTIVE_BAD] = new.consecutiveBadWindows
            p[Keys.COOLDOWN_MIN] = new.cooldownMin
            p[Keys.MAX_SHOULDER_OFFSET] = new.maxShoulderOffsetRatio
            p[Keys.SAVE_EVERY_WINDOW] = new.saveEveryWindowSnapshot
            val baseline = new.baselineDeg
            if (baseline == null) p.remove(Keys.BASELINE) else p[Keys.BASELINE] = baseline
        }
    }

    suspend fun setBaseline(deg: Float?) = update { it.copy(baselineDeg = deg) }

    private fun readFrom(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            useFrontCamera = p[Keys.USE_FRONT_CAMERA] ?: d.useFrontCamera,
            intervalSec = p[Keys.INTERVAL_SEC] ?: d.intervalSec,
            jitterSec = p[Keys.JITTER_SEC] ?: d.jitterSec,
            windowSec = p[Keys.WINDOW_SEC] ?: d.windowSec,
            absoluteThresholdDeg = p[Keys.ABSOLUTE_THRESHOLD] ?: d.absoluteThresholdDeg,
            calibrationDeltaDeg = p[Keys.CALIBRATION_DELTA] ?: d.calibrationDeltaDeg,
            hysteresisDeg = p[Keys.HYSTERESIS] ?: d.hysteresisDeg,
            minValidFrames = p[Keys.MIN_VALID_FRAMES] ?: d.minValidFrames,
            consecutiveBadWindows = p[Keys.CONSECUTIVE_BAD] ?: d.consecutiveBadWindows,
            cooldownMin = p[Keys.COOLDOWN_MIN] ?: d.cooldownMin,
            maxShoulderOffsetRatio = p[Keys.MAX_SHOULDER_OFFSET] ?: d.maxShoulderOffsetRatio,
            baselineDeg = p[Keys.BASELINE],
            saveEveryWindowSnapshot = p[Keys.SAVE_EVERY_WINDOW] ?: d.saveEveryWindowSnapshot,
        )
    }
}
