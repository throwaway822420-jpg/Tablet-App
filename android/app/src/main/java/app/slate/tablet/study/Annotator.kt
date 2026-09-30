package app.slate.tablet.study

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import app.slate.tablet.input.PenCapture
import app.slate.tablet.mirror.Viewport
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

/**
 * A screenshot to draw on. The S Pen draws (pen colours or highlighter); the eraser end or the
 * S Pen button erases whole strokes; two fingers zoom and pan for precise marking. Strokes are kept
 * in screenshot pixels so the result is exported at full resolution.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Annotator(context: Context, val image: Bitmap) : View(context) {
    class Mark(var points: FloatArray, var size: Int, val color: Int, val width: Float, val highlighter: Boolean)

    var color = Color.rgb(230, 40, 40)
    var highlighter = false
    var erasing = false

    val marks = ArrayList<Mark>()
    private val viewport = Viewport().apply { setFrame(image.width.toFloat(), image.height.toFloat()) }
    private var current: Mark? = null
    private var penErasing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val m = Matrix()

    /** Pen width in screenshot pixels: about 3 dp on screen at "fit". */
    private fun baseWidth(): Float {
        val d = viewport.displayed()
        return 3f * resources.displayMetrics.density * image.width / (d[2] - d[0]) * viewport.scale
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewport.setView(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(30, 30, 30))
        val d = viewport.displayed()
        m.setScale((d[2] - d[0]) / image.width, (d[3] - d[1]) / image.height)
        m.postTranslate(d[0], d[1])
        canvas.save()
        canvas.concat(m)
        canvas.drawBitmap(image, 0f, 0f, paint)
        for (mk in marks) drawMark(canvas, mk)
        canvas.restore()
    }

    private fun drawMark(canvas: Canvas, mk: Mark) {
        paint.color = mk.color
        paint.strokeWidth = mk.width
        paint.alpha = if (mk.highlighter) 90 else 255
        paint.strokeCap = if (mk.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        path.rewind()
        path.moveTo(mk.points[0], mk.points[1])
        for (i in 1 until mk.size / 2) path.lineTo(mk.points[i * 2], mk.points[i * 2 + 1])
        if (mk.size == 2) path.lineTo(mk.points[0] + 0.1f, mk.points[1])
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    // --- Input ---

    private var lastSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = PenCapture.stylusIndex(e)
        if (idx >= 0) return pen(e, idx)
        // Fingers: two to zoom/pan; one finger does nothing (palm rejection).
        if (e.pointerCount >= 2) {
            val cx = (e.getX(0) + e.getX(1)) / 2
            val cy = (e.getY(0) + e.getY(1)) / 2
            val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
            if (e.actionMasked == MotionEvent.ACTION_MOVE && lastSpan > 0) {
                viewport.zoomAt(span / lastSpan, cx, cy)
                viewport.panBy(cx - lastCx, cy - lastCy)
                invalidate()
            }
            lastSpan = span; lastCx = cx; lastCy = cy
        } else {
            lastSpan = 0f
        }
        return true
    }

    private fun pen(e: MotionEvent, idx: Int): Boolean {
        val (fx, fy) = viewport.viewToFrame(e.getX(idx), e.getY(idx))
        val x = fx * image.width
        val y = fy * image.height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestUnbufferedDispatch(e)
                penErasing = erasing || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
                    e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                if (penErasing) eraseAt(x, y) else {
                    val w = baseWidth() * if (highlighter) 6f else 1f
                    current = Mark(FloatArray(256), 0, if (highlighter) Color.rgb(255, 225, 0) else color, w, highlighter).also {
                        add(it, x, y)
                        marks.add(it)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val (hx, hy) = viewport.viewToFrame(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    if (penErasing) eraseAt(hx * image.width, hy * image.height) else current?.let { add(it, hx * image.width, hy * image.height) }
                }
                if (penErasing) eraseAt(x, y) else current?.let { add(it, x, y) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                penErasing = false
            }
        }
        invalidate()
        return true
    }

    private fun add(mk: Mark, x: Float, y: Float) {
        if (mk.size + 2 > mk.points.size) mk.points = mk.points.copyOf(mk.points.size * 2)
        mk.points[mk.size++] = x
        mk.points[mk.size++] = y
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = baseWidth() * 5
        marks.removeAll { mk -> (0 until mk.size / 2).any { i -> hypot(mk.points[i * 2] - x, mk.points[i * 2 + 1] - y) < r + mk.width / 2 } }
    }

    fun undo() {
        if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
        invalidate()
    }

    fun clear() {
        marks.clear()
        invalidate()
    }

    // --- Export ---

    /** The screenshot with annotations, at full resolution, as JPEG. */
    fun composite(): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        for (mk in marks) drawMark(c, mk)
        return out
    }

    /** Zoomed crop of whatever was circled (from the annotated image), or null. */
    fun circledCrop(annotated: Bitmap): Bitmap? {
        val rect = Loops.cropRect(marks.map { it.points.copyOf(it.size) }, image.width, image.height) ?: return null
        val l = rect[0].toInt(); val t = rect[1].toInt()
        return Bitmap.createBitmap(annotated, l, t, (rect[2].toInt() - l).coerceAtLeast(1), (rect[3].toInt() - t).coerceAtLeast(1))
    }

    companion object {
        fun jpeg(b: Bitmap, quality: Int = 88): ByteArray =
            ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        /** Keeps images within what Claude reads at full detail (long side ≤ 2576 px) to save upload and tokens. */
        fun limit(b: Bitmap, maxSide: Int = 2576): Bitmap {
            val longest = maxOf(b.width, b.height)
            if (longest <= maxSide) return b
            val s = maxSide.toFloat() / longest
            return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        }
    }
}
