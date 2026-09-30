package app.slate.tablet.link

import android.content.res.Resources
import android.util.Log
import app.slate.tablet.protocol.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * USB transport: listens on 127.0.0.1:47812. The PC reaches it through `adb forward`, so only
 * a PC with USB debugging access to this tablet can connect.
 */
object UsbServer {
    private const val TAG = "SlateUsb"
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "slate-usb-accept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (true) {
            try {
                ServerSocket(Protocol.USB_PORT, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.reuseAddress = true
                    Log.i(TAG, "Listening on 127.0.0.1:${Protocol.USB_PORT}")
                    while (true) {
                        val socket = server.accept()
                        thread(name = "slate-usb-session", isDaemon = true) { session(socket) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "USB listener failed, retrying", e)
                Thread.sleep(1000)
            }
        }
    }

    private fun session(socket: Socket) {
        socket.tcpNoDelay = true
        val ch = TcpChannel(socket, "USB")
        SlateLink.begin(ch, "PC found on USB, connecting…")
        val dm = Resources.getSystem().displayMetrics
        ch.send(SlateLink.hello(Protocol.NO_PAIR_CODE, dm.widthPixels, dm.heightPixels))
        var why = "USB disconnected. Waiting for the PC…"
        try {
            ch.readLoop { SlateLink.onPacket(ch, it) }
        } catch (e: IOException) {
            // Cable pulled, PC app closed, or adb restarted.
        } catch (e: Exception) {
            Log.e(TAG, "USB session failed", e)
            why = "USB error: ${e.message}"
        }
        SlateLink.onChannelClosed(ch, why)
    }
}
