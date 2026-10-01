package app.slate.tablet.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.slate.tablet.link.BulkLink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Hardware H.264 decoding straight onto a Surface, tuned for latency: low-latency mode, a
 * display-priority output thread, only the newest decoded frame shown (older ones that piled up are
 * dropped), and anything that goes wrong just waits for the next keyframe (one every second).
 */
class VideoDecoder(private val onSize: (Int, Int) -> Unit) : BulkLink.VideoSink {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var output: Thread? = null
    @Volatile private var running = false
    private var width = 0
    private var height = 0
    private var needKeyframe = true

    /** Counters for the stats overlay (read and reset once a second). */
    class Stats {
        val received = AtomicLong()
        val shown = AtomicLong()
        val late = AtomicLong()      // decoded but replaced by a newer frame before it could be shown
        val skipped = AtomicLong()   // thrown away waiting for a keyframe
        /** Sum and count of (time shown − frame timestamp), in tablet-clock µs minus PC-clock µs. */
        val delaySumUs = AtomicLong()
        val delayCount = AtomicLong()
        @Volatile var decoderName = ""
    }

    val stats = Stats()

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            releaseCodec()
            surface = s
            needKeyframe = true
        }
    }

    override fun onVideo(accessUnit: ByteArray, offset: Int, length: Int, keyframe: Boolean, ptsUs: Long) {
        synchronized(lock) {
            val s = surface ?: return
            if (keyframe) {
                // A new SPS may mean a new size (another monitor): reconfigure when it changes.
                val size = spsSize(accessUnit, offset, length)
                if (size != null && (codec == null || size != width to height)) {
                    releaseCodec()
                    if (!configure(s, accessUnit, offset, length, size)) return
                }
            }
            val c = codec ?: return
            stats.received.incrementAndGet()
            if (needKeyframe && !keyframe) {
                stats.skipped.incrementAndGet()
                return
            }
            try {
                val idx = c.dequeueInputBuffer(20_000)
                if (idx < 0) {
                    needKeyframe = true // decoder is behind: skip ahead to the next keyframe rather than smear
                    stats.skipped.incrementAndGet()
                    return
                }
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(accessUnit, offset, length)
                c.queueInputBuffer(idx, 0, length, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                needKeyframe = false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Decoder error, waiting for the next keyframe", e)
                releaseCodec()
            }
        }
    }

    private fun spsSize(data: ByteArray, offset: Int, length: Int): Pair<Int, Int>? {
        val sps = H264.nalUnits(data, offset, length).firstOrNull { H264.type(data, it.first) == H264.NAL_SPS } ?: return null
        return try {
            H264.spsSize(data.copyOfRange(sps.first, sps.first + sps.second))
        } catch (e: Exception) {
            null
        }
    }

    private fun configure(s: Surface, data: ByteArray, offset: Int, length: Int, size: Pair<Int, Int>): Boolean {
        val nals = H264.nalUnits(data, offset, length)
        val sps = nals.firstOrNull { H264.type(data, it.first) == H264.NAL_SPS } ?: return false
        val pps = nals.firstOrNull { H264.type(data, it.first) == H264.NAL_PPS } ?: return false
        val (w, h) = size
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(H264.withStartCode(data, sps.first, sps.second)))
                setByteBuffer("csd-1", ByteBuffer.wrap(H264.withStartCode(data, pps.first, pps.second)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                // Vendor low-latency switches (ignored by decoders that don't know them).
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            width = w
            height = h
            needKeyframe = true
            running = true
            output = thread(name = "slate-video-out", isDaemon = true) { drain(c) }
            main.post { onSize(w, h) }
            stats.decoderName = c.name
            Log.i(TAG, "Decoding ${w}x$h with ${c.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start the video decoder", e)
            false
        }
    }

    private fun drain(c: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                var idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx < 0) continue
                var pts = info.presentationTimeUs
                // If more frames are already waiting, skip straight to the newest one.
                while (true) {
                    val next = c.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    c.releaseOutputBuffer(idx, false)
                    stats.late.incrementAndGet()
                    idx = next
                    pts = info.presentationTimeUs
                }
                c.releaseOutputBuffer(idx, System.nanoTime()) // show it now; no frame pacing
                stats.shown.incrementAndGet()
                stats.delaySumUs.addAndGet(SystemClock.elapsedRealtimeNanos() / 1000 - pts)
                stats.delayCount.incrementAndGet()
            }
        } catch (e: IllegalStateException) {
            // Released while waiting.
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        running = false
        output?.join(200)
        output = null
        try {
            c.stop()
        } catch (_: IllegalStateException) {
        }
        c.release()
        width = 0
        height = 0
    }

    fun release() = setSurface(null)

    private companion object {
        const val TAG = "SlateVideo"
    }
}
