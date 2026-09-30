package app.slate.tablet.write

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.slate.tablet.link.SlateLink

/** Drawing-surface target: the PC types the text; calculator answers go to both clipboards. */
class PcTarget(private val context: Context, private val addSpace: () -> Boolean) : WriteTarget {
    override val supportsEquation = true

    override fun type(t: Transcript, mode: OutputMode, done: (String?) -> Unit) =
        SlateLink.sendText(t.wire(mode, addSpace()), done)

    override fun copy(answer: Transcript, mode: OutputMode, done: (String?) -> Unit) {
        val plain = answer.plain(if (mode == OutputMode.EQUATION) OutputMode.UNICODE else mode)
        copyToTablet(context, plain)
        if (SlateLink.status.phase != SlateLink.Phase.CONNECTED) {
            done(null) // tablet clipboard only
            return
        }
        val mathml = if (mode == OutputMode.EQUATION) answer.segments.firstOrNull()?.mathml else null
        SlateLink.sendText(Transcript.clipboardWire(plain, mathml), done)
    }

    companion object {
        fun copyToTablet(context: Context, text: String) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Slate", text))
        }
    }
}
