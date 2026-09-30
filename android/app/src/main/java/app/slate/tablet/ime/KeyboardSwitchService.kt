package app.slate.tablet.ime

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast

/**
 * Puts a small "✎ Slate" button just above any other keyboard (Samsung Keyboard, Gboard…) while it
 * is showing. Tapping it switches straight to Slate Keyboard. Android only lets an accessibility
 * service switch keyboards, which is why this is one; it reads nothing but where the keyboard is.
 */
class KeyboardSwitchService : AccessibilityService() {
    private var button: TextView? = null
    private var shownAt: Rect? = null

    private val slateId by lazy { ComponentName(this, SlateKeyboard::class.java).flattenToShortString() }

    override fun onServiceConnected() = update()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) update()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    private fun update() {
        val ime = runCatching { windows }.getOrNull()?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null || isSlate(ime)) return hide()
        val bounds = Rect().also { ime.getBoundsInScreen(it) }
        if (bounds.isEmpty) return hide()
        show(bounds)
    }

    private fun isSlate(ime: AccessibilityWindowInfo): Boolean {
        val pkg = runCatching { ime.root?.packageName?.toString() }.getOrNull()
        if (pkg != null) return pkg == packageName
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ComponentName.unflattenFromString(current)?.packageName == packageName
    }

    private fun show(keyboard: Rect) {
        if (button != null && shownAt == keyboard) return
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus, or the text field would lose it and the keyboard would close.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val height = (40 * d).toInt()
            y = (keyboard.top - height - (6 * d).toInt()).coerceAtLeast(0)
            x = (keyboard.right - (132 * d).toInt()).coerceAtLeast(0)
        }
        val b = button ?: TextView(this).apply {
            text = "✎ Slate"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            minHeight = (40 * d).toInt()
            minWidth = (116 * d).toInt()
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(0xE0304FFE.toInt())
            }
            elevation = 4 * d
            contentDescription = "Switch to Slate Keyboard"
            setOnClickListener { switchToSlate() }
        }
        if (button == null) {
            runCatching { wm.addView(b, params) }.onFailure { return }
            button = b
        } else {
            runCatching { wm.updateViewLayout(b, params) }
        }
        shownAt = Rect(keyboard)
    }

    private fun hide() {
        button?.let { b -> runCatching { getSystemService(WindowManager::class.java).removeView(b) } }
        button = null
        shownAt = null
    }

    private fun switchToSlate() {
        val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { softKeyboardController.switchToInputMethod(slateId) }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(this, "Turn on Slate Keyboard first (Slate › Slate Keyboard › 1).", Toast.LENGTH_LONG).show()
        }
    }
}
