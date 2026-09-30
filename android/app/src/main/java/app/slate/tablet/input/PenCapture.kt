package app.slate.tablet.input

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import app.slate.tablet.link.SlateLink
import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.PenFlags
import app.slate.tablet.protocol.PenTool
import app.slate.tablet.protocol.Protocol
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns stylus MotionEvents into PEN/LEAVE packets. Only TOOL_TYPE_STYLUS and TOOL_TYPE_ERASER
 * pointers count; fingers and palms are swallowed. Every historical sample is sent, not just the
 * latest, so fast strokes keep their shape.
 */
class PenCapture(private val view: View, private val activeArea: () -> RectF) {
    private val handler = Handler(Looper.getMainLooper())

    // Milestone 2 check: `adb shell setprop log.tag.SlatePen VERBOSE`, then `adb logcat -s SlatePen`.
    private val verbose = Log.isLoggable(TAG, Log.VERBOSE)
    private val tiltSupport = HashMap<Int, Boolean>()
    private var last: Packet? = null

    // A stroke only draws if the pen touched down inside the active area, like the edge of a real
    // tablet's sensor. Once down it keeps drawing (clamped at the edge) so strokes don't break.
    private var strokeInside = false

    // Android sends HOVER_EXIT just before ACTION_DOWN and nothing at all when the pen is lifted out
    // of range from a touch, so "left range" is only decided after a short quiet period.
    private val leave = Runnable { leaveNow() }

    fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = stylusIndex(e)
        if (idx < 0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> penDown(e, idx)
            MotionEvent.ACTION_POINTER_DOWN -> if (e.actionIndex == idx) penDown(e, idx) else emitAll(e, idx, contact = true)
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> emitAll(e, idx, contact = true)
            MotionEvent.ACTION_UP -> lift(e, idx)
            MotionEvent.ACTION_POINTER_UP -> if (e.actionIndex == idx) lift(e, idx) else emitAll(e, idx, contact = true)
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    fun onHoverEvent(e: MotionEvent): Boolean {
        val idx = stylusIndex(e)
        if (idx < 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                handler.removeCallbacks(leave)
                emitAll(e, idx, contact = false)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                handler.removeCallbacks(leave)
                handler.postDelayed(leave, HOVER_EXIT_GRACE_MS)
            }
        }
        return true
    }

    /** Lift the pen and take it out of range, e.g. when the canvas loses focus. */
    fun release() {
        handler.removeCallbacks(leave)
        strokeInside = false
        last?.let { if (it.contact) send(it.copy(flags = it.flags and PenFlags.CONTACT.inv(), pressure = 0)) }
        leaveNow()
    }

    private fun penDown(e: MotionEvent, idx: Int) {
        view.requestUnbufferedDispatch(e)
        handler.removeCallbacks(leave)
        strokeInside = activeArea().contains(e.getX(idx), e.getY(idx))
        emitAll(e, idx, contact = true)
    }

    private fun lift(e: MotionEvent, idx: Int) {
        strokeInside = false
        emit(e, idx, -1, touching = false)
        handler.removeCallbacks(leave)
        handler.postDelayed(leave, LIFT_GRACE_MS)
    }

    private fun emitAll(e: MotionEvent, idx: Int, contact: Boolean) {
        for (h in 0 until e.historySize) emit(e, idx, h, contact)
        emit(e, idx, -1, contact)
    }

    /** [h] is a history index, or -1 for the current sample. */
    private fun emit(e: MotionEvent, idx: Int, h: Int, touching: Boolean) {
        val contact = touching && strokeInside
        fun axis(a: Int) = if (h < 0) e.getAxisValue(a, idx) else e.getHistoricalAxisValue(a, idx, h)
        val x = if (h < 0) e.getX(idx) else e.getHistoricalX(idx, h)
        val y = if (h < 0) e.getY(idx) else e.getHistoricalY(idx, h)
        val pressure = if (h < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, h)

        val hasTilt = tiltSupport.getOrPut(e.deviceId) { e.device?.getMotionRange(MotionEvent.AXIS_TILT) != null }
        val (tiltX, tiltY) = if (hasTilt) tiltXY(axis(MotionEvent.AXIS_TILT), axis(MotionEvent.AXIS_ORIENTATION)) else 0 to 0
        val area = activeArea()

        send(SlateLink.penPacket(
            tool = if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) PenTool.ERASER else PenTool.PEN,
            contact = contact,
            inRange = true,
            barrel = e.buttonState and STYLUS_BUTTONS != 0,
            hasTilt = hasTilt,
            pressure = if (contact) pressureToWire(pressure) else 0,
            x = normalize(x, area.left, area.width()),
            y = normalize(y, area.top, area.height()),
            tiltX = tiltX,
            tiltY = tiltY,
        ))
    }

    private fun send(p: Packet) {
        last = p
        if (verbose) {
            Log.v(TAG, "tool=${p.tool} contact=${p.contact} inRange=${p.inRange} barrel=${p.flags and PenFlags.BARREL != 0} " +
                "x=%.4f y=%.4f pressure=${p.pressure} tilt=${p.tiltX},${p.tiltY}".format(p.x, p.y))
        }
        SlateLink.sendPen(p)
    }

    private fun leaveNow() {
        if (last == null) return
        last = null
        if (verbose) Log.v(TAG, "leave")
        SlateLink.sendLeave()
    }

    companion object {
        private const val TAG = "SlatePen"
        const val HOVER_EXIT_GRACE_MS = 100L
        const val LIFT_GRACE_MS = 150L
        private const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

        fun stylusIndex(e: MotionEvent): Int {
            for (i in 0 until e.pointerCount) {
                val t = e.getToolType(i)
                if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
            }
            return -1
        }

        /** Position within the active area as 0..1. */
        fun normalize(v: Float, start: Float, size: Float): Float =
            if (size <= 0f) 0f else ((v - start) / size).coerceIn(0f, 1f)

        /** Android pressure (nominally 0..1) to wire pressure 1..1024; a touching pen always has some pressure. */
        fun pressureToWire(p: Float): Int =
            if (p.isNaN()) 1 else (p * Protocol.MAX_PRESSURE).roundToInt().coerceIn(1, Protocol.MAX_PRESSURE)

        /**
         * Android's AXIS_TILT (0..π/2 from perpendicular) and AXIS_ORIENTATION (−π..π; 0 = the pen
         * points toward the top of the screen, so its top end leans toward the user; π/2 = points
         * right) to Windows/W3C tiltX/tiltY in degrees (+X: top end leans right, +Y: toward the user).
         * Same conversion Chromium uses for Pointer Events.
         */
        fun tiltXY(tiltRad: Float, orientationRad: Float): Pair<Int, Int> {
            // Float π/2 is slightly more than π/2; clamp so a flat pen doesn't flip to the other side.
            val tilt = tiltRad.toDouble().coerceIn(0.0, PI / 2)
            val r = sin(tilt)
            val z = cos(tilt).coerceAtLeast(0.0)
            val tx = Math.toDegrees(atan2(sin(-orientationRad.toDouble()) * r, z))
            val ty = Math.toDegrees(atan2(cos(-orientationRad.toDouble()) * r, z))
            return tx.roundToInt().coerceIn(-90, 90) to ty.roundToInt().coerceIn(-90, 90)
        }
    }
}
