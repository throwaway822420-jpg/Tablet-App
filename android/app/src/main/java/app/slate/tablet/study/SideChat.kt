package app.slate.tablet.study

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.slate.tablet.link.BulkLink
import app.slate.tablet.ui.Prefs
import app.slate.tablet.write.WritePanel

/**
 * A small floating conversation box, like a side chat: after asking, the answer streams in here on
 * top of whatever you're looking at, and you can keep chatting (type, or handwrite with Slate
 * Keyboard), ✎ draw on the answer, expand to History, collapse it to its title bar, or drag it.
 * Uses Android's "Appear on top" permission; without it, answers open in History as before.
 */
@SuppressLint("StaticFieldLeak", "ClickableViewAccessibility", "SetJavaScriptEnabled")
object SideChat {
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var title: TextView? = null
    private var body: View? = null
    private var input: EditText? = null
    private var session: String? = null
    private var pageReady = false
    private var collapsed = false
    /** Hidden while the drawing screen is open over it. */
    private var suspended = false

    private val onChange: (String) -> Unit = { sid -> if (sid == session) render() }

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** Opens Android's "Appear on top" setting for Slate. */
    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Shows (or switches) the box to [sessionId]. Returns false if Slate may not draw on top. */
    fun show(context: Context, sessionId: String): Boolean {
        val app = context.applicationContext
        if (!canShow(app)) return false
        session = sessionId
        suspended = false
        if (root == null) build(app)
        root?.visibility = View.VISIBLE
        setCollapsed(false)
        render()
        return true
    }

    fun close() {
        val r = root ?: return
        StudyHub.removeListener(onChange)
        hideKeyboard()
        runCatching { r.context.getSystemService(WindowManager::class.java).removeView(r) }
        web?.destroy()
        root = null; web = null; title = null; body = null; input = null; params = null
        pageReady = false
    }

    /** Out of the way while a full-screen Slate screen (drawing) is open. */
    fun suspend() {
        if (root == null) return
        suspended = true
        hideKeyboard()
        root?.visibility = View.GONE
    }

    /** Back after the drawing screen closes without sending. */
    fun resume() {
        if (!suspended) return
        suspended = false
        root?.visibility = View.VISIBLE
    }

    private fun build(app: Context) {
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val wm = app.getSystemService(WindowManager::class.java)
        val screen = app.resources.displayMetrics

        val t = TextView(app).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun headerButton(label: String, onClick: () -> Unit) = TextView(app).apply {
            text = label
            setTextColor(Color.rgb(220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(40, 130, 90))
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(headerButton("⤢") { openHistory(app) }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(headerButton("—") { setCollapsed(!collapsed) }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(headerButton("✕") { close() }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val w = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.rgb(24, 26, 30))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    view.evaluateJavascript("document.body.style.fontSize='15px'; document.body.style.padding='4px 12px 24px'", null)
                    render()
                }
            }
            loadUrl("file:///android_asset/study/viewer.html")
        }

        val field = EditText(app).apply {
            hint = "Reply to Claude…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(140, 140, 140))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundColor(Color.rgb(36, 39, 45))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // The box only takes keyboard focus while you're typing in it, so the app behind keeps working.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    takeFocus(true)
                    v.requestFocus()
                    v.post { app.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0) }
                }
                false
            }
        }
        val compose = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(6), dp(8), dp(8))
            addView(WritePanel.makeButton(app, "✎") { draw(app) }, LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginEnd = dp(6) })
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(WritePanel.makeButton(app, "Send", accent = true) { send(app) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(6) })
        }
        val b = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(compose)
        }

        val r = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.rgb(24, 26, 30)); setStroke(dp(1), Color.rgb(70, 74, 82)) }
            clipToOutline = true
            elevation = 12 * d
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // A tap anywhere else: give keyboard focus back to the app behind.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) takeFocus(false)
                false
            }
        }

        val p = WindowManager.LayoutParams(
            minOf((screen.widthPixels * 0.38f).toInt(), dp(620)).coerceAtLeast(dp(340)),
            (screen.heightPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // Drag the box by its title bar.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (startX - (e.rawX - downX)).toInt().coerceIn(0, screen.widthPixels - dp(120))
                    p.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, screen.heightPixels - dp(60))
                    runCatching { wm.updateViewLayout(r, p) }
                }
            }
            true
        }

        root = r; params = p; web = w; title = t; body = b; input = field
        wm.addView(r, p)
        StudyHub.addListener(onChange)
    }

    private fun takeFocus(on: Boolean) {
        val r = root ?: return
        val p = params ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val has = p.flags and flag == 0
        if (has == on) return
        p.flags = if (on) p.flags and flag.inv() else p.flags or flag
        runCatching { r.context.getSystemService(WindowManager::class.java).updateViewLayout(r, p) }
        if (!on) hideKeyboard()
    }

    private fun hideKeyboard() {
        val f = input ?: return
        f.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(f.windowToken, 0)
        f.clearFocus()
    }

    private fun setCollapsed(on: Boolean) {
        val r = root ?: return
        val p = params ?: return
        collapsed = on
        body?.visibility = if (on) View.GONE else View.VISIBLE
        val dm = r.resources.displayMetrics
        p.height = if (on) (44 * dm.density).toInt() else (dm.heightPixels * 0.62f).toInt()
        if (on) takeFocus(false)
        runCatching { r.context.getSystemService(WindowManager::class.java).updateViewLayout(r, p) }
    }

    private fun render() {
        val sid = session
        title?.text = sid?.let { StudyHub.store.session(it)?.title } ?: "Claude"
        if (pageReady) web?.evaluateJavascript(Conversation.renderScript(sid), null)
    }

    private fun send(app: Context) {
        val field = input ?: return
        val text = field.text.toString().trim()
        if (text.isEmpty()) return
        val prefs = Prefs(app)
        if (!BulkLink.connected && prefs.apiKey.isBlank()) {
            Toast.makeText(app, "Connect the PC, or add your Anthropic API key on Slate's main screen.", Toast.LENGTH_LONG).show()
            return
        }
        field.setText("")
        session = StudyHub.ask(session, "chat", emptyList(), "", prefs.apiKey, text)
        render()
    }

    /** Snapshot of the answer as shown, opened for drawing on (a follow-up about part of it). */
    private fun draw(app: Context) {
        val w = web ?: return
        val sid = session ?: return
        if (w.width <= 0 || w.height <= 0) return
        val shot = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        w.draw(Canvas(shot))
        suspend()
        AskActivity.start(app, shot, sid, followUp = true)
    }

    private fun openHistory(app: Context) {
        val sid = session
        close()
        app.startActivity(
            Intent(app, StudyActivity::class.java).putExtra(StudyActivity.EXTRA_SESSION, sid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}
