package app.slate.tablet.study

import kotlin.math.hypot

/** Finds circled areas in annotations, to send a zoomed crop alongside the whole screen. Pure maths. */
object Loops {
    /** A stroke as x,y pairs in image pixels. */
    fun isLoop(points: FloatArray): Boolean {
        val n = points.size / 2
        if (n < 12) return false
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var length = 0f
        for (i in 0 until n) {
            val x = points[i * 2]; val y = points[i * 2 + 1]
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
            if (i > 0) length += hypot(x - points[i * 2 - 2], y - points[i * 2 - 1])
        }
        val w = r - l
        val h = b - t
        val diag = hypot(w, h)
        if (w < 40 || h < 25) return false
        val gap = hypot(points[0] - points[n * 2 - 2], points[1] - points[n * 2 - 1])
        // Closed (ends meet, allowing a sloppy circle) and roundish (the path goes around, not back and forth).
        return gap < 0.35f * diag && length > 1.8f * diag
    }

    /**
     * The area worth zooming into: the union of circled areas plus a margin, at least [minSize]
     * pixels, inside the image. Null when nothing was circled.
     */
    fun cropRect(strokes: List<FloatArray>, imageW: Int, imageH: Int, minSize: Float = 320f): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (p in strokes.filter(::isLoop)) {
            any = true
            for (i in 0 until p.size / 2) {
                l = minOf(l, p[i * 2]); t = minOf(t, p[i * 2 + 1]); r = maxOf(r, p[i * 2]); b = maxOf(b, p[i * 2 + 1])
            }
        }
        if (!any) return null
        val mx = maxOf((r - l) * 0.15f, (minSize - (r - l)) / 2, 0f)
        val my = maxOf((b - t) * 0.15f, (minSize - (b - t)) / 2, 0f)
        l = (l - mx).coerceAtLeast(0f); t = (t - my).coerceAtLeast(0f)
        r = (r + mx).coerceAtMost(imageW.toFloat()); b = (b + my).coerceAtMost(imageH.toFloat())
        // Not worth a crop if it's most of the screen anyway.
        if ((r - l) * (b - t) > 0.6f * imageW * imageH) return null
        return floatArrayOf(l, t, r, b)
    }
}
