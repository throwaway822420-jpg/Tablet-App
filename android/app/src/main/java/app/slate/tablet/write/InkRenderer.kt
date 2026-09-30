package app.slate.tablet.write

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Renders ink as black on white, cropped to the writing, for the recognizer. */
object InkRenderer {
    private const val STROKE_PX = 4f

    fun toPng(strokes: List<Stroke>): ByteArray? {
        val b = InkGeometry.bounds(strokes) ?: return null
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        val scale = InkGeometry.scale(w, h)
        val bitmap = Bitmap.createBitmap(ceil(w * scale).toInt().coerceAtLeast(1), ceil(h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-b[0], -b[1])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = STROKE_PX / scale
        }
        for (s in strokes) {
            if (s.pointCount == 1) {
                canvas.drawPoint(s.x(0), s.y(0), paint)
                continue
            }
            val path = Path()
            path.moveTo(s.x(0), s.y(0))
            for (i in 1 until s.pointCount) path.lineTo(s.x(i), s.y(i))
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
