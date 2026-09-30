package app.slate.tablet.link

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * The tablet end of the bulk channel (PROTOCOL.md "Bulk channel"): TCP to the PC's port 47813,
 * through `adb reverse` on USB or directly on Wi-Fi. Carries video, screenshots, control messages
 * and Claude replies. Connects automatically whenever [SlateLink] has a PC.
 */
object BulkLink {
    private const val TAG = "SlateBulk"
    const val PORT = 47813

    const val KIND_HELLO = 1
    const val KIND_JSON = 2
    const val KIND_VIDEO = 3
    const val KIND_BLOB = 4

    /** Receives video frames on the reader thread (low latency: no main-thread hop). */
    interface VideoSink {
        fun onVideo(accessUnit: ByteArray, offset: Int, length: Int, keyframe: Boolean, ptsUs: Long)
    }

    @Volatile var videoSink: VideoSink? = null

    private val main = Handler(Looper.getMainLooper())
    private val jsonListeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    private val blobListeners = CopyOnWriteArraySet<(JSONObject, ByteArray) -> Unit>()
    private val stateListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile private var socket: Socket? = null
    @Volatile private var queue: LinkedBlockingQueue<ByteArray>? = null
    @Volatile var connected = false
        private set
    @Volatile private var started = false

    /** The last "welcome" message (monitors etc.), for screens that open later. */
    @Volatile var welcome: JSONObject? = null
        private set

    fun start() {
        if (started) return
        started = true
        thread(name = "slate-bulk-connect", isDaemon = true) { connectLoop() }
    }

    fun addJsonListener(l: (JSONObject) -> Unit) = jsonListeners.add(l)
    fun removeJsonListener(l: (JSONObject) -> Unit) = jsonListeners.remove(l)
    fun addBlobListener(l: (JSONObject, ByteArray) -> Unit) = blobListeners.add(l)
    fun removeBlobListener(l: (JSONObject, ByteArray) -> Unit) = blobListeners.remove(l)

    /** Listener gets the current state immediately (main thread) and every change. */
    fun addStateListener(l: (Boolean) -> Unit) {
        stateListeners.add(l)
        main.post { l(connected) }
    }

    fun removeStateListener(l: (Boolean) -> Unit) = stateListeners.remove(l)

    /** Sends a JSON control message; returns false if not connected. */
    fun send(message: JSONObject): Boolean = enqueue(frame(KIND_JSON, message.toString().toByteArray(Charsets.UTF_8)))

    /** Sends a JSON header with binary data (e.g. an image). */
    fun sendBlob(header: JSONObject, data: ByteArray): Boolean {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        val body = ByteBuffer.allocate(4 + h.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(h.size).put(h).put(data)
        return enqueue(frame(KIND_BLOB, body.array()))
    }

    private fun enqueue(bytes: ByteArray): Boolean {
        val q = queue ?: return false
        if (!connected) return false
        q.put(bytes)
        return true
    }

    private fun frame(kind: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(5 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(body.size + 1).put(kind.toByte()).put(body)
        return b.array()
    }

    private fun connectLoop() {
        while (true) {
            val status = SlateLink.status
            val target = SlateLink.bulkTarget()
            if (status.phase == SlateLink.Phase.CONNECTED && target != null) {
                try {
                    session(target.first, target.second)
                } catch (e: IOException) {
                    Log.i(TAG, "Bulk channel closed: ${e.message}")
                }
                setConnected(false)
            }
            Thread.sleep(1500)
        }
    }

    private fun session(address: InetAddress, pairCode: Int) {
        val s = Socket()
        s.tcpNoDelay = true
        s.receiveBufferSize = 1 shl 20
        s.connect(InetSocketAddress(address, PORT), 3000)
        socket = s
        val q = LinkedBlockingQueue<ByteArray>()
        queue = q
        val out = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
        val hello = JSONObject().put("code", pairCode).put("device", SlateLink.deviceName)
        out.write(frame(KIND_HELLO, hello.toString().toByteArray(Charsets.UTF_8)))
        out.flush()

        val writer = thread(name = "slate-bulk-write", isDaemon = true) {
            try {
                while (true) {
                    val first = q.take()
                    if (first.isEmpty()) break
                    out.write(first)
                    while (true) out.write(q.poll() ?: break)
                    out.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            try { s.close() } catch (_: IOException) {}
        }
        setConnected(true)
        try {
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val head = ByteArray(5)
            while (true) {
                input.readFully(head)
                val len = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 1 || len > 64 * 1024 * 1024) throw IOException("bad frame length $len")
                val body = ByteArray(len - 1)
                input.readFully(body)
                dispatch(head[4].toInt() and 0xFF, body)
                // Stop if SlateLink moved to another PC or disconnected.
                if (SlateLink.status.phase != SlateLink.Phase.CONNECTED) break
            }
        } finally {
            q.put(ByteArray(0))
            try { s.close() } catch (_: IOException) {}
            writer.join(500)
            queue = null
            socket = null
        }
    }

    private fun dispatch(kind: Int, body: ByteArray) {
        when (kind) {
            KIND_VIDEO -> if (body.size > 9) {
                val pts = ByteBuffer.wrap(body, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
                videoSink?.onVideo(body, 9, body.size - 9, body[0].toInt() and 1 != 0, pts)
            }
            KIND_JSON -> {
                val o = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { return }
                if (o.optString("t") == "welcome") welcome = o
                if (o.optString("t") == "denied") Log.w(TAG, "PC refused the bulk channel: ${o.optString("message")}")
                main.post { jsonListeners.forEach { it(o) } }
            }
            KIND_BLOB -> {
                if (body.size < 4) return
                val hl = ByteBuffer.wrap(body, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (hl < 0 || hl > body.size - 4) return
                val header = try { JSONObject(String(body, 4, hl, Charsets.UTF_8)) } catch (e: Exception) { return }
                val data = body.copyOfRange(4 + hl, body.size)
                main.post { blobListeners.forEach { it(header, data) } }
            }
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (!value) welcome = null
        main.post { stateListeners.forEach { it(value) } }
    }

    /** Drop the current connection (e.g. the PC changed); it reconnects by itself. */
    fun reconnect() {
        try { socket?.close() } catch (_: IOException) {}
    }
}
