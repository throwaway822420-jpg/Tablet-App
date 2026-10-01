package app.slate.tablet.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import app.slate.tablet.link.BulkLink
import app.slate.tablet.link.SlateLink
import app.slate.tablet.link.Transport
import app.slate.tablet.ui.Prefs
import app.slate.tablet.write.PcTarget
import app.slate.tablet.write.WritePanel
import org.json.JSONObject

/**
 * Screen mode: the mirrored PC screen plus its controls: mode buttons and a ⋯ menu on the right,
 * shortcut buttons down the left, and a status line while the stream starts.
 */
@SuppressLint("ViewConstructor")
class MirrorPanel(
    context: Context,
    private val prefs: Prefs,
    extraButtons: List<Button>,
    /** Extra buttons added under the ⋯ button (e.g. Ask Claude). */
    toolButtons: List<Button> = emptyList(),
) : FrameLayout(context) {
    val mirror = MirrorView(context)
    private val status = TextView(context)
    private val statsView = TextView(context)
    private var pcStats: JSONObject? = null
    /** PC frame clock minus tablet clock (µs), from the last clock sync; null until synced. */
    private var clockOffsetUs: Long? = null
    private var bestRttUs = Long.MAX_VALUE
    private val statsTick = object : Runnable {
        override fun run() {
            if (!active || !prefs.showStreamStats) return
            BulkLink.send(JSONObject().put("t", "clock").put("c", nowUs()))
            showStats()
            postDelayed(this, 1000)
        }
    }
    private val shortcuts = LinearLayout(context)
    private val shortcutScroll = ScrollView(context)
    private var active = false
    private var running = false
    private lateinit var right: LinearLayout

    /**
     * The handwriting pad: slides up over the bottom of the screen when you tap into a text field on
     * the PC. The PC's screen stays visible (shrunk into the space above it) but ignores touches.
     */
    private var padView: WritePanel? = null
    private val pad: WritePanel get() = padView ?: createPad().also { padView = it }

    private fun createPad(): WritePanel {
        fun key(label: String, action: () -> Unit) = WritePanel.makeButton(context, label, onClick = action)
        fun keys(combo: String) = BulkLink.send(JSONObject().put("t", "keys").put("combo", combo))
        return WritePanel(
            context, PcTarget(context) { prefs.addSpaceAfterText }, prefs.panelSettings(), vertical = false,
            extraButtons = listOf(key("✕ Screen") { closePad() }),
            trailingButtons = listOf(key("Space") { keys("Space") }, key("⌫") { keys("Backspace") }, key("↵") { keys("Enter") }),
        ).also { p ->
            p.visibility = View.GONE
            p.setBackgroundColor(if (prefs.darkCanvas) Color.rgb(24, 24, 24) else Color.WHITE)
            p.elevation = dp(8).toFloat()
            addView(p, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        }
    }

    val padOpen: Boolean get() = padView?.visibility == View.VISIBLE

    fun openPad() {
        if (padOpen) return
        val h = (height * 0.4f).toInt().coerceAtLeast(dp(260))
        pad.layoutParams = (pad.layoutParams as LayoutParams).apply { height = h }
        pad.visibility = View.VISIBLE
        mirror.inputEnabled = false
        // The whole PC screen moves into the space above the pad, so the text field stays in view.
        mirror.layoutParams = (mirror.layoutParams as LayoutParams).apply { bottomMargin = h }
        right.visibility = View.GONE
        shortcutScroll.visibility = View.GONE
        pad.writeView.requestFocus()
    }

    fun closePad() {
        if (!padOpen) return
        pad.pause()
        pad.visibility = View.GONE
        mirror.layoutParams = (mirror.layoutParams as LayoutParams).apply { bottomMargin = 0 }
        mirror.inputEnabled = true
        right.visibility = View.VISIBLE
        applyPrefs()
        mirror.requestFocus()
    }

    /** Stops any recognition in flight (the surface is going away). */
    fun pause() {
        padView?.pause()
    }

    private fun onTextFocus(editable: Boolean) {
        // Only when the focus follows your own tap, not when an app moves focus by itself.
        val tapped = android.os.SystemClock.uptimeMillis() - mirror.lastTouchMs < 2500
        if (editable && tapped && prefs.writeOnTextField && running) openPad()
    }

    private var shotRequested = false
    private val onShot: (JSONObject, ByteArray) -> Unit = { h, data ->
        if (h.optString("t") == "shot" && shotRequested) {
            shotRequested = false
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.let { openAsk(it) } ?: askFromVideo()
        }
    }

    /** Freeze the PC's screen at full resolution and open it for annotation. */
    fun ask() {
        if (!BulkLink.connected) return
        shotRequested = true
        showStatus("Capturing the screen…")
        BulkLink.send(JSONObject().put("t", "shot"))
        // If the PC doesn't answer quickly, use the current video frame instead.
        postDelayed({ if (shotRequested) { shotRequested = false; askFromVideo() } }, 3000)
    }

    private fun askFromVideo() {
        val v = mirror.viewport
        mirror.viewport.reset(1f)
        mirror.applyTransform()
        val d = v.displayed()
        val full = mirror.snapshot() ?: return showStatus("Couldn't capture the screen.")
        val crop = android.graphics.Bitmap.createBitmap(full, d[0].toInt().coerceAtLeast(0), d[1].toInt().coerceAtLeast(0),
            (d[2] - d[0]).toInt().coerceAtMost(full.width), (d[3] - d[1]).toInt().coerceAtMost(full.height))
        openAsk(crop)
    }

    private fun openAsk(bitmap: android.graphics.Bitmap) {
        if (running) status.visibility = View.GONE
        val activity = context as? android.app.Activity ?: return
        app.slate.tablet.study.AskActivity.start(activity, bitmap, app.slate.tablet.study.StudyHub.recentSession(tabletOnly = false))
    }

    private val onJson: (JSONObject) -> Unit = { o ->
        when (o.optString("t")) {
            "stream" -> onStream(o)
            "cursor" -> mirror.onCursor(o.optDouble("x").toFloat(), o.optDouble("y").toFloat())
            "textfocus" -> onTextFocus(o.optBoolean("editable"))
            "stats" -> pcStats = o
            "clock" -> onClock(o)
        }
    }

    private val onBulk: (Boolean) -> Unit = { connected ->
        if (active) {
            if (connected) requestStream() else {
                running = false
                showStatus(
                    if (SlateLink.status.phase == SlateLink.Phase.CONNECTED) "Connecting the screen stream… (update Slate on the PC if this stays)"
                    else "Waiting for the PC…",
                )
            }
        }
    }

    init {
        addView(mirror, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        status.apply {
            setTextColor(Color.rgb(200, 200, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        statsView.apply {
            setTextColor(Color.rgb(140, 240, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = View.GONE
        }
        addView(statsView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        val more = WritePanel.makeButton(context, "⋯ View") {}
        more.setOnClickListener { showMenu(more) }
        val fit = WritePanel.makeButton(context, "Fit") { mirror.viewport.reset(1f); mirror.applyTransform() }
        right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            (extraButtons + listOf(more, fit) + toolButtons).forEach {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { topMargin = dp(6) })
            }
        }
        addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))

        shortcuts.orientation = LinearLayout.VERTICAL
        shortcutScroll.addView(shortcuts)
        addView(shortcutScroll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        buildShortcuts()
        applyPrefs()
    }

    fun start() {
        if (active) return
        active = true
        applyPrefs()
        buildShortcuts()
        BulkLink.addJsonListener(onJson)
        BulkLink.addBlobListener(onShot)
        BulkLink.addStateListener(onBulk)
        showStatus("Starting the screen stream…")
        startStats()
    }

    private fun startStats() {
        removeCallbacks(statsTick)
        resetStats()
        statsView.visibility = if (prefs.showStreamStats) View.VISIBLE else View.GONE
        if (prefs.showStreamStats) post(statsTick)
    }

    private fun resetStats() {
        pcStats = null
        clockOffsetUs = null
        bestRttUs = Long.MAX_VALUE
        mirror.decoder.stats.apply { received.set(0); shown.set(0); late.set(0); skipped.set(0); delaySumUs.set(0); delayCount.set(0) }
    }

    private fun nowUs() = android.os.SystemClock.elapsedRealtimeNanos() / 1000

    /** Clock sync: keep the offset from the fastest round trip seen, the least distorted by queuing. */
    private fun onClock(o: JSONObject) {
        val sent = o.optLong("c")
        val rtt = nowUs() - sent
        if (rtt < 0 || rtt > bestRttUs * 2 + 20_000) return
        if (rtt <= bestRttUs) bestRttUs = rtt
        if (rtt == bestRttUs || clockOffsetUs == null) clockOffsetUs = o.optLong("pc") - (sent + rtt / 2)
    }

    private fun showStats() {
        val s = mirror.decoder.stats
        val received = s.received.getAndSet(0)
        val shown = s.shown.getAndSet(0)
        val late = s.late.getAndSet(0)
        val skipped = s.skipped.getAndSet(0)
        val count = s.delayCount.getAndSet(0)
        val sum = s.delaySumUs.getAndSet(0)
        val pc = pcStats
        val delay = clockOffsetUs?.takeIf { count > 0 }?.let { off -> ((sum / count + off) / 1000).coerceAtLeast(0) }
        statsView.text = buildString {
            if (pc != null) {
                append("PC  ").append(pc.optInt("w")).append('×').append(pc.optInt("h"))
                append(" · ").append(pc.optString("encoder")).append(" · ").append(pc.optString("capture"))
                append("\nPC sent ").append(pc.optInt("sent")).append(" fps (target ").append(pc.optInt("fps")).append(")")
                if (pc.optInt("dropped") > 0) append(" · link dropped ").append(pc.optInt("dropped"))
                append('\n')
            } else append("PC  waiting for stats (update Slate on the PC?)\n")
            append("Tablet got ").append(received).append(" · shown ").append(shown).append(" fps")
            if (late > 0) append(" · late ").append(late)
            if (skipped > 0) append(" · skipped ").append(skipped)
            append("\nDelay (PC encoder → tablet screen): ").append(delay?.let { "$it ms" } ?: "measuring…")
            append(" · USB/Wi-Fi round trip ").append(if (bestRttUs == Long.MAX_VALUE) "…" else "${bestRttUs / 1000} ms")
            if (s.decoderName.isNotEmpty()) append("\nDecoder ").append(s.decoderName)
        }
    }

    fun stop() {
        if (!active) return
        closePad()
        removeCallbacks(statsTick)
        active = false
        running = false
        mirror.capture.release()
        BulkLink.send(JSONObject().put("t", "stream.stop"))
        BulkLink.removeJsonListener(onJson)
        BulkLink.removeBlobListener(onShot)
        BulkLink.removeStateListener(onBulk)
    }

    private fun requestStream() {
        val o = JSONObject().put("t", "stream.start").put("fps", 60)
            .put("usb", SlateLink.status.transport == Transport.USB)
            .put("monitor", prefs.mirrorMonitor)
        maxSize()?.let { (w, h) -> o.put("maxw", w).put("maxh", h) }
        if (prefs.mirrorBitrate > 0) o.put("bitrate", prefs.mirrorBitrate)
        if (BulkLink.send(o)) showStatus("Starting the screen stream…")
    }

    /** The largest picture worth sending, from the Resolution setting; null for the PC's full resolution. */
    private fun maxSize(): Pair<Int, Int>? = when (prefs.mirrorResolution) {
        1 -> {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            val d = display
            if (d == null) null else {
                @Suppress("DEPRECATION")
                d.getRealMetrics(m)
                maxOf(m.widthPixels, m.heightPixels) to minOf(m.widthPixels, m.heightPixels)
            }
        }
        2 -> 1920 to 1080
        else -> null
    }

    private fun onStream(o: JSONObject) {
        when (o.optString("state")) {
            "running" -> {
                running = true
                status.visibility = View.GONE
            }
            "stopped" -> {
                running = false
                if (active) showStatus(o.optString("message", "Stream stopped"))
            }
            else -> showStatus(o.optString("message"))
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyPrefs() {
        mirror.fingerMode = if (prefs.windowsTouch) MirrorView.FingerMode.TOUCH else MirrorView.FingerMode.NAVIGATE
        mirror.followCursor = prefs.followCursor
        shortcutScroll.visibility = if (prefs.showShortcuts && !padOpen) View.VISIBLE else View.GONE
    }

    private fun buildShortcuts() {
        shortcuts.removeAllViews()
        for ((label, combo) in Prefs.parseShortcuts(prefs.shortcuts)) {
            shortcuts.addView(
                WritePanel.makeButton(context, label) { BulkLink.send(JSONObject().put("t", "keys").put("combo", combo)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(4); marginStart = dp(4) },
            )
        }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        val m = menu.menu
        val v = mirror.viewport
        m.add("Fit to screen").setOnMenuItemClickListener { v.reset(1f); mirror.applyTransform(); true }
        m.add("Shrink (easier to reach)").setOnMenuItemClickListener { v.reset(0.6f); mirror.applyTransform(); true }
        m.add("Zoom 2×").setOnMenuItemClickListener { v.reset(2f); mirror.applyTransform(); true }
        m.add("Actual pixels").setOnMenuItemClickListener {
            val d = v.displayed()
            v.reset(v.frameW / ((d[2] - d[0]) / v.scale))
            mirror.applyTransform(); true
        }
        m.add("Stream stats (fps, delay)").apply {
            isCheckable = true; isChecked = prefs.showStreamStats
            setOnMenuItemClickListener { prefs.showStreamStats = !prefs.showStreamStats; startStats(); true }
        }
        m.add("Fingers: Windows touch").apply {
            isCheckable = true; isChecked = prefs.windowsTouch
            setOnMenuItemClickListener { prefs.windowsTouch = !prefs.windowsTouch; applyPrefs(); true }
        }
        m.add("Handwriting pad (write into the PC)").setOnMenuItemClickListener { openPad(); true }
        m.add("Handwriting pad when I tap a text field").apply {
            isCheckable = true; isChecked = prefs.writeOnTextField
            setOnMenuItemClickListener { prefs.writeOnTextField = !prefs.writeOnTextField; true }
        }
        m.add("Follow the cursor when zoomed").apply {
            isCheckable = true; isChecked = prefs.followCursor
            setOnMenuItemClickListener { prefs.followCursor = !prefs.followCursor; applyPrefs(); true }
        }
        m.add("Shortcut buttons").apply {
            isCheckable = true; isChecked = prefs.showShortcuts
            setOnMenuItemClickListener { prefs.showShortcuts = !prefs.showShortcuts; applyPrefs(); true }
        }
        val monitors = BulkLink.welcome?.optJSONArray("monitors")
        if (monitors != null && monitors.length() > 1) {
            val sub = m.addSubMenu("Monitor")
            for (i in 0 until monitors.length()) {
                val mon = monitors.getJSONObject(i)
                sub.add(mon.optString("name")).setOnMenuItemClickListener {
                    prefs.mirrorMonitor = mon.optString("id")
                    requestStream(); true
                }
            }
        }
        val res = m.addSubMenu("Resolution")
        for ((label, value) in listOf("Match this tablet (recommended)" to 1, "1080p (fastest)" to 2, "PC's full resolution" to 0)) {
            res.add(label).apply {
                isCheckable = true; isChecked = prefs.mirrorResolution == value
                setOnMenuItemClickListener { prefs.mirrorResolution = value; requestStream(); true }
            }
        }
        val q = m.addSubMenu("Quality")
        for ((label, bps) in listOf("Automatic" to 0, "Best (40 Mb/s)" to 40_000_000, "Balanced (15 Mb/s)" to 15_000_000, "Data saver (6 Mb/s)" to 6_000_000)) {
            q.add(label).apply {
                isCheckable = true; isChecked = prefs.mirrorBitrate == bps
                setOnMenuItemClickListener { prefs.mirrorBitrate = bps; requestStream(); true }
            }
        }
        menu.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
