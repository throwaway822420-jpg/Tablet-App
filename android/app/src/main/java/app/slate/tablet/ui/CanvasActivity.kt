package app.slate.tablet.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.slate.tablet.R
import app.slate.tablet.link.SlateLink
import app.slate.tablet.write.Ink
import app.slate.tablet.write.WriteController
import app.slate.tablet.write.WriteController.State
import app.slate.tablet.write.WriteView

/**
 * Full-screen surface with two modes, switched by the tab on the middle of the right edge:
 * Pen mode sends the S Pen to the PC as a pen; Write mode keeps the ink on the tablet and turns
 * it into text that the PC types.
 */
class CanvasActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var view: CanvasView
    private lateinit var writeView: WriteView
    private lateinit var controller: WriteController
    private lateinit var modeButton: Button
    private lateinit var writeButtons: LinearLayout
    private lateinit var writeStatus: TextView
    private var writeMode = false
    private val onStatus: (SlateLink.Status) -> Unit = { view.status = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        view = CanvasView(this, prefs)

        val ink = Ink()
        writeView = WriteView(this, ink) { prefs.darkCanvas }.apply { visibility = View.GONE }
        controller = WriteController(ink, { prefs.apiKey }, { prefs.addSpaceAfterText }, ::showWriteState)
        writeView.controller = controller

        writeStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }

        modeButton = sideButton(getString(R.string.mode_write)) { setWriteMode(!writeMode) }
        writeButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(sideButton(getString(R.string.write_enter), accent = true) { controller.enter() })
            addView(sideButton(getString(R.string.write_undo)) { ink.undo(); writeView.invalidate(); controller.onInkChanged() })
            addView(sideButton(getString(R.string.write_clear)) { ink.clear(); writeView.invalidate(); controller.onInkChanged() })
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(modeButton)
            addView(writeButtons)
        }

        val root = FrameLayout(this)
        root.addView(view, match())
        root.addView(writeView, match())
        root.addView(writeStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(side, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
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
        writeView.invalidate()
        (if (writeMode) writeView else view).requestFocus()
    }

    override fun onPause() {
        // App switch, screen off, notification shade: never leave the PC with the pen down.
        view.capture.release()
        super.onPause()
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive() else view.capture.release()
    }

    private fun setWriteMode(on: Boolean) {
        writeMode = on
        if (on) view.capture.release() // the PC's pen leaves while we write locally
        writeView.visibility = if (on) View.VISIBLE else View.GONE
        writeButtons.visibility = if (on) View.VISIBLE else View.GONE
        writeStatus.visibility = if (on) View.VISIBLE else View.GONE
        modeButton.text = getString(if (on) R.string.mode_pen else R.string.mode_write)
        if (on) {
            showWriteState(if (writeView.ink.isEmpty) State.Empty else State.Writing)
            writeView.requestFocus()
        } else {
            view.requestFocus()
        }
    }

    private fun showWriteState(state: State) {
        val dark = prefs.darkCanvas
        writeStatus.setTextColor(if (dark) Color.rgb(170, 170, 170) else Color.rgb(90, 90, 90))
        writeStatus.text = when (state) {
            State.Empty -> getString(if (prefs.apiKey.isBlank()) R.string.write_need_key else R.string.write_hint)
            State.Writing -> ""
            State.Converting -> getString(R.string.write_converting)
            is State.Preview -> if (state.text.isEmpty()) "" else "→ ${state.text}"
            is State.Typing -> getString(R.string.write_typing, state.text)
            is State.Typed -> getString(R.string.write_typed, state.text, state.ms)
            is State.Failed -> state.message
        }
        if (state is State.Failed) writeStatus.setTextColor(Color.rgb(230, 90, 80))
        if (state is State.Typed) writeView.invalidate()
    }

    private fun sideButton(label: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.WHITE)
        minWidth = dp(72)
        minimumWidth = dp(72)
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(dp(12).toFloat(), dp(12).toFloat(), 0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat())
            setColor(if (accent) Color.rgb(40, 130, 90) else Color.argb(200, 60, 64, 72))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

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
