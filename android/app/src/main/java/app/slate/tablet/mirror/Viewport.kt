package app.slate.tablet.mirror

/**
 * Where the mirrored screen sits on the tablet. [scale] is relative to "fit" (1 = the whole PC
 * screen fits the tablet; below 1 shrinks it; above 1 zooms in), and (cx, cy) is the point of the
 * PC screen (0..1) shown at the centre of the tablet. Pure maths, so it's unit-tested.
 */
class Viewport {
    var viewW = 1f; private set
    var viewH = 1f; private set
    var frameW = 16f; private set
    var frameH = 9f; private set
    var scale = 1f; private set
    var cx = 0.5f; private set
    var cy = 0.5f; private set

    companion object {
        const val MIN_SCALE = 0.3f
        const val MAX_SCALE = 8f
    }

    fun setView(w: Float, h: Float) {
        viewW = maxOf(1f, w); viewH = maxOf(1f, h); clamp()
    }

    fun setFrame(w: Float, h: Float) {
        frameW = maxOf(1f, w); frameH = maxOf(1f, h); clamp()
    }

    private fun fitW(): Float = minOf(viewW, viewH * frameW / frameH)
    private fun fitH(): Float = fitW() * frameH / frameW

    /** The PC screen's rectangle on the tablet: left, top, right, bottom. */
    fun displayed(): FloatArray {
        val w = fitW() * scale
        val h = fitH() * scale
        val l = viewW / 2 - cx * w
        val t = viewH / 2 - cy * h
        return floatArrayOf(l, t, l + w, t + h)
    }

    val isZoomedIn: Boolean get() = scale > 1.001f

    /** Tablet point → PC screen point (0..1, may fall outside when pointing off the picture). */
    fun viewToFrame(x: Float, y: Float): Pair<Float, Float> {
        val d = displayed()
        return (x - d[0]) / (d[2] - d[0]) to (y - d[1]) / (d[3] - d[1])
    }

    fun frameToView(nx: Float, ny: Float): Pair<Float, Float> {
        val d = displayed()
        return d[0] + nx * (d[2] - d[0]) to d[1] + ny * (d[3] - d[1])
    }

    fun reset(newScale: Float = 1f) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        cx = 0.5f; cy = 0.5f
        clamp()
    }

    /** Zooms by [factor] keeping the PC point under (fx, fy) in place, like pinch-zoom. */
    fun zoomAt(factor: Float, fx: Float, fy: Float) {
        val (px, py) = viewToFrame(fx, fy)
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val w = fitW() * scale
        val h = fitH() * scale
        cx = (viewW / 2 - (fx - px * w)) / w
        cy = (viewH / 2 - (fy - py * h)) / h
        clamp()
    }

    /** Moves the picture by (dx, dy) tablet pixels. */
    fun panBy(dx: Float, dy: Float) {
        val d = displayed()
        cx -= dx / (d[2] - d[0])
        cy -= dy / (d[3] - d[1])
        clamp()
    }

    /** Centres the view on a PC point (e.g. from the mini-map). */
    fun centreOn(nx: Float, ny: Float) {
        cx = nx; cy = ny; clamp()
    }

    /**
     * When zoomed in, pans just enough to keep a PC point (the cursor) inside the middle of the
     * view, leaving [margin] of the view as a border. Returns true if it moved.
     */
    fun follow(nx: Float, ny: Float, margin: Float = 0.15f): Boolean {
        if (!isZoomedIn) return false
        val (vx, vy) = frameToView(nx, ny)
        val mx = viewW * margin
        val my = viewH * margin
        var dx = 0f
        var dy = 0f
        if (vx < mx) dx = mx - vx else if (vx > viewW - mx) dx = viewW - mx - vx
        if (vy < my) dy = my - vy else if (vy > viewH - my) dy = viewH - my - vy
        if (dx == 0f && dy == 0f) return false
        val before = cx to cy
        panBy(dx, dy)
        return before != (cx to cy)
    }

    /** Keeps the picture covering the view when it's bigger than the view, and centred when smaller. */
    private fun clamp() {
        val w = fitW() * scale
        val h = fitH() * scale
        cx = if (w <= viewW) 0.5f else cx.coerceIn(viewW / 2 / w, 1 - viewW / 2 / w)
        cy = if (h <= viewH) 0.5f else cy.coerceIn(viewH / 2 / h, 1 - viewH / 2 / h)
    }
}
