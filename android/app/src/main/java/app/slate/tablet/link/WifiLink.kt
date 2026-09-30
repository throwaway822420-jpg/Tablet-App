package app.slate.tablet.link

import android.content.res.Resources
import android.util.Log
import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.PacketType
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import kotlin.concurrent.thread

/** Wi-Fi transport: UDP to the PC's port, paired with the 4-digit code shown on the PC. */
object WifiLink {
    private const val TAG = "SlateWifi"
    private const val HELLO_RETRY_MS = 500
    private const val HELLO_GIVE_UP_MS = 5000

    fun connect(address: InetAddress, port: Int, pairCode: Int, pcName: String) {
        thread(name = "slate-wifi-session", isDaemon = true) {
            val socket = try {
                DatagramSocket().apply { connect(InetSocketAddress(address, port)) }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't open UDP socket", e)
                return@thread
            }
            SlateLink.setWifiPc(address, pairCode)
            val ch = UdpChannel(socket, "Wi-Fi")
            SlateLink.begin(ch, "Connecting to $pcName over Wi-Fi…")
            val dm = Resources.getSystem().displayMetrics
            val hello = SlateLink.hello(pairCode, dm.widthPixels, dm.heightPixels)
            val started = nowMs()
            ch.send(hello)

            var why = "Wi-Fi connection closed."
            try {
                ch.readLoop(
                    idleMs = HELLO_RETRY_MS,
                    onIdle = {
                        when {
                            ch.configured || !SlateLink.isCurrent(ch) -> true
                            nowMs() - started >= HELLO_GIVE_UP_MS -> {
                                why = "No answer from $pcName. Check Slate is running there, both are on the same network, " +
                                    "and Windows Firewall allows Slate on private networks."
                                false
                            }
                            else -> {
                                ch.send(hello) // HELLO or its CONFIG got lost
                                true
                            }
                        }
                    },
                    onPacket = { p ->
                        if (p.type != PacketType.DISCOVER) SlateLink.onPacket(ch, p)
                    },
                )
            } catch (e: PortUnreachableException) {
                why = "Slate isn't running on $pcName."
            } catch (e: IOException) {
                why = "Wi-Fi connection lost."
            }
            SlateLink.onChannelClosed(ch, why)
        }
    }
}
