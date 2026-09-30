package app.slate.tablet.link

import android.os.SystemClock
import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.Protocol
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class Transport(val label: String) { USB("USB"), WIFI("Wi-Fi") }

fun nowMs(): Int = SystemClock.uptimeMillis().toInt()

/**
 * One connection to a PC. Sending never blocks the caller: packets are stamped with this
 * connection's sequence number and queued for a writer thread.
 */
abstract class Channel(val transport: Transport, val description: String) : Closeable {
    private var seq = 0
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    /** True once the PC accepted us with CONFIG. */
    @Volatile var configured = false

    /** UDP: state packets (pen-up, LEAVE) are sent several times since one lost datagram would leave a stroke stuck. */
    abstract val lossy: Boolean

    val isClosed: Boolean get() = closed.get()

    private var writer: Thread? = null

    private fun writeLoop() {
        try {
            // Runs until the empty sentinel from close(), so everything queued before it is flushed.
            while (true) {
                val batch = ArrayList<ByteArray>(8).apply { add(queue.take()) }
                queue.drainTo(batch)
                val end = batch.indexOfFirst { it.isEmpty() }
                write(if (end < 0) batch else batch.subList(0, end))
                if (end >= 0) break
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            SlateLink.onChannelClosed(this, "Connection lost")
        }
    }

    /** Stamps and queues a packet. Synchronized so seq order always equals queue order. */
    @Synchronized
    fun send(p: Packet) {
        if (closed.get()) return
        if (writer == null) {
            // Started on first use rather than in the constructor, so subclasses are fully built.
            writer = thread(name = "slate-${transport.name.lowercase()}-writer", isDaemon = true) { writeLoop() }
        }
        queue.put(p.copy(seq = seq++, timestamp = nowMs()).encode())
    }

    protected abstract fun write(batch: List<ByteArray>)
    protected abstract fun closeSocket()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.put(ByteArray(0)) // wake the writer so it exits
        // Let the writer flush a final BYE / pen-up before the socket goes.
        val w = synchronized(this) { writer }
        if (w != null && w !== Thread.currentThread()) w.join(300)
        closeSocket()
    }
}

class TcpChannel(private val socket: Socket, description: String) : Channel(Transport.USB, description) {
    private val out = BufferedOutputStream(socket.getOutputStream(), 4096)
    override val lossy = false

    override fun write(batch: List<ByteArray>) {
        for (b in batch) out.write(b)
        out.flush()
    }

    /** Blocks reading packets until the socket closes. */
    fun readLoop(onPacket: (Packet) -> Unit) {
        val input = DataInputStream(socket.getInputStream())
        val buf = ByteArray(Protocol.PACKET_SIZE)
        while (!isClosed) {
            input.readFully(buf)
            Packet.decode(buf)?.let(onPacket)
        }
    }

    override fun closeSocket() {
        try { socket.close() } catch (_: IOException) {}
    }
}

class UdpChannel(private val socket: DatagramSocket, description: String) : Channel(Transport.WIFI, description) {
    override val lossy = true

    override fun write(batch: List<ByteArray>) {
        for (b in batch) socket.send(DatagramPacket(b, b.size))
    }

    /**
     * Blocks reading packets until the socket closes. [onIdle] runs whenever nothing arrived for
     * [idleMs]; returning false ends the loop.
     */
    fun readLoop(idleMs: Int, onIdle: () -> Boolean, onPacket: (Packet) -> Unit) {
        val buf = ByteArray(512)
        val dp = DatagramPacket(buf, buf.size)
        socket.soTimeout = idleMs
        while (!isClosed) {
            try {
                dp.setLength(buf.size)
                socket.receive(dp)
                Packet.decode(buf, 0, dp.length)?.let(onPacket)
            } catch (_: SocketTimeoutException) {
                if (!onIdle()) return
            }
        }
    }

    override fun closeSocket() {
        socket.close()
    }
}
