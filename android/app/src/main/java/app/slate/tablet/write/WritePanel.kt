package app.slate.tablet.write

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.slate.tablet.ai.Spend
import app.slate.tablet.write.InkConverter.State

/** Where a write panel's results go: the PC (drawing surface) or the focused Android field (keyboard). */
interface WriteTarget {
    /** False when the target can't paste real equations (Android fields): Equation mode then types Unicode. */
    val supportsEquation: Boolean

    /** Types the transcription. */
    fun type(t: Transcript, mode: OutputMode, done: (String?) -> Unit)

    /** Copies a calculator answer to the clipboard(s). */
    fun copy(answer: Transcript, mode: OutputMode, done: (String?) -> Unit)
}

/**
 * Handwriting panel shared by the drawing surface and the Slate Keyboard: an ink surface, a status
 * line, and buttons for Enter / =, output mode, calculator, Undo and Clear. [extraButtons] are
 * added at the start of the button bar (e.g. Pen, or the keyboard's own keys).
 */
@SuppressLint("ViewConstructor")
class WritePanel(
    context: Context,
    private val target: WriteTarget,
    private val settings: Settings,
    vertical: Boolean,
    extraButtons: List<Button> = emptyList(),
    trailingButtons: List<Button> = emptyList(),
) : FrameLayout(context) {

    /** Persistent choices the panel reads and writes. */
    interface Settings {
        val apiKey: String
        val dark: Boolean
        var outputMode: OutputMode
        val addSpace: Boolean
        /** Read handwriting with the faster, cheaper model. */
        val fast: Boolean get() = false
    }

    val ink = Ink()
    val writeView = WriteView(context, ink) { settings.dark }
    private val status = TextView(context)
    private val enterButton: Button
    private val modeButton: Button
    private val calcButton: Button
    private val typeItButton: Button
    private var calcMode = false

    private val writer = InkConverter(
        ink, { settings.apiKey },
        convert = { key, png -> Recognizer.recognize(key, png, settings.fast) },
        isEmpty = { it.isEmpty },
        deliver = { t, done -> target.type(t, effectiveMode(), done) },
        clearOnEnter = true,
        redraw = { writeView.invalidate() },
        onState = ::showWriteState,
    )

    private val calculator = InkConverter(
        ink, { settings.apiKey },
        convert = { key, png -> Calculator.solve(key, png) },
        isEmpty = { it.answer.isEmpty },
        deliver = { c, done -> target.copy(c.answer, effectiveMode(), done) },
        clearOnEnter = false,
        redraw = { writeView.invalidate() },
        onState = ::showCalcState,
    )

    init {
        writeView.listener = writer
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (vertical) 18f else 15f)
            gravity = Gravity.CENTER
            maxLines = if (vertical) 8 else 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        enterButton = button("Enter", accent = true) { active().enter() }
        modeButton = button("") {
            settings.outputMode = settings.outputMode.next()
            updateButtons()
            active().refresh()
        }
        calcButton = button("Calc") { setCalcMode(!calcMode) }
        typeItButton = button("Type it") {
            calculator.current?.let { c -> target.type(c.answer, effectiveMode()) { err -> status.text = err ?: "Typed ${c.answer.plain(effectiveMode())}" } }
        }
        val undo = button("Undo") { ink.undo(); inkEdited() }
        val clear = button("Clear") { ink.clear(); inkEdited() }

        val bar = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            (extraButtons + listOf(enterButton, modeButton, calcButton, typeItButton, undo, clear) + trailingButtons).forEach { b ->
                addView(b, if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { topMargin = dp(6) }
                else LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
        }
        addView(writeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        if (vertical) {
            addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        } else {
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(4) })
        }
        updateButtons()
        showWriteState(State.Empty)
    }

    private fun active(): InkConverter<*> = if (calcMode) calculator else writer

    private fun effectiveMode(): OutputMode =
        if (settings.outputMode == OutputMode.EQUATION && !target.supportsEquation) OutputMode.UNICODE else settings.outputMode

    fun setCalcMode(on: Boolean) {
        if (calcMode == on) return
        active().cancelPending()
        calcMode = on
        writeView.listener = active()
        updateButtons()
        active().refresh()
    }

    /** Stop any pending background conversion (e.g. when the panel is hidden). */
    fun pause() = active().cancelPending()

    private fun inkEdited() {
        writeView.invalidate()
        active().onInkChanged()
    }

    private fun updateButtons() {
        enterButton.text = if (calcMode) "=" else "Enter"
        val mode = settings.outputMode
        modeButton.text = when (mode) {
            OutputMode.UNICODE -> "x² Unicode"
            OutputMode.EQUATION -> if (target.supportsEquation) "∑ Equation" else "∑ Eq→Unicode"
            OutputMode.LATEX -> "\\TeX"
        }
        style(calcButton, highlighted = calcMode)
        typeItButton.visibility = if (calcMode) View.VISIBLE else View.GONE
    }

    private fun showWriteState(s: State<Transcript>) {
        if (calcMode) return
        val mode = effectiveMode()
        setStatus(
            when (s) {
                State.Empty -> if (settings.apiKey.isBlank()) "Add your Anthropic API key on Slate's main screen to turn writing into text."
                else "Write here, then tap Enter. The eraser end or the S Pen button erases."
                State.Writing -> ""
                State.Converting -> "Converting…"
                is State.Ready -> if (s.result.isEmpty) "" else "→ " + s.result.plain(mode)
                is State.Delivering -> "Typing: " + s.result.plain(mode)
                is State.Delivered -> "Typed: ${s.result.plain(mode)} (${s.ms} ms)"
                is State.Failed -> s.message
            },
            error = s is State.Failed,
        )
        if (s is State.Delivered) writeView.invalidate()
    }

    private fun showCalcState(s: State<Calculation>) {
        if (!calcMode) return
        val mode = effectiveMode()
        fun describe(c: Calculation, suffix: String) = buildString {
            append(c.question.plain(OutputMode.UNICODE)).append(" = ").append(c.answer.plain(mode))
            c.approx?.let { append("  ≈ ").append(it) }
            if (c.checked) append("  ✓")
            if (suffix.isNotEmpty()) append("\n").append(suffix)
            c.steps.forEach { append("\n").append(it) }
        }
        setStatus(
            when (s) {
                State.Empty -> "Calculator: write a calculation, then tap =. The answer is copied to the tablet and PC clipboards."
                State.Writing -> ""
                State.Converting -> "Working it out…"
                is State.Ready -> describe(s.result, "")
                is State.Delivering -> describe(s.result, "Copying…")
                is State.Delivered -> describe(s.result, "Copied (${s.ms} ms) · spent this month: $%.2f".format(Spend.thisMonthUsd()))
                is State.Failed -> s.message
            },
            error = s is State.Failed,
        )
    }

    private fun setStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(
            when {
                error -> Color.rgb(230, 90, 80)
                settings.dark -> Color.rgb(190, 190, 190)
                else -> Color.rgb(70, 70, 70)
            },
        )
    }

    private fun button(label: String, accent: Boolean = false, onClick: () -> Unit) =
        makeButton(context, label, accent, onClick)

    private fun style(b: Button, highlighted: Boolean) {
        (b.background as GradientDrawable).setColor(if (highlighted) ACCENT else NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        val ACCENT = Color.rgb(40, 130, 90)
        val NORMAL = Color.argb(215, 60, 64, 72)

        /** The rounded button style used on Slate's drawing surfaces. */
        fun makeButton(context: Context, label: String, accent: Boolean = false, onClick: () -> Unit) = Button(context).apply {
            val d = context.resources.displayMetrics.density
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            minWidth = (72 * d).toInt()
            minimumWidth = (72 * d).toInt()
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(if (accent) ACCENT else NORMAL)
            }
            setOnClickListener { onClick() }
        }
    }
}
