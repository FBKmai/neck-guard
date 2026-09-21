package com.local.neckguard.report

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让电脑端自动发现本机推流地址，是 [PcDiscovery] 的反向版本。
 *
 * 原来的方向是「手机找电脑」（手机广播探针、电脑单播回地址），
 * 无线相机模式下反过来：电脑广播探针，手机回自己的推流地址。
 *
 * 协议：
 *   收 {"neckguard": "discover-camera", "v": 1}
 *   回 {"neckguard": "camera", "name", "host", "port", "token_required", "v": 1}
 *
 * host 用「连到对端后读本地地址」的办法算出与电脑同网段的那个 IP，
 * 和 pc/neck_receiver.py 的 route_ip_toward 同理：手机上可能同时有
 * Wi-Fi、数据网络和热点地址，回错了电脑就连不上。
 */
class CameraDiscoveryResponder(
    private val discoveryPort: Int,
    private val streamPort: () -> Int,
    private val tokenRequired: () -> Boolean,
    private val deviceName: String,
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = running.get()

    /** 绑定并开始应答。失败只记录不抛出：用户仍可在电脑端手填地址。 */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = POLL_MILLIS
                bind(InetSocketAddress(discoveryPort))
            }
            socket = s
            running.set(true)
            lastError = null
            thread = Thread({ loop(s) }, "camera-discovery").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "discovery responder on udp:$discoveryPort")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e::class.java.simpleName
            Log.w(TAG, "bind udp:$discoveryPort failed, 电脑端需手填地址", e)
            running.set(false)
            closeQuietly()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly()
        thread?.interrupt()
        thread = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "receive failed: ${e.message}")
                continue
            }
            try {
                reply(s, packet)
            } catch (e: Exception) {
                // 单个坏包不能拖垮整个线程
                Log.d(TAG, "reply failed: ${e.message}")
            }
        }
    }

    private fun reply(s: DatagramSocket, packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val probe = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (probe.optString(MAGIC) != PROBE) return

        val peer = packet.address ?: return
        val host = localAddressToward(peer) ?: return
        val payload = JSONObject()
            .put(MAGIC, "camera")
            .put("name", deviceName)
            .put("host", host)
            .put("port", streamPort())
            .put("token_required", tokenRequired())
            .put("v", 1)
            .toString()
            .toByteArray(Charsets.UTF_8)
        s.send(DatagramPacket(payload, payload.size, peer, packet.port))
        Log.i(TAG, "replied to ${peer.hostAddress} -> http://$host:${streamPort()}/video")
    }

    /** 借内核选路得到与对端同网段的本机地址（connect UDP 不实际发包）。 */
    private fun localAddressToward(peer: InetAddress): String? {
        var probe: DatagramSocket? = null
        return try {
            probe = DatagramSocket()
            probe.connect(peer, 9)
            probe.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" && !it.startsWith("127.") }
        } catch (e: Exception) {
            Log.d(TAG, "route probe failed: ${e.message}")
            null
        } finally {
            try {
                probe?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    companion object {
        private const val TAG = "CameraDiscovery"
        private const val MAGIC = "neckguard"
        private const val PROBE = "discover-camera"
        private const val POLL_MILLIS = 500

        /** 与 pc/neck_camera_monitor.py 的 CAMERA_DISCOVERY_PORT 一致。 */
        const val DEFAULT_PORT = 8768
    }
}
