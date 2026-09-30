package app.slate.tablet.write

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import app.slate.tablet.input.PenCapture

/**
 * Write mode surface: the S Pen writes ink that stays on the tablet. The eraser end, or holding
 * the S Pen button, erases whole strokes. Fingers and palms are ignored.
 */
@SuppressLint("ViewConstructor")
class WriteView(context: Context, val ink: Ink, private val dark: () -> Boolean) : View(context) {
    var controller: WriteController? = null

    private val density = resources.displayMetrics.density
    private val eraseRadius = 14f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f * density
    }
    private val path = Path()
    private var erasing = false

    override fun onDraw(canvas: Canvas) {
        val isDark = dark()
        canvas.drawColor(if (isDark) Color.rgb(18, 18, 18) else Color.rgb(250, 250, 250))
        paint.color = if (isDark) Color.rgb(235, 235, 235) else Color.rgb(20, 20, 20)
        for (s in ink.strokes) {
            if (s.pointCount == 1) {
                canvas.drawPoint(s.x(0), s.y(0), paint)
                continue
            }
            path.rewind()
            path.moveTo(s.x(0), s.y(0))
            for (i in 1 until s.pointCount) path.lineTo(s.x(i), s.y(i))
            canvas.drawPath(path, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // A writing surface: there is nothing to click.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = PenCapture.stylusIndex(e)
        if (idx < 0) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.actionIndex != idx) return true
                requestUnbufferedDispatch(e)
                erasing = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
                    e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                controller?.onStrokeStarted()
                if (erasing) erase(e, idx) else ink.begin(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    erase(e, idx)
                } else {
                    for (h in 0 until e.historySize) ink.extend(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    ink.extend(e.getX(idx), e.getY(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.actionIndex != idx) return true
                if (!erasing) ink.end()
                erasing = false
                controller?.onInkChanged()
            }
        }
        invalidate()
        return true
    }

    private fun erase(e: MotionEvent, idx: Int) {
        for (h in 0 until e.historySize) ink.eraseNear(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), eraseRadius)
        ink.eraseNear(e.getX(idx), e.getY(idx), eraseRadius)
    }
}
