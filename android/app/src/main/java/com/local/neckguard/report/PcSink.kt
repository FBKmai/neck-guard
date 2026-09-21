package com.local.neckguard.report

import android.util.Log
import com.local.neckguard.data.PostureEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
            // 先在内存里拼好整个 body，才能给出 Content-Length。
            // 接收端 neck_receiver.py 按 Content-Length 读取，不支持 chunked，
            // 所以这里不能用 setChunkedStreamingMode。截图通常几十 KB，内存可控。
            val body = buildMultipartBody(boundary, event, snapshot, wireType)
            conn = (URL("$baseUrl/api/posture/events").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (token.isNotBlank()) setRequestProperty(HEADER_TOKEN, token)
                setFixedLengthStreamingMode(body.size)
            }
            BufferedOutputStream(conn.outputStream).use { out ->
                out.write(body)
                out.flush()
            }
            val code = conn.responseCode
            val responseBody = readBody(conn)
            if (code in 200..299) Result.Ok(responseBody) else Result.Failed("HTTP $code $responseBody")
        } catch (e: IOException) {
            Result.Failed(describe(e))
        } catch (e: Exception) {
            Result.Failed(describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** 拼 multipart 请求体。截图以流式读入，避免整张图再多一份拷贝。 */
    private fun buildMultipartBody(
        boundary: String,
        event: PostureEvent,
        snapshot: File?,
        wireType: String,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        fun writeAscii(s: String) = buffer.write(s.toByteArray(Charsets.UTF_8))
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"event\"\r\n")
        writeAscii("Content-Type: application/json; charset=utf-8\r\n\r\n")
        writeAscii(toWireJson(event, wireType).toString())
        writeAscii("\r\n")
        if (snapshot != null && snapshot.isFile) {
            writeAscii("--$boundary\r\n")
            writeAscii("Content-Disposition: form-data; name=\"snapshot\"; filename=\"${snapshot.name}\"\r\n")
            writeAscii("Content-Type: image/jpeg\r\n\r\n")
            snapshot.inputStream().use { it.copyTo(buffer) }
            writeAscii("\r\n")
        }
        writeAscii("--$boundary--\r\n")
        return buffer.toByteArray()
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

        /**
         * 把网络异常翻译成用户看得懂、并且指向下一步动作的提示。
         * 底层 message 附在后面，方便排查时对照日志。
         */
        fun humanize(raw: String): String {
            val lower = raw.lowercase()
            val hint = when {
                lower.contains("econnrefused") || lower.contains("connection refused") ->
                    "电脑拒绝了连接：地址多半填成了虚拟网卡（VMware / WSL / Hyper-V），或接收端没启动。建议点「扫描电脑」自动填"
                lower.contains("etimedout") || lower.contains("timed out") || lower.contains("timeout") ->
                    "连接超时：检查电脑防火墙是否放行，以及手机和电脑是否在同一个 Wi-Fi"
                lower.contains("ehostunreach") || lower.contains("no route to host") ->
                    "路由不可达：手机和电脑不在同一个局域网"
                lower.contains("enetunreach") || lower.contains("network is unreachable") ->
                    "网络不可达：手机可能没连上 Wi-Fi"
                lower.contains("unable to resolve host") || lower.contains("unknownhost") ->
                    "地址解析失败：请填 IP 而不是主机名"
                lower.contains("http 401") -> "密钥不匹配：手机的共享密钥要和接收端 --token 一致"
                lower.contains("http 404") -> "地址能连通但路径不对：只填到端口即可，不要带后面的路径"
                lower.contains("cleartext") -> "系统拦截了明文 HTTP 请求"
                else -> null
            }
            return if (hint == null) raw else "$hint（$raw）"
        }
    }
}
