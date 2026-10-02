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

    private var typing: TypingKeys? = null

    override fun onCreateInputView(): View {
        val prefs = Prefs(this)
        fun key(label: String, action: () -> Unit) = WritePanel.makeButton(this, label, onClick = action)
        val p = WritePanel(
            this, target, prefs.panelSettings(), vertical = false,
            extraButtons = listOf(key("⌨ Type") { showTyping(true) }),
            trailingButtons = listOf(
                key("Space") { currentInputConnection?.commitText(" ", 1) },
                key("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                key("↵") { newLineOrAction() },
            ),
        )
        panel = p
        // Typing and handwriting live in one keyboard, so switching between them is instant.
        val t = TypingKeys(this, object : TypingKeys.Actions {
            override fun type(text: String) {
                currentInputConnection?.commitText(text, 1)
            }
            override fun backspace() = deleteBack()
            override fun enter() = newLineOrAction()
            override fun toHandwriting() = showTyping(false)
            override fun toOtherKeyboard() = switchKeyboard()
        }).apply { setBackgroundColor(android.graphics.Color.rgb(30, 32, 36)) }
        typing = t
        val height = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        return FrameLayout(this).apply {
            addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }.also { showTyping(prefs.keyboardTyping) }
    }

    private fun showTyping(on: Boolean) {
        panel?.visibility = if (on) View.GONE else View.VISIBLE
        typing?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) panel?.pause() else panel?.writeView?.invalidate()
        Prefs(this).keyboardTyping = on
        if (on) updateAutoCaps()
    }

    /** Deletes the selection, or the character before the cursor (a whole emoji or surrogate pair). */
    private fun deleteBack() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Shift on at the start of a field or a sentence, where the field asks for it. */
    private fun updateAutoCaps() {
        val t = typing ?: return
        if (t.visibility != View.VISIBLE) return
        val info = currentInputEditorInfo ?: return t.autoCapitalise(false)
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        t.autoCapitalise(caps != 0)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        panel?.pause()
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel?.writeView?.invalidate()
        updateAutoCaps()
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
