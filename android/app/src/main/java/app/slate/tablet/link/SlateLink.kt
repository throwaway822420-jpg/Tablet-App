package app.slate.tablet.link

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.slate.tablet.protocol.ByeReason
import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.PacketType
import app.slate.tablet.protocol.PenFlags
import app.slate.tablet.protocol.Protocol
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The tablet's one connection to a PC, whichever transport it came in on. Owns the liveness rules
 * from PROTOCOL.md: pings, the 250 ms pen-state refresh, and giving up on a silent PC.
 */
object SlateLink {
    private const val TAG = "SlateLink"
    private const val PING_EVERY_MS = 1000
    private const val REFRESH_PEN_MS = 250
    private const val PC_SILENT_MS = 5000

    enum class Phase { IDLE, CONNECTING, CONNECTED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val transport: Transport? = null,
        val pcName: String = "",
        val message: String = "",
        val rttMs: Int = -1,
        /** Target width / height from the PC's CONFIG; 0 = use the whole screen. */
        val aspect: Float = 0f,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slate-link").apply { isDaemon = true } }
    private val lock = Any()

    // Guarded by lock.
    private var current: Channel? = null
    private var lastPen: Packet? = null
    private var lastPenSentMs = 0
    private var lastPongMs = 0
    private var lastPingMs = 0
    private var rttMs = -1
    private var started = false

    @Volatile var status = Status()
        private set

    val deviceName: String = Build.MODEL ?: "Android tablet"

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        UsbServer.start()
        scheduler.scheduleWithFixedDelay(::tick, 100, 100, TimeUnit.MILLISECONDS)
    }

    /** Listener is called on the main thread, immediately with the current status and on every change. */
    fun addListener(l: (Status) -> Unit) {
        listeners.add(l)
        main.post { l(status) }
    }

    fun removeListener(l: (Status) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(s: Status) {
        status = s
        main.post { listeners.forEach { it(s) } }
    }

    /** A new channel takes over (the old one, if any, gets BYE). Call before sending HELLO on it. */
    fun begin(ch: Channel, connectingMessage: String) {
        val old: Channel?
        synchronized(lock) {
            old = current
            current = ch
            lastPen = null
            lastPongMs = nowMs()
            rttMs = -1
        }
        old?.let {
            it.send(Packet(PacketType.BYE, reason = ByeReason.NORMAL))
            it.close()
        }
        publish(Status(Phase.CONNECTING, ch.transport, "", connectingMessage))
    }

    fun isCurrent(ch: Channel) = synchronized(lock) { current === ch }

    fun hello(pairCode: Int, surfaceWidth: Int, surfaceHeight: Int) = Packet(
        PacketType.HELLO,
        pairCode = pairCode,
        surfaceWidth = surfaceWidth.coerceIn(0, 0xFFFF),
        surfaceHeight = surfaceHeight.coerceIn(0, 0xFFFF),
        name = deviceName,
    )

    /** Called from reader threads. */
    fun onPacket(ch: Channel, p: Packet) {
        if (!isCurrent(ch)) return
        when (p.type) {
            PacketType.CONFIG -> {
                val first = !ch.configured
                ch.configured = true
                synchronized(lock) { lastPongMs = nowMs() }
                if (first) Log.i(TAG, "Connected to ${p.name} over ${ch.transport.label}, aspect ${p.aspect}")
                publish(Status(Phase.CONNECTED, ch.transport, p.name, "Connected to ${p.name} over ${ch.transport.label}", rttMs, p.aspect))
            }
            PacketType.PONG -> {
                val rtt = nowMs() - p.echoTimestamp
                synchronized(lock) {
                    lastPongMs = nowMs()
                    rttMs = rtt
                }
                if (ch.configured) publish(status.copy(rttMs = rtt))
            }
            PacketType.BYE -> {
                val why = when (p.reason) {
                    ByeReason.BAD_CODE -> "Wrong pairing code. Check the code in Slate on the PC."
                    ByeReason.BUSY -> "The PC is already connected to another tablet."
                    ByeReason.VERSION_MISMATCH -> "The PC app is a different version. Update Slate on both devices."
                    else -> "The PC ended the session."
                }
                onChannelClosed(ch, why)
            }
        }
    }

    /** Called when a channel dies or gives up. Only matters if it's the current one. */
    fun onChannelClosed(ch: Channel, why: String) {
        val wasCurrent: Boolean
        synchronized(lock) {
            wasCurrent = current === ch
            if (wasCurrent) {
                current = null
                lastPen = null
            }
        }
        ch.close()
        if (wasCurrent) {
            Log.i(TAG, "Disconnected: $why")
            publish(Status(Phase.IDLE, message = why))
        }
    }

    /** Disconnect on the user's request. */
    fun disconnect() {
        val ch = synchronized(lock) { current } ?: return
        ch.send(Packet(PacketType.BYE, reason = ByeReason.NORMAL))
        onChannelClosed(ch, "Disconnected")
    }

    /** Sends one pen state. Pen-up and leave are repeated on lossy (UDP) links. */
    fun sendPen(p: Packet) {
        synchronized(lock) {
            val ch = current?.takeIf { it.configured } ?: return
            val prev = lastPen
            ch.send(p)
            lastPen = if (p.inRange) p else null
            lastPenSentMs = nowMs()
            if (ch.lossy && prev != null && prev.contact && !p.contact) {
                repeat(2) { ch.send(p) }
            }
        }
    }

    fun sendLeave() {
        synchronized(lock) {
            val ch = current?.takeIf { it.configured } ?: return
            lastPen = null
            repeat(if (ch.lossy) 3 else 1) { ch.send(Packet(PacketType.LEAVE)) }
        }
    }

    private fun tick() {
        try {
            val now = nowMs()
            var silent: Channel? = null
            synchronized(lock) {
                val ch = current ?: return
                // Keep the PC's 1 s watchdog fed while the pen sits still in range.
                val pen = lastPen
                if (ch.configured && pen != null && now - lastPenSentMs >= REFRESH_PEN_MS) {
                    ch.send(pen)
                    lastPenSentMs = now
                }
                if (ch.configured && now - lastPongMs > PC_SILENT_MS) silent = ch
            }
            silent?.let {
                onChannelClosed(it, "The PC stopped answering.")
                return
            }
            if (now - lastPingMs >= PING_EVERY_MS) {
                lastPingMs = now
                ping()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
        }
    }

    private fun ping() {
        synchronized(lock) {
            val ch = current?.takeIf { it.configured } ?: return
            ch.send(Packet(PacketType.PING, lastRttMs = if (rttMs in 0 until Protocol.NO_RTT) rttMs else Protocol.NO_RTT))
        }
    }

    /** Builds a PEN packet; kept here so every sender uses the same flag rules. */
    fun penPacket(tool: Int, contact: Boolean, inRange: Boolean, barrel: Boolean, hasTilt: Boolean,
                  pressure: Int, x: Float, y: Float, tiltX: Int, tiltY: Int): Packet {
        var flags = 0
        if (contact) flags = flags or PenFlags.CONTACT or PenFlags.IN_RANGE
        if (inRange) flags = flags or PenFlags.IN_RANGE
        if (barrel) flags = flags or PenFlags.BARREL
        if (hasTilt) flags = flags or PenFlags.HAS_TILT
        return Packet(
            PacketType.PEN, tool = tool, flags = flags,
            pressure = if (contact) pressure.coerceIn(0, Protocol.MAX_PRESSURE) else 0,
            x = x, y = y, tiltX = tiltX, tiltY = tiltY,
        )
    }
}
