package app.slate.tablet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.slate.tablet.R
import app.slate.tablet.input.AreaLayout
import app.slate.tablet.input.PenCapture
import app.slate.tablet.link.SlateLink

/** The blank drawing surface: letterboxes the active area to the PC's aspect ratio and outlines it. */
class CanvasView(context: Context, private val prefs: Prefs) : View(context) {
    private val activeArea = RectF()
    val capture = PenCapture(this) { activeArea }

    private val density = resources.displayMetrics.density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val maskPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * density }
    private val bigTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f * density
        textAlign = Paint.Align.CENTER
    }

    var status: SlateLink.Status = SlateLink.status
        set(value) {
            val aspectChanged = value.aspect != field.aspect
            field = value
            if (aspectChanged) {
                // The mapping moved under the pen: lift it rather than jump mid-stroke.
                capture.release()
                layoutArea()
            }
            invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutArea()
        // Keep edge-swipe system gestures from stealing strokes that start near the sides.
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    /** Re-reads the area size setting, e.g. after coming back from the settings screen. */
    fun refreshArea() {
        layoutArea()
        invalidate()
    }

    private fun layoutArea() {
        val b = AreaLayout.bounds(width.toFloat(), height.toFloat(), status.aspect, prefs.areaScale)
        activeArea.set(b[0], b[1], b[2], b[3])
    }

    override fun onDraw(canvas: Canvas) {
        val dark = prefs.darkCanvas
        val bg = if (dark) Color.rgb(18, 18, 18) else Color.rgb(245, 245, 245)
        val fg = if (dark) Color.rgb(170, 170, 170) else Color.rgb(90, 90, 90)
        canvas.drawColor(bg)

        if (prefs.showOutline && (status.aspect > 0f || prefs.areaScale < 1f)) {
            // Dim the letterbox bands so the live area is obvious.
            maskPaint.color = if (dark) Color.rgb(8, 8, 8) else Color.rgb(225, 225, 225)
            canvas.drawRect(0f, 0f, width.toFloat(), activeArea.top, maskPaint)
            canvas.drawRect(0f, activeArea.bottom, width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(0f, activeArea.top, activeArea.left, activeArea.bottom, maskPaint)
            canvas.drawRect(activeArea.right, activeArea.top, width.toFloat(), activeArea.bottom, maskPaint)
            outlinePaint.color = if (dark) Color.rgb(60, 60, 60) else Color.rgb(190, 190, 190)
            val inset = outlinePaint.strokeWidth / 2f
            canvas.drawRect(activeArea.left + inset, activeArea.top + inset, activeArea.right - inset, activeArea.bottom - inset, outlinePaint)
        }

        val s = status
        if (s.phase != SlateLink.Phase.CONNECTED) {
            bigTextPaint.color = fg
            val msg = s.message.ifEmpty { context.getString(R.string.canvas_waiting) }
            canvas.drawText(msg, width / 2f, height / 2f, bigTextPaint)
            canvas.drawText(context.getString(R.string.canvas_back_hint), width / 2f, height / 2f + 32f * density, textPaint.apply {
                color = fg
                textAlign = Paint.Align.CENTER
            })
        } else if (prefs.showStatus) {
            textPaint.color = fg
            textPaint.textAlign = Paint.Align.LEFT
            val rtt = if (s.rttMs >= 0) " · ${s.rttMs} ms" else ""
            canvas.drawText("${s.transport?.label} · ${s.pcName}$rtt", 12f * density, 24f * density, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // A drawing surface: there is nothing to click.
    override fun onTouchEvent(event: MotionEvent): Boolean = capture.onTouchEvent(event)

    override fun onHoverEvent(event: MotionEvent): Boolean = capture.onHoverEvent(event) || super.onHoverEvent(event)
}
