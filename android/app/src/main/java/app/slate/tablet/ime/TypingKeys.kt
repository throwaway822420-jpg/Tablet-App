package app.slate.tablet.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A plain QWERTY layout inside Slate Keyboard, so switching between handwriting and typing happens
 * in place (no keyboard swap). Letters, a symbols page, one-shot shift (double-tap for caps lock),
 * and a repeating backspace. No autocorrect: ⌨ still switches to the phone's own keyboard for that.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TypingKeys(
    context: Context,
    private val actions: Actions,
) : LinearLayout(context) {

    interface Actions {
        fun type(text: String)
        fun backspace()
        fun enter()
        fun toHandwriting()
        fun toOtherKeyboard()
    }

    private enum class Shift { OFF, ONCE, LOCKED }

    private var shift = Shift.OFF
    private var symbols = false
    private var lastShiftTap = 0L
    private val letterKeys = ArrayList<TextView>()
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(6))
        build()
    }

    private fun build() {
        removeAllViews()
        letterKeys.clear()
        val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
        rows.forEachIndexed { i, row ->
            val line = row(if (i == 2 && !symbols) 0.5f else 0f)
            if (i == rows.lastIndex) line.addView(if (symbols) spacer(1.5f) else special(shiftLabel(), 1.5f) { onShift() })
            for (c in row) line.addView(charKey(c))
            if (i == rows.lastIndex) line.addView(repeatingKey("⌫", 1.5f) { actions.backspace() })
            if (i == 2 && !symbols) line.addView(spacer(0.5f))
        }
        val bottom = row(0f)
        bottom.addView(special("✎ Write", 1.6f, accent = true) { actions.toHandwriting() })
        bottom.addView(special(if (symbols) "ABC" else "?123", 1.3f) { symbols = !symbols; shift = Shift.OFF; build() })
        bottom.addView(charKey(","))
        bottom.addView(special("Space", 4.5f) { actions.type(" "); afterChar() })
        bottom.addView(charKey("."))
        bottom.addView(special("↵", 1.3f) { actions.enter() })
        bottom.addView(special("⌨", 1.2f) { actions.toOtherKeyboard() })
        refreshCase()
    }

    private fun row(leadingSpace: Float): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        if (leadingSpace > 0) addView(spacer(leadingSpace))
        this@TypingKeys.addView(this, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun spacer(weight: Float) = View(context).apply { layoutParams = LayoutParams(0, 1, weight) }

    private fun keyView(label: String, weight: Float, accent: Boolean = false) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) 15f else 21f)
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(if (accent) Color.rgb(40, 130, 90) else Color.rgb(60, 64, 72))
        }
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        isHapticFeedbackEnabled = true
    }

    private fun charKey(c: String): TextView = keyView(c, 1f).also { k ->
        if (c.length == 1 && c[0].isLetter()) letterKeys.add(k)
        k.setOnClickListener {
            k.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            actions.type(k.text.toString())
            afterChar()
        }
    }

    private fun special(label: String, weight: Float, accent: Boolean = false, onClick: () -> Unit) =
        keyView(label, weight, accent).apply {
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    /** Repeats while held, like a normal backspace. */
    private fun repeatingKey(label: String, weight: Float, onRepeat: () -> Unit) = keyView(label, weight).apply {
        val repeat = object : Runnable {
            override fun run() {
                onRepeat()
                postDelayed(this, 50)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onRepeat()
                    v.isPressed = true
                    v.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private fun shiftLabel() = when (shift) {
        Shift.OFF -> "⇧"
        Shift.ONCE -> "⬆"
        Shift.LOCKED -> "⇪"
    }

    private fun onShift() {
        if (symbols) return
        val now = android.os.SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.ONCE && now - lastShiftTap < 350 -> Shift.LOCKED
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShiftTap = now
        build()
    }

    /** One-shot shift drops after a letter; caps lock stays. */
    private fun afterChar() {
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            build()
        }
    }

    /** Capitalise the next letter, e.g. at the start of a field or sentence. */
    fun autoCapitalise(on: Boolean) {
        if (symbols || shift == Shift.LOCKED) return
        val want = if (on) Shift.ONCE else Shift.OFF
        if (shift != want) {
            shift = want
            build()
        }
    }

    private fun refreshCase() {
        val upper = shift != Shift.OFF
        for (k in letterKeys) k.text = if (upper) k.text.toString().uppercase() else k.text.toString().lowercase()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        val LETTER_ROWS = listOf(
            "1234567890".map { it.toString() },
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            "zxcvbnm".map { it.toString() } + listOf("'", "?"),
        )
        val SYMBOL_ROWS = listOf(
            "1234567890".map { it.toString() },
            listOf("@", "#", "£", "$", "_", "&", "-", "+", "(", ")", "/"),
            listOf("=", "*", "\"", "'", ":", ";", "!", "?", "%", "<", ">"),
            listOf("[", "]", "{", "}", "\\", "|", "~", "^"),
        )
    }
}
