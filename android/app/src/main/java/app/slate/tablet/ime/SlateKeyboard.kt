package app.slate.tablet.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import app.slate.tablet.ui.Prefs
import app.slate.tablet.write.OutputMode
import app.slate.tablet.write.PcTarget
import app.slate.tablet.write.Transcript
import app.slate.tablet.write.WritePanel
import app.slate.tablet.write.WriteTarget

/**
 * Slate Keyboard: an Android keyboard that is a handwriting pad. Write with the S Pen, tap Enter,
 * and the text goes into whatever field is focused, in any app. Works without a PC.
 */
class SlateKeyboard : InputMethodService() {
    private var panel: WritePanel? = null

    private val target = object : WriteTarget {
        override val supportsEquation = false // Android text fields can't hold real equations

        override fun type(t: Transcript, mode: OutputMode, done: (String?) -> Unit) {
            val ic = currentInputConnection ?: return done("No text field is selected.")
            var text = t.plain(mode)
            if (Prefs(this@SlateKeyboard).addSpaceAfterText && text.isNotEmpty() && !text.last().isWhitespace()) text += " "
            ic.commitText(text, 1)
            done(null)
        }

        override fun copy(answer: Transcript, mode: OutputMode, done: (String?) -> Unit) {
            PcTarget.copyToTablet(this@SlateKeyboard, answer.plain(mode))
            done(null)
        }
    }

    override fun onCreateInputView(): View {
        val prefs = Prefs(this)
        fun key(label: String, action: () -> Unit) = WritePanel.makeButton(this, label, onClick = action)
        val p = WritePanel(
            this, target, prefs.panelSettings(), vertical = false,
            extraButtons = listOf(key("⌨") { switchKeyboard() }),
            trailingButtons = listOf(
                key("Space") { currentInputConnection?.commitText(" ", 1) },
                key("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                key("↵") { newLineOrAction() },
            ),
        )
        panel = p
        val height = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        return FrameLayout(this).apply {
            addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        panel?.pause()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel?.writeView?.invalidate()
    }

    /** Back to the previous keyboard; if there isn't one, show the keyboard picker. */
    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    /** Single-line fields (search, URL, chat): run their action. Multi-line: a new line. */
    private fun newLineOrAction() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiLine = info != null && (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }
}
