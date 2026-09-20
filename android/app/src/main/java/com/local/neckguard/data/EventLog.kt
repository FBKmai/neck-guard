package com.local.neckguard.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class EventType {
    /** 已确认前倾并发出通知。 */
    ALERT,
    /** 已确认前倾但处于冷却期，只记录。 */
    CONFIRMED,
    /** 普通采样窗（仅在调试开关打开时记录）。 */
    WINDOW,
    ERROR,
    INFO,
}

data class PostureEvent(
    val timestampMillis: Long,
    val type: EventType,
    val neckDeg: Float? = null,
    val torsoDeg: Float? = null,
    val thresholdDeg: Float? = null,
    val message: String? = null,
    val snapshotPath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestampMillis)
        put("type", type.name)
        neckDeg?.let { put("neck", it.toDouble()) }
        torsoDeg?.let { put("torso", it.toDouble()) }
        thresholdDeg?.let { put("threshold", it.toDouble()) }
        message?.let { put("message", it) }
        snapshotPath?.let { put("snapshot", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): PostureEvent = PostureEvent(
            timestampMillis = json.getLong("ts"),
            type = runCatching { EventType.valueOf(json.getString("type")) }.getOrDefault(EventType.INFO),
            neckDeg = if (json.has("neck")) json.getDouble("neck").toFloat() else null,
            torsoDeg = if (json.has("torso")) json.getDouble("torso").toFloat() else null,
            thresholdDeg = if (json.has("threshold")) json.getDouble("threshold").toFloat() else null,
            message = if (json.has("message")) json.getString("message") else null,
            snapshotPath = if (json.has("snapshot")) json.getString("snapshot") else null,
        )
    }
}

/**
 * 追加写 JSONL 事件日志。文件很小（每条约 150 字节），超过 MAX_LINES 时裁掉最旧的一半。
 * 所有 IO 在 Dispatchers.IO 上执行；写入用文件锁对象串行化。
 */
class EventLog(context: Context) {

    private val file = File(context.filesDir, "events.jsonl")
    private val lock = Any()

    suspend fun append(event: PostureEvent) = withContext(Dispatchers.IO) {
        try {
            synchronized(lock) {
                file.appendText(event.toJson().toString() + "\n")
                trimIfNeeded()
            }
        } catch (e: Exception) {
            Log.e(TAG, "append event failed", e)
        }
    }

    suspend fun readLatest(limit: Int = 100): List<PostureEvent> = withContext(Dispatchers.IO) {
        try {
            synchronized(lock) {
                if (!file.exists()) return@withContext emptyList()
                file.readLines()
                    .asReversed()
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line -> runCatching { PostureEvent.fromJson(JSONObject(line)) }.getOrNull() }
                    .take(limit)
                    .toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "read events failed", e)
            emptyList()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { file.delete() }
        Unit
    }

    private fun trimIfNeeded() {
        if (file.length() < TRIM_CHECK_BYTES) return
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        val kept = lines.takeLast(MAX_LINES / 2)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val TAG = "EventLog"
        private const val MAX_LINES = 2000
        private const val TRIM_CHECK_BYTES = 300_000L
    }
}
