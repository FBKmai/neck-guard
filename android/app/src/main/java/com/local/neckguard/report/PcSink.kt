package com.local.neckguard.report

import android.util.Log
import com.local.neckguard.data.PostureEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 局域网电脑接收端（pc/neck_receiver.py）的 HTTP 出口。
 * 协议：
 *   GET  {base}/api/ping                 -> 200 {"ok":true,...}
 *   POST {base}/api/posture/events       multipart: event(JSON) + snapshot(image/jpeg，可选)
 *   请求头 X-Neck-Token 可选。
 * 所有网络异常都在这里吞掉并记日志，不向调用方抛出。
 */
class PcSink(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
) : EventSink {

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Failed(val message: String) : Result()
    }

    override suspend fun deliver(event: PostureEvent, snapshot: File?) {
        when (val r = send(event, snapshot)) {
            is Result.Ok -> Log.i(TAG, "event delivered to pc: ${r.body}")
            is Result.Failed -> Log.w(TAG, "deliver to pc failed: ${r.message}")
        }
    }

    suspend fun ping(): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$baseUrl/api/ping").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                if (token.isNotBlank()) setRequestProperty(HEADER_TOKEN, token)
            }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code in 200..299) Result.Ok(body) else Result.Failed("HTTP $code $body")
        } catch (e: IOException) {
            Result.Failed(describe(e))
        } catch (e: Exception) {
            Result.Failed(describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** 设置页「发送测试事件」：线上类型固定为 TEST，电脑端据此显示测试标题。 */
    suspend fun sendTest(event: PostureEvent, snapshot: File?): Result = send(event, snapshot, wireType = "TEST")

    suspend fun send(event: PostureEvent, snapshot: File?, wireType: String = event.type.name): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val boundary = "----NeckGuard" + UUID.randomUUID().toString().replace("-", "")
            conn = (URL("$baseUrl/api/posture/events").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (token.isNotBlank()) setRequestProperty(HEADER_TOKEN, token)
                setChunkedStreamingMode(0)
            }
            BufferedOutputStream(conn.outputStream).use { out ->
                fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                writeAscii("--$boundary\r\n")
                writeAscii("Content-Disposition: form-data; name=\"event\"\r\n")
                writeAscii("Content-Type: application/json; charset=utf-8\r\n\r\n")
                writeAscii(toWireJson(event, wireType).toString())
                writeAscii("\r\n")
                if (snapshot != null && snapshot.isFile) {
                    writeAscii("--$boundary\r\n")
                    writeAscii("Content-Disposition: form-data; name=\"snapshot\"; filename=\"${snapshot.name}\"\r\n")
                    writeAscii("Content-Type: image/jpeg\r\n\r\n")
                    snapshot.inputStream().use { it.copyTo(out) }
                    writeAscii("\r\n")
                }
                writeAscii("--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code in 200..299) Result.Ok(body) else Result.Failed("HTTP $code $body")
        } catch (e: IOException) {
            Result.Failed(describe(e))
        } catch (e: Exception) {
            Result.Failed(describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun toWireJson(event: PostureEvent, wireType: String): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("ts", event.timestampMillis)
        put("type", wireType)
        put("neckDeg", event.neckDeg?.toDouble() ?: JSONObject.NULL)
        put("torsoDeg", event.torsoDeg?.toDouble() ?: JSONObject.NULL)
        put("thresholdDeg", event.thresholdDeg?.toDouble() ?: JSONObject.NULL)
        put("message", event.message ?: JSONObject.NULL)
    }

    private fun readBody(conn: HttpURLConnection): String = try {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(500) ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

    companion object {
        private const val TAG = "PcSink"
        const val HEADER_TOKEN = "X-Neck-Token"
    }
}
