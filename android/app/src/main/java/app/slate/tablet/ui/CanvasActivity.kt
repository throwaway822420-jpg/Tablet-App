package app.slate.tablet.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import app.slate.tablet.R
import android.widget.LinearLayout
import app.slate.tablet.link.SlateLink
import app.slate.tablet.mirror.MirrorPanel
import app.slate.tablet.write.PcTarget
import app.slate.tablet.write.WritePanel

/**
 * Full-screen surface with two modes, switched by the tab on the middle of the right edge:
 * Pen mode sends the S Pen to the PC as a pen; Write mode keeps the ink on the tablet and turns
 * it into text (or calculator answers) for the PC.
 */
class CanvasActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var view: CanvasView
    private lateinit var panel: WritePanel
    private lateinit var mirror: MirrorPanel
    private lateinit var penTabs: LinearLayout
    private var mode = Mode.PEN

    enum class Mode { PEN, WRITE, SCREEN }
    private val onStatus: (SlateLink.Status) -> Unit = { view.status = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        view = CanvasView(this, prefs)
        fun tab(label: Int, to: Mode) = WritePanel.makeButton(this, getString(label)) { setMode(to) }
        panel = WritePanel(
            this, PcTarget(this) { prefs.addSpaceAfterText }, prefs.panelSettings(), vertical = true,
            extraButtons = listOf(tab(R.string.mode_pen, Mode.PEN), tab(R.string.mode_screen, Mode.SCREEN)),
        )
        mirror = MirrorPanel(
            this, prefs,
            extraButtons = listOf(tab(R.string.mode_pen, Mode.PEN), tab(R.string.mode_write, Mode.WRITE)),
            toolButtons = listOf(
                WritePanel.makeButton(this, "Ask", accent = true) { mirror.ask() },
                WritePanel.makeButton(this, "History") { startActivity(android.content.Intent(this, app.slate.tablet.study.StudyActivity::class.java)) },
            ),
        )
        // Pen mode: tabs on the middle of the right edge switch to writing or the PC's screen.
        penTabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(tab(R.string.mode_write, Mode.WRITE), tab(R.string.mode_screen, Mode.SCREEN)).forEach {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { topMargin = dp(6) })
            }
        }

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mirror, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(penTabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(root)

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setMode(savedInstanceState?.getString(KEY_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.PEN)
        immersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
    }

    override fun onStart() {
        super.onStart()
        SlateLink.addListener(onStatus)
    }

    override fun onStop() {
        SlateLink.removeListener(onStatus)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        view.refreshArea()
        panel.writeView.invalidate()
        if (mode == Mode.SCREEN) mirror.start()
        focusCurrent()
    }

    override fun onPause() {
        // App switch, screen off, notification shade: never leave the PC with the pen down.
        view.capture.release()
        panel.pause()
        mirror.stop() // no video while the surface isn't visible
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive() else {
            view.capture.release()
            mirror.mirror.capture.release()
        }
    }

    private fun setMode(m: Mode) {
        // Whatever the pen was doing on the PC ends before the mode changes.
        view.capture.release()
        mirror.mirror.capture.release()
        if (m != Mode.WRITE) panel.pause()
        if (m == Mode.SCREEN) mirror.start() else mirror.stop()
        mode = m
        view.visibility = if (m == Mode.PEN) View.VISIBLE else View.GONE
        penTabs.visibility = if (m == Mode.PEN) View.VISIBLE else View.GONE
        panel.visibility = if (m == Mode.WRITE) View.VISIBLE else View.GONE
        mirror.visibility = if (m == Mode.SCREEN) View.VISIBLE else View.GONE
        focusCurrent()
    }

    private fun focusCurrent() = when (mode) {
        Mode.PEN -> view.requestFocus()
        Mode.WRITE -> panel.writeView.requestFocus()
        Mode.SCREEN -> mirror.mirror.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
