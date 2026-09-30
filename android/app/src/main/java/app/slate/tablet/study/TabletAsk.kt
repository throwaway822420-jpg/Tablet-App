package app.slate.tablet.study

import android.util.Base64
import app.slate.tablet.ai.Claude
import app.slate.tablet.ai.Requests
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams

/** Asking Claude Opus 5.5 straight from the tablet (no PC): used for tablet screenshots and when offline from the PC. */
object TabletAsk {
    const val MODEL = "claude-opus-5-5"

    val INTENTS = mapOf(
        "ask" to "Answer the question I've written on it.",
        "explain" to "Explain what I've circled or highlighted: what it means and why, clearly enough that I understand it.",
        "steps" to "Work through what I've marked step by step, explaining the reasoning at each step.",
        "hint" to "Give me a hint only: nudge me towards the next step without giving the answer away.",
        "check" to "Check my working on what I've marked: say what's right, and point out the first mistake and why without redoing everything for me.",
        "quiz" to "Quiz me on this: ask me 3 short questions of increasing difficulty to test my understanding, and don't give the answers yet.",
        "followup" to "This is a handwritten follow-up to our conversation. Read it and reply.",
    )

    // Same standing instructions as the PC's Claude Code sessions (Slate.Core/Ask.cs).
    val SYSTEM = """
        You are helping a student who is studying on a tablet. Each question arrives as a screenshot of
        what they're working on, with their pen annotations drawn on top: circles, underlines or
        highlights mark what they mean, and their handwritten question is usually written on the image.
        Sometimes a zoomed crop of the circled area is included too. Read the handwriting as their words.

        Answer as a patient, precise tutor. Be concise and lead with the point. Format for a Markdown
        viewer: headings only when useful, $…$ and $$…$$ for maths (LaTeX), and ```mermaid blocks when a
        diagram genuinely helps. Don't describe the screenshot back to them unless it helps the answer.
        Respect the intent they chose: a hint means a hint, not the answer.
    """.trimIndent()

    /** Blocking. Earlier answers in the session are included as text so follow-ups have context. */
    fun ask(apiKey: String, intent: String, images: List<Pair<String, ByteArray>>, history: List<StudyStore.Entry>): Pair<String, Double> {
        val b = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            .addBeta(Requests.FALLBACK_BETA)
            .fallbacks(Requests.fallbacks())
            .system(SYSTEM)
        for (h in history.takeLast(6)) {
            b.addUserMessage("(Earlier question, image not repeated) " + (INTENTS[h.intent] ?: INTENTS.getValue("ask")))
            b.addAssistantMessage(h.markdown.ifBlank { "(no answer)" })
        }
        val content = images.map { (_, bytes) -> Requests.image(Base64.encodeToString(bytes, Base64.NO_WRAP), png = false) } +
            BetaContentBlockParam.ofText(INTENTS[intent] ?: INTENTS.getValue("ask"))
        b.addUserMessageOfBetaContentBlockParams(content)
        val message = Claude.call(apiKey, b.build())
        val u = message.usage()
        val cost = app.slate.tablet.ai.Spend.cost(message.model().asString(), u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L))
        return Claude.text(message).trim() to cost
    }
}
