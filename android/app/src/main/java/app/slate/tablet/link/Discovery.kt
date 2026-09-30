package app.slate.tablet.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.PacketType
import app.slate.tablet.protocol.Protocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

data class FoundPc(val name: String, val address: InetAddress, val port: Int)

/** Listens for the PCs' DISCOVER broadcasts while started. Results arrive on the main thread. */
class Discovery(context: Context, private val onChange: (List<FoundPc>) -> Unit) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (socket != null) return
        // Many devices filter broadcast packets unless an app holds this.
        lock = wifi?.createMulticastLock("slate-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
        } catch (e: SocketException) {
            Log.w("SlateDiscovery", "Can't listen for PCs", e)
            return
        }
        socket = s
        thread(name = "slate-discovery", isDaemon = true) { listen(s) }
    }

    fun stop() {
        socket?.close()
        socket = null
        lock?.release()
        lock = null
    }

    private fun listen(s: DatagramSocket) {
        val seen = LinkedHashMap<String, Pair<FoundPc, Long>>()
        val buf = ByteArray(512)
        val dp = DatagramPacket(buf, buf.size)
        var lastReported = emptyList<FoundPc>()
        while (!s.isClosed) {
            try {
                dp.setLength(buf.size)
                s.receive(dp)
                val p = Packet.decode(buf, 0, dp.length)
                if (p != null && p.type == PacketType.DISCOVER && p.version == Protocol.VERSION) {
                    val pc = FoundPc(p.name.ifEmpty { dp.address.hostAddress ?: "PC" }, dp.address, p.udpPort)
                    seen[dp.address.hostAddress + ":" + p.udpPort] = pc to SystemClock.uptimeMillis()
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                break
            }
            val now = SystemClock.uptimeMillis()
            seen.values.removeAll { now - it.second > 3500 }
            val list = seen.values.map { it.first }
            if (list != lastReported) {
                lastReported = list
                main.post { onChange(list) }
            }
        }
    }
}
