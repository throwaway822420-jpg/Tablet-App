package app.slate.tablet.write

import app.slate.tablet.protocol.Protocol
import org.json.JSONObject

/** How maths in a transcription is written out. */
enum class OutputMode(val label: String) {
    /** Plain Unicode, e.g. dy/dx, x². Works everywhere. */
    UNICODE("Unicode"),

    /** Maths pasted as real equations (MathML) into Word/PowerPoint/OneNote; prose typed as normal. */
    EQUATION("Equation"),

    /** Maths as $LaTeX$ for Overleaf, Notion, Obsidian… */
    LATEX("LaTeX");

    fun next(): OutputMode = entries[(ordinal + 1) % entries.size]
}

/**
 * A transcription as Claude returned it: prose and maths pieces in order, each maths piece in
 * Unicode, LaTeX and MathML, so switching output mode needs no new request.
 */
data class Transcript(val segments: List<Segment>) {
    data class Segment(val isMath: Boolean, val text: String, val latex: String = "", val mathml: String = "")

    val isEmpty: Boolean get() = segments.all { it.text.isBlank() && it.latex.isBlank() }

    /** The text as it reads in [mode], for showing on the tablet or typing on Android. */
    fun plain(mode: OutputMode): String = buildString {
        for (s in segments) append(
            when {
                !s.isMath -> s.text
                mode == OutputMode.LATEX && s.latex.isNotBlank() -> "$" + s.latex.trim() + "$"
                else -> UnicodeMath.convert(s.text)
            },
        )
    }.trim()

    /**
     * The text for the PC to type. In [OutputMode.EQUATION] each maths piece is wrapped as
     * `MATH_START mathml MATH_FALLBACK unicode MATH_END`, and the PC pastes it as an equation.
     * Falls back to Unicode if the MathML won't fit in one TEXT message.
     */
    fun wire(mode: OutputMode, trailingSpace: Boolean): String {
        val out = if (mode != OutputMode.EQUATION) plain(mode) else buildString {
            for (s in segments) {
                if (!s.isMath || s.mathml.isBlank()) append(if (s.isMath) UnicodeMath.convert(s.text) else s.text)
                else append(MATH_START).append(s.mathml.trim()).append(MATH_FALLBACK).append(UnicodeMath.convert(s.text)).append(MATH_END)
            }
        }.trim()
        val fitted = if (mode == OutputMode.EQUATION && tooLong(out)) plain(OutputMode.UNICODE) else out
        return if (trailingSpace && fitted.isNotEmpty() && !fitted.last().isWhitespace() && fitted.last() != MATH_END) "$fitted " else fitted
    }

    companion object {
        // Private-use characters that never occur in handwriting; PROTOCOL.md "Rich text".
        const val MATH_START = ''
        const val MATH_END = ''
        const val MATH_FALLBACK = ''
        const val CLIPBOARD = ''
        const val CLIPBOARD_MATHML = ''

        /** A TEXT message asking the PC to put [plain] (and optionally MathML) on its clipboard. */
        fun clipboardWire(plain: String, mathml: String?): String =
            CLIPBOARD + plain + if (mathml.isNullOrBlank()) "" else CLIPBOARD_MATHML + mathml.trim()

        fun tooLong(wire: String) =
            wire.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT_CHUNKS * Protocol.TEXT_CHUNK_BYTES - 64

        fun of(text: String) = Transcript(if (text.isEmpty()) emptyList() else listOf(Segment(false, text)))

        /** Parses Claude's JSON reply: {"segments":[{"type","text","latex","mathml"}]}. */
        fun fromJson(json: String): Transcript {
            val arr = JSONObject(json).getJSONArray("segments")
            return Transcript((0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Segment(o.optString("type") == "math", o.optString("text"), o.optString("latex"), o.optString("mathml"))
            })
        }
    }
}
