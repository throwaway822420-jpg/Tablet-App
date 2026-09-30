package app.slate.tablet.write

import kotlin.math.hypot

/** One pen stroke as x,y pairs in view pixels. */
class Stroke {
    var points = FloatArray(64)
        private set
    private var size = 0

    fun add(x: Float, y: Float) {
        if (size + 2 > points.size) points = points.copyOf(points.size * 2)
        points[size++] = x
        points[size++] = y
    }

    val pointCount: Int get() = size / 2
    fun x(i: Int) = points[i * 2]
    fun y(i: Int) = points[i * 2 + 1]

    fun copy(): Stroke = Stroke().also {
        it.points = points.copyOf(size)
        it.size = size
    }

    /** True if any part of the stroke (including between its sampled points) is within [radius]. */
    fun passesNear(px: Float, py: Float, radius: Float): Boolean {
        if (pointCount == 1) return hypot(x(0) - px, y(0) - py) <= radius
        for (i in 1 until pointCount) {
            if (segmentDistance(px, py, x(i - 1), y(i - 1), x(i), y(i)) <= radius) return true
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(ax + t * dx - px, ay + t * dy - py)
    }
}

/**
 * The handwriting on the tablet. [version] changes whenever the ink does, so a recognition
 * result can be matched to exactly the ink it was made from.
 */
class Ink {
    val strokes = ArrayList<Stroke>()
    var version = 0
        private set
    private var drawing: Stroke? = null

    val isEmpty: Boolean get() = strokes.isEmpty()

    /** A copy that's safe to read on another thread. */
    fun snapshot(): List<Stroke> = strokes.map { it.copy() }

    fun begin(x: Float, y: Float) {
        drawing = Stroke().also {
            it.add(x, y)
            strokes.add(it)
        }
        version++
    }

    fun extend(x: Float, y: Float) {
        drawing?.add(x, y)
    }

    fun end() {
        drawing = null
        version++
    }

    /** Removes strokes the eraser touches. Returns true if anything was erased. */
    fun eraseNear(x: Float, y: Float, radius: Float): Boolean {
        val removed = strokes.removeAll { it !== drawing && it.passesNear(x, y, radius) }
        if (removed) version++
        return removed
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        drawing = null
        version++
    }

    fun clear() {
        if (strokes.isEmpty()) return
        strokes.clear()
        drawing = null
        version++
    }
}

/** Geometry for rendering ink to an image. Pure math so it can be unit-tested. */
object InkGeometry {
    const val PADDING = 24f
    const val MAX_SIDE = 1400f
    const val MIN_SIDE = 320f

    /** left, top, right, bottom of all points plus padding, or null when there are none. */
    fun bounds(strokes: List<Stroke>): FloatArray? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (s in strokes) for (i in 0 until s.pointCount) {
            l = minOf(l, s.x(i)); t = minOf(t, s.y(i))
            r = maxOf(r, s.x(i)); b = maxOf(b, s.y(i))
        }
        if (l > r) return null
        return floatArrayOf(l - PADDING, t - PADDING, r + PADDING, b + PADDING)
    }

    /**
     * Scale from view pixels to image pixels: shrink big writing so the image stays small
     * (fewer tokens, faster upload), enlarge tiny writing so it stays legible.
     */
    fun scale(width: Float, height: Float): Float {
        val longest = maxOf(width, height)
        if (longest <= 0f) return 1f
        return when {
            longest > MAX_SIDE -> MAX_SIDE / longest
            longest < MIN_SIDE -> MIN_SIDE / longest
            else -> 1f
        }
    }
}
