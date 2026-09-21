package com.local.neckguard.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EventType {
    /** 已确认前倾并发出通知。 */
    ALERT,
    /** 已确认前倾但处于冷却期，只记录。 */
    CONFIRMED,
    /** 一个确认窗的结果。 */
    WINDOW,
    /** 巡检发现疑似前倾，进入确认模式。 */
    TRIGGER,
    /** 前倾后重新坐正。 */
    RECOVERED,
    /** 相机绑定失败或断流重连。 */
    CAMERA,
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
    /** 该事件关联的截图，ALERT/CONFIRMED 可能有多张（前倾过程中的多帧），WINDOW 最多一张。 */
    val snapshotPaths: List<String> = emptyList(),
    /** WINDOW 事件的判定：GOOD / BAD / INVALID。 */
    val verdict: String? = null,
    /** RECOVERED 事件：本次前倾从确认到恢复持续了多久。 */
    val forwardHeadMillis: Long? = null,
) {
    val snapshotPath: String?
        get() = snapshotPaths.firstOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestampMillis)
        put("type", type.name)
        neckDeg?.let { put("neck", it.toDouble()) }
        torsoDeg?.let { put("torso", it.toDouble()) }
        thresholdDeg?.let { put("threshold", it.toDouble()) }
        message?.let { put("message", it) }
        verdict?.let { put("verdict", it) }
        forwardHeadMillis?.let { put("forwardHeadMs", it) }
        if (snapshotPaths.isNotEmpty()) put("snapshots", JSONArray(snapshotPaths))
    }

    companion object {
        fun fromJson(json: JSONObject): PostureEvent {
            val paths = ArrayList<String>()
            json.optJSONArray("snapshots")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(paths::add)
            }
            // 兼容旧格式的单张 snapshot 字段
            if (paths.isEmpty() && json.has("snapshot")) paths.add(json.getString("snapshot"))
            return PostureEvent(
                timestampMillis = json.getLong("ts"),
                type = runCatching { EventType.valueOf(json.getString("type")) }.getOrDefault(EventType.INFO),
                neckDeg = if (json.has("neck")) json.getDouble("neck").toFloat() else null,
                torsoDeg = if (json.has("torso")) json.getDouble("torso").toFloat() else null,
                thresholdDeg = if (json.has("threshold")) json.getDouble("threshold").toFloat() else null,
                message = if (json.has("message")) json.getString("message") else null,
                snapshotPaths = paths,
                verdict = if (json.has("verdict")) json.getString("verdict") else null,
                forwardHeadMillis = if (json.has("forwardHeadMs")) json.getLong("forwardHeadMs") else null,
            )
        }
    }
}

/**
 * 追加写 JSONL 事件日志。文件很小（每条约 150 字节），超过 MAX_LINES 时裁掉最旧的一半。
 * 所有 IO 在 Dispatchers.IO 上执行；写入用文件锁对象串行化。
 */
class EventLog(context: Context) {

    private val file = File(context.filesDir, "events.jsonl")
    private val lock = Any()

    suspend fun append(event: PostureEvent): Unit = withContext(Dispatchers.IO) {
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
