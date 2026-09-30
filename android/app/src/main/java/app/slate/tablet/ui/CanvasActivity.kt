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
import android.widget.Button
import android.widget.FrameLayout
import app.slate.tablet.R
import app.slate.tablet.link.SlateLink
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
    private lateinit var modeButton: Button
    private var writeMode = false
    private val onStatus: (SlateLink.Status) -> Unit = { view.status = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        view = CanvasView(this, prefs)
        modeButton = WritePanel.makeButton(this, getString(R.string.mode_write)) { setWriteMode(true) }
        panel = WritePanel(
            this, PcTarget(this) { prefs.addSpaceAfterText }, prefs.panelSettings(), vertical = true,
            extraButtons = listOf(WritePanel.makeButton(this, getString(R.string.mode_pen)) { setWriteMode(false) }),
        )

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Pen mode: one tab on the middle of the right edge switches to writing.
        root.addView(modeButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(root)

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setWriteMode(savedInstanceState?.getBoolean(KEY_WRITE_MODE) ?: false)
        immersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_WRITE_MODE, writeMode)
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
        (if (writeMode) panel.writeView else view).requestFocus()
    }

    override fun onPause() {
        // App switch, screen off, notification shade: never leave the PC with the pen down.
        view.capture.release()
        panel.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive() else view.capture.release()
    }

    private fun setWriteMode(on: Boolean) {
        writeMode = on
        if (on) view.capture.release() else panel.pause() // the PC's pen leaves while we write locally
        panel.visibility = if (on) View.VISIBLE else View.GONE
        modeButton.visibility = if (on) View.GONE else View.VISIBLE
        (if (on) panel.writeView else view).requestFocus()
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
        const val KEY_WRITE_MODE = "write_mode"
    }
}
