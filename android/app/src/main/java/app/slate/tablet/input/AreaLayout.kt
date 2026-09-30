package app.slate.tablet.input

/** Where the active area sits on the tablet screen. Pure math so it can be unit-tested. */
object AreaLayout {
    const val MIN_SCALE = 0.25f

    /**
     * The largest rectangle of [aspect] (width / height; 0 = the screen's own shape) that fits a
     * [width] × [height] screen, shrunk to [scale] of that size and centred.
     * Returns left, top, right, bottom.
     */
    fun bounds(width: Float, height: Float, aspect: Float, scale: Float): FloatArray {
        if (width <= 0f || height <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        var w = width
        var h = height
        if (aspect > 0f) {
            if (width / height > aspect) w = height * aspect else h = width / aspect
        }
        val s = scale.coerceIn(MIN_SCALE, 1f)
        w *= s
        h *= s
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return floatArrayOf(left, top, left + w, top + h)
    }
}
