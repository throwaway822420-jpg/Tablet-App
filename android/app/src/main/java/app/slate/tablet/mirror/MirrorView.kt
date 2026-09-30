package app.slate.tablet.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.slate.tablet.input.PenCapture
import app.slate.tablet.link.BulkLink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The mirrored PC screen. The S Pen is a real pen on the PC, landing exactly where it touches the
 * picture at any zoom. Fingers either navigate (tap = click, long-press = right-click, drag =
 * drag, two fingers = scroll or pinch-zoom the view, three-finger swipes switch windows) or are
 * sent to Windows as real touch. Fingers are ignored while the pen is near (palm rejection).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MirrorView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    enum class FingerMode { NAVIGATE, TOUCH }

    val viewport = Viewport()
    val decoder = VideoDecoder { w, h -> viewport.setFrame(w.toFloat(), h.toFloat()); applyTransform() }
    private val texture = TextureView(context)
    private val overlay = Overlay(context)
    val capture = PenCapture(this) { displayedRect() }

    var fingerMode = FingerMode.NAVIGATE
    var followCursor = true
    var showMinimap = true

    /** Called when the user changes zoom/pan, e.g. to update toolbar state. */
    var onViewportChanged: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private var penNearUntil = 0L

    init {
        setBackgroundColor(Color.BLACK)
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** The current picture as shown (for freezing when the PC can't send a screenshot). */
    fun snapshot(): android.graphics.Bitmap? = texture.bitmap

    fun displayedRect(): RectF = viewport.displayed().let { RectF(it[0], it[1], it[2], it[3]) }

    // --- Surface / transform ---

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        decoder.setSurface(Surface(st))
        BulkLink.videoSink = decoder
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyTransform()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        if (BulkLink.videoSink === decoder) BulkLink.videoSink = null
        decoder.release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        val now = SystemClock.uptimeMillis()
        // Refresh the mini-map thumbnail: often while zoomed, occasionally otherwise (so it's ready).
        if (showMinimap && now - lastMinimapMs > if (viewport.isZoomedIn) 400 else 1500) {
            lastMinimapMs = now
            overlay.updateMinimap()
        }
    }

    private var lastMinimapMs = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setView(w.toFloat(), h.toFloat())
        applyTransform()
    }

    fun applyTransform() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val d = viewport.displayed()
        val m = Matrix()
        m.setScale((d[2] - d[0]) / w, (d[3] - d[1]) / h)
        m.postTranslate(d[0], d[1])
        texture.setTransform(m)
        texture.invalidate()
        overlay.invalidate()
        onViewportChanged?.invoke()
    }

    /** Called with the PC cursor position (0..1) while streaming. */
    fun onCursor(nx: Float, ny: Float) {
        if (followCursor && viewport.follow(nx, ny)) applyTransform()
    }

    // --- Input routing ---

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // Children (none interactive) never get touches; the mirror handles everything itself.
        if (PenCapture.stylusIndex(e) >= 0) {
            penNearUntil = SystemClock.uptimeMillis() + 400
            cancelFingers()
            return capture.onTouchEvent(e)
        }
        if (SystemClock.uptimeMillis() < penNearUntil) return true // palm while writing
        if (overlay.handleMinimap(e)) return true
        return if (fingerMode == FingerMode.TOUCH) sendTouch(e) else navigate(e)
    }

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        if (PenCapture.stylusIndex(e) >= 0) penNearUntil = SystemClock.uptimeMillis() + 400
        return capture.onHoverEvent(e) || super.dispatchHoverEvent(e)
    }

    private fun frame(x: Float, y: Float): Pair<Float, Float> = viewport.viewToFrame(x, y)

    private fun send(o: JSONObject) {
        BulkLink.send(o)
    }

    private fun mouse(action: String, x: Float, y: Float) {
        val (nx, ny) = frame(x, y)
        send(JSONObject().put("t", "mouse").put("action", action).put("x", nx.toDouble()).put("y", ny.toDouble()))
    }

    // --- Navigate mode gestures ---

    private enum class Two { UNDECIDED, ZOOM, SCROLL, PAN }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var multi = false
    private var two = Two.UNDECIDED
    private var span0 = 0f
    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var maxPointers = 0
    private val longPress = Runnable {
        longPressed = true
        mouse("rightclick", downX, downY)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun cancelFingers() {
        removeCallbacks(longPress)
        if (dragging) {
            mouse("up", downX, downY)
            dragging = false
        }
    }

    private fun navigate(e: MotionEvent): Boolean {
        val n = e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false; longPressed = false; multi = false; maxPointers = 1
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelFingers()
                multi = true
                maxPointers = maxOf(maxPointers, n)
                two = Two.UNDECIDED
                span0 = span(e); lastSpan = span0
                lastCx = centroidX(e); lastCy = centroidY(e)
                startCx = lastCx; startCy = lastCy
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multi) {
                    if (!dragging && !longPressed && hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        dragging = true
                        mouse("down", downX, downY)
                    }
                    if (dragging) mouse("move", e.x, e.y)
                } else if (n == 2) {
                    twoFingers(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep two-finger state sensible when a third finger lifts; gestures end on ACTION_UP.
                if (n - 1 == 2) {
                    lastSpan = span(e); lastCx = centroidX(e); lastCy = centroidY(e)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    maxPointers >= 3 -> threeFingerSwipe(e.x - startCx, e.y - startCy)
                    dragging -> mouse("up", e.x, e.y)
                    !multi && !longPressed -> mouse("click", downX, downY)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> cancelFingers()
        }
        return true
    }

    private fun twoFingers(e: MotionEvent) {
        val s = span(e)
        val cx = centroidX(e)
        val cy = centroidY(e)
        if (two == Two.UNDECIDED) {
            two = when {
                abs(s - span0) > 40 * density -> Two.ZOOM
                hypot(cx - startCx, cy - startCy) > slop -> if (viewport.isZoomedIn) Two.PAN else Two.SCROLL
                else -> Two.UNDECIDED
            }
        }
        when (two) {
            Two.ZOOM -> {
                viewport.zoomAt(s / lastSpan, cx, cy)
                viewport.panBy(cx - lastCx, cy - lastCy)
                applyTransform()
            }
            Two.PAN -> {
                viewport.panBy(cx - lastCx, cy - lastCy)
                applyTransform()
            }
            Two.SCROLL -> {
                val (nx, ny) = frame(cx, cy)
                // Natural scrolling: fingers up = page down. ~70 px per wheel notch.
                val notch = 70 * density / 2.5f
                send(
                    JSONObject().put("t", "scroll").put("x", nx.toDouble()).put("y", ny.toDouble())
                        .put("dx", (-(cx - lastCx) / notch).toDouble()).put("dy", (-(cy - lastCy) / notch).toDouble()),
                )
            }
            Two.UNDECIDED -> {}
        }
        lastSpan = s; lastCx = cx; lastCy = cy
    }

    private fun threeFingerSwipe(dx: Float, dy: Float) {
        val min = 120 * density
        val combo = when {
            abs(dx) > abs(dy) && dx > min -> "Alt+Tab"
            abs(dx) > abs(dy) && dx < -min -> "Alt+Shift+Tab"
            dy < -min -> "Win+Tab"
            dy > min -> "Win+D"
            else -> return
        }
        send(JSONObject().put("t", "keys").put("combo", combo))
    }

    private fun span(e: MotionEvent): Float =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun centroidX(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount
    private fun centroidY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

    // --- Windows touch mode ---

    private var lastTouchSendMs = 0L

    private fun sendTouch(e: MotionEvent): Boolean {
        val action = e.actionMasked
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_MOVE && now - lastTouchSendMs < 8) return true // ~120 Hz is plenty
        lastTouchSendMs = now
        val contacts = JSONArray()
        for (i in 0 until e.pointerCount) {
            val phase = when {
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && i == e.actionIndex -> "down"
                (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == e.actionIndex -> "up"
                action == MotionEvent.ACTION_CANCEL -> "up"
                else -> "move"
            }
            val (nx, ny) = frame(e.getX(i), e.getY(i))
            contacts.put(JSONObject().put("id", e.getPointerId(i)).put("x", nx.toDouble()).put("y", ny.toDouble()).put("phase", phase))
        }
        send(JSONObject().put("t", "touch").put("contacts", contacts))
        return true
    }

    // --- Mini-map overlay ---

    private inner class Overlay(context: Context) : View(context) {
        private var thumb: Bitmap? = null
        private val box = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var minimapDrag = false

        /** Finger on the mini-map: jump/drag the view there. */
        fun handleMinimap(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                minimapDrag = showMinimap && viewport.isZoomedIn && box.contains(e.x, e.y)
            }
            if (!minimapDrag) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                viewport.centreOn(((e.x - box.left) / box.width()).coerceIn(0f, 1f), ((e.y - box.top) / box.height()).coerceIn(0f, 1f))
                applyTransform()
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) minimapDrag = false
            return true
        }

        /**
         * Copies what's on screen into the thumbnail. Only the visible part of the PC screen is on the
         * tablet, so the thumbnail fills in as you look around (and is complete whenever not zoomed).
         */
        fun updateMinimap() {
            if (width <= 0 || height <= 0) return
            val w = (180 * density).toInt()
            val h = (w * viewport.frameH / viewport.frameW).toInt().coerceAtLeast(1)
            val q = 4 // grab at quarter resolution: plenty for a thumbnail, cheap to copy
            val grab = texture.getBitmap((width / q).coerceAtLeast(1), (height / q).coerceAtLeast(1)) ?: return
            val d = viewport.displayed()
            val l = (maxOf(d[0], 0f)); val t = maxOf(d[1], 0f)
            val r = minOf(d[2], width.toFloat()); val b = minOf(d[3], height.toFloat())
            if (r > l && b > t) {
                val tb = thumb?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val (fl, ft) = viewport.viewToFrame(l, t)
                val (fr, fb) = viewport.viewToFrame(r, b)
                Canvas(tb).drawBitmap(
                    grab, android.graphics.Rect((l / q).toInt(), (t / q).toInt(), (r / q).toInt().coerceAtLeast(1), (b / q).toInt().coerceAtLeast(1)),
                    RectF(fl * w, ft * h, fr * w, fb * h), paint.apply { style = Paint.Style.FILL; isFilterBitmap = true },
                )
                thumb = tb
            }
            grab.recycle()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!showMinimap || !viewport.isZoomedIn) return
            val w = 180 * density
            val h = w * viewport.frameH / viewport.frameW
            val m = 12 * density
            box.set(width - w - m, height - h - m, width - m, height - m)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(200, 20, 20, 20)
            canvas.drawRect(box, paint)
            thumb?.let { canvas.drawBitmap(it, null, box, null) }
            val (l, t) = viewport.viewToFrame(0f, 0f)
            val (r, b) = viewport.viewToFrame(width.toFloat(), height.toFloat())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            paint.color = Color.rgb(90, 200, 140)
            canvas.drawRect(
                box.left + l.coerceIn(0f, 1f) * box.width(), box.top + t.coerceIn(0f, 1f) * box.height(),
                box.left + r.coerceIn(0f, 1f) * box.width(), box.top + b.coerceIn(0f, 1f) * box.height(), paint,
            )
            paint.color = Color.argb(160, 255, 255, 255)
            paint.strokeWidth = 1 * density
            canvas.drawRect(box, paint)
        }
    }
}
