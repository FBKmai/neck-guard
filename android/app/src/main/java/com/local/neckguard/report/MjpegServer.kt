package com.local.neckguard.report

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 MJPEG over HTTP 推流服务器，供电脑端当作无线相机拉流。
 *
 * 只用 JDK 的 ServerSocket，不引入任何 HTTP 库，写法与 PcSink 手写 multipart 保持一致。
 *
 * 路由：
 *   GET /video    -> multipart/x-mixed-replace 长连接，每帧一段 JPEG
 *   GET /snapshot -> 最新一帧 JPEG
 *   GET /info     -> {"name","width","height","fps","lens","token_required","v"}
 *   GET /         -> 一页内嵌 <img> 的 HTML，方便用浏览器确认画面
 *
 * 鉴权：token 非空时校验请求头 X-Neck-Token 或查询参数 ?token=，不符返回 401。
 *
 * 帧分发采用「只保留最新帧」：publish 覆盖式写入并唤醒所有客户端线程，
 * 慢客户端下一轮直接拿到最新帧，天然丢帧，不会堆积内存也不会拖慢相机。
 */
class MjpegServer(
    private val port: Int,
    private val token: String,
    /** 供 /info 与 HTML 页展示，不影响推流本身。 */
    private val deviceName: String,
    private val lensDescription: () -> String?,
    private val targetFps: () -> Int,
) {

    private val running = AtomicBoolean(false)
    private val clients = AtomicInteger(0)
    private val bytesSent = AtomicLong(0L)
    private val framesPublished = AtomicLong(0L)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** 最新一帧。与 seq 一起在 frameLock 下读写。 */
    private var latestJpeg: ByteArray? = null
    private var latestWidth = 0
    private var latestHeight = 0
    private var frameSeq = 0L
    private val frameLock = Object()

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.get()
    val totalBytesSent: Long get() = bytesSent.get()
    val totalFramesPublished: Long get() = framesPublished.get()

    /** 启动监听。已在运行时直接返回 true；端口被占用等失败返回 false 并记入 [lastError]。 */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket()
            // 端口刚被上一次推流占用过时不等 TIME_WAIT
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), BACKLOG)
            serverSocket = socket
            running.set(true)
            lastError = null
            acceptThread = Thread({ acceptLoop(socket) }, "mjpeg-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "mjpeg server listening on :$port")
            true
        } catch (e: Exception) {
            lastError = describe(e)
            Log.e(TAG, "start mjpeg server failed", e)
            running.set(false)
            closeQuietly(serverSocket)
            serverSocket = null
            false
        }
    }

    /** 停止监听并断开所有客户端。幂等。 */
    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(serverSocket)
        serverSocket = null
        // 唤醒还卡在 wait 的客户端线程，让它们看到 running=false 后退出
        synchronized(frameLock) {
            latestJpeg = null
            frameLock.notifyAll()
        }
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "mjpeg server stopped")
    }

    /**
     * 发布一帧。覆盖上一帧，不排队。
     * 由相机分析线程调用，必须足够轻：只做一次引用赋值加一次 notifyAll。
     */
    fun publish(jpeg: ByteArray, width: Int, height: Int) {
        if (!running.get()) return
        synchronized(frameLock) {
            latestJpeg = jpeg
            latestWidth = width
            latestHeight = height
            frameSeq++
            framesPublished.incrementAndGet()
            frameLock.notifyAll()
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                // stop() 关掉 socket 会走到这里，属正常退出
                if (running.get()) Log.w(TAG, "accept failed: ${describe(e)}")
                break
            }
            if (clients.get() >= MAX_CLIENTS) {
                Thread({ rejectBusy(client) }, "mjpeg-busy").apply { isDaemon = true }.start()
                continue
            }
            Thread({ serve(client) }, "mjpeg-client").apply { isDaemon = true }.start()
        }
    }

    private fun rejectBusy(socket: Socket) {
        try {
            socket.use {
                it.getOutputStream().write(
                    httpHeader(503, "text/plain; charset=utf-8", "连接数已达上限 $MAX_CLIENTS".toByteArray(Charsets.UTF_8).size)
                        .toByteArray(Charsets.UTF_8),
                )
                it.getOutputStream().write("连接数已达上限 $MAX_CLIENTS".toByteArray(Charsets.UTF_8))
                it.getOutputStream().flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun serve(socket: Socket) {
        clients.incrementAndGet()
        try {
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.tcpNoDelay = true
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) {
                writeText(socket, 405, "只支持 GET")
                return
            }
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            // 读完请求头，顺便取出 token
            var headerToken: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals(HEADER_TOKEN, ignoreCase = true)) {
                    headerToken = line.substring(idx + 1).trim()
                }
            }

            if (!authorized(headerToken, query)) {
                writeText(socket, 401, "密钥不匹配：请填与手机端一致的推流密钥")
                return
            }

            when (path) {
                "/video", "/video.mjpg" -> streamVideo(socket)
                "/snapshot", "/snapshot.jpg" -> writeSnapshot(socket)
                "/info" -> writeInfo(socket)
                "/" , "/index.html" -> writeIndex(socket)
                else -> writeText(socket, 404, "not found")
            }
        } catch (e: Exception) {
            // 客户端中途断开是常态，只在调试级别记录
            Log.d(TAG, "client closed: ${describe(e)}")
        } finally {
            clients.decrementAndGet()
            closeQuietly(socket)
        }
    }

    private fun authorized(headerToken: String?, query: String): Boolean {
        if (token.isBlank()) return true
        if (headerToken == token) return true
        // 浏览器里没法加自定义头，允许 ?token= 兜底
        val fromQuery = query.split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?.let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: Exception) {
                    it
                }
            }
        return fromQuery == token
    }

    /**
     * 长连接推流。每轮等新帧，拿到就写一段 multipart。
     * 写失败（客户端断开）直接抛出，由 serve 的 catch 收尾。
     */
    private fun streamVideo(socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_BYTES)
        // 推流是长连接，不能设写超时之外的读超时，否则空闲时会被中断
        socket.soTimeout = 0
        out.write(
            (
                "HTTP/1.0 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, private\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        out.flush()

        var lastSeq = 0L
        while (running.get() && !socket.isClosed) {
            // 取一帧：等到 seq 变化或超时。返回 null 表示这一轮没拿到，外层直接再等。
            val taken: Pair<ByteArray, Long>? = synchronized(frameLock) {
                while (running.get() && frameSeq == lastSeq) {
                    frameLock.wait(WAIT_TIMEOUT_MILLIS)
                }
                val current = latestJpeg
                if (!running.get() || current == null) null else current to frameSeq
            }
            if (!running.get()) return
            if (taken == null) continue
            val frame = taken.first
            lastSeq = taken.second
            out.write(
                (
                    "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            out.write(frame)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            bytesSent.addAndGet(frame.size.toLong())
        }
    }

    private fun writeSnapshot(socket: Socket) {
        val frame = synchronized(frameLock) { latestJpeg }
        if (frame == null) {
            writeText(socket, 503, "还没有画面，请确认手机端已开始推流")
            return
        }
        val out = socket.getOutputStream()
        out.write(httpHeader(200, "image/jpeg", frame.size).toByteArray(Charsets.UTF_8))
        out.write(frame)
        out.flush()
        bytesSent.addAndGet(frame.size.toLong())
    }

    private fun writeInfo(socket: Socket) {
        val (w, h) = synchronized(frameLock) { latestWidth to latestHeight }
        val json = buildString {
            append("{")
            append("\"neckguard\":\"camera\",")
            append("\"name\":\"").append(escapeJson(deviceName)).append("\",")
            append("\"width\":").append(w).append(',')
            append("\"height\":").append(h).append(',')
            append("\"fps\":").append(targetFps()).append(',')
            append("\"lens\":\"").append(escapeJson(lensDescription() ?: "")).append("\",")
            append("\"frames\":").append(framesPublished.get()).append(',')
            append("\"clients\":").append(clients.get()).append(',')
            append("\"token_required\":").append(token.isNotBlank()).append(',')
            append("\"v\":1")
            append("}")
        }
        writeBody(socket, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun writeIndex(socket: Socket) {
        val suffix = if (token.isBlank()) "" else "?token=$token"
        val html = """
            <!doctype html><meta charset="utf-8"><title>颈椎卫士 无线相机</title>
            <style>body{margin:0;background:#111;color:#eee;font-family:sans-serif;text-align:center}
            img{max-width:100%;height:auto}p{padding:8px;font-size:14px}</style>
            <p>$deviceName · 目标 ${targetFps()} fps · 看到画面说明电脑可以连上</p>
            <img src="/video$suffix" alt="live">
        """.trimIndent()
        writeBody(socket, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun writeText(socket: Socket, status: Int, message: String) =
        writeBody(socket, status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8))

    private fun writeBody(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        out.write(httpHeader(status, contentType, body.size).toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun httpHeader(status: Int, contentType: String, length: Int): String {
        val reason = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        return "HTTP/1.0 $status $reason\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $length\r\n\r\n"
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketException, is IOException -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    }

    companion object {
        private const val TAG = "MjpegServer"
        const val HEADER_TOKEN = "X-Neck-Token"
        const val DEFAULT_PORT = 8767
        private const val BOUNDARY = "neckguardframe"
        private const val BACKLOG = 8
        private const val MAX_CLIENTS = 4
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val WAIT_TIMEOUT_MILLIS = 1_000L
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        private fun escapeJson(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
        }
    }
}
