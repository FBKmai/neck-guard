package com.local.neckguard.report

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网接收端自动发现，对应 pc/neck_receiver.py 的 DiscoveryService。
 *
 * 做法参考 LANDrop：向 UDP 广播发探针，在监听的接收端单播回应自己的地址。
 * 关键点是接收端回的 host 由它那侧的「路由探测」算出，天然与手机同网段，
 * 避免用户从 ipconfig 的一堆地址里挑到虚拟网卡（VMware / WSL / Hyper-V）导致连接被拒绝。
 *
 * 回包是单播，不需要 MulticastLock，也不需要额外权限。
 */
object PcDiscovery {

    /** 发现到的一台电脑接收端。 */
    data class Receiver(
        val name: String,
        val host: String,
        val port: Int,
        val tokenRequired: Boolean,
    ) {
        /** 直接可写进设置的地址。 */
        val baseUrl: String get() = "http://$host:$port"
    }

    /**
     * 广播探针并收集回应。
     *
     * @param timeoutMillis 总收集时长，到点就返回已发现的结果。
     * @return 按地址去重后的接收端列表；失败或无人应答时返回空列表，不抛异常。
     */
    suspend fun discover(
        port: Int = DEFAULT_DISCOVERY_PORT,
        timeoutMillis: Long = 1_500L,
    ): List<Receiver> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Receiver>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = SOCKET_POLL_MILLIS
            }
            val probe = JSONObject()
                .put(MAGIC, "discover")
                .put("v", 1)
                .toString()
                .toByteArray(Charsets.UTF_8)

            var sent = 0
            for (target in broadcastTargets()) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, target, port))
                    sent++
                } catch (e: Exception) {
                    // 某些网卡不可达是常态，只要有一个发出去就行
                    Log.d(TAG, "send probe to $target failed: ${describe(e)}")
                }
            }
            if (sent == 0) {
                Log.w(TAG, "no broadcast target reachable")
                return@withContext emptyList()
            }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue // 只是这一轮没收到，继续等到 deadline
                } catch (e: Exception) {
                    Log.d(TAG, "receive failed: ${describe(e)}")
                    break
                }
                parse(packet)?.let { found.putIfAbsent(it.baseUrl, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discover failed: ${describe(e)}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        Log.i(TAG, "discovered ${found.size} receiver(s)")
        found.values.toList()
    }

    /** 全局广播加各网卡定向广播，覆盖屏蔽 255.255.255.255 的路由器。 */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            Log.d(TAG, "global broadcast unavailable: ${describe(e)}")
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
            for (nif in interfaces) {
                if (nif.isLoopback || !nif.isUp) continue
                for (addr in nif.interfaceAddresses) {
                    addr.broadcast?.let { targets.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "enumerate interfaces failed: ${describe(e)}")
        }
        return targets.toList()
    }

    private fun parse(packet: DatagramPacket): Receiver? = try {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = JSONObject(text)
        if (json.optString(MAGIC) != "receiver") {
            null
        } else {
            val host = json.optString("host").takeIf { it.isNotBlank() }
                ?: packet.address?.hostAddress
            val port = json.optInt("port", 0)
            if (host.isNullOrBlank() || port !in 1..65535) {
                null
            } else {
                Receiver(
                    name = json.optString("name").takeIf { it.isNotBlank() } ?: host,
                    host = host,
                    port = port,
                    tokenRequired = json.optBoolean("token_required", false),
                )
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "parse reply failed: ${describe(e)}")
        null
    }

    private fun describe(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

    private const val TAG = "PcDiscovery"
    private const val MAGIC = "neckguard"
    private const val SOCKET_POLL_MILLIS = 300

    /** 与 pc/neck_receiver.py 的 DISCOVERY_PORT 一致。 */
    const val DEFAULT_DISCOVERY_PORT = 8766
}
