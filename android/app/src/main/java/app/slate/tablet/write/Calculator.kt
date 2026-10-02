package app.slate.tablet.write

import android.util.Base64
import app.slate.tablet.ai.Claude
import app.slate.tablet.ai.ClaudeFailure
import app.slate.tablet.ai.Requests
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONException
import org.json.JSONObject

/** A worked calculation. [answer] is in all three output formats; [checked] means the tablet re-computed it. */
data class Calculation(
    val question: Transcript,
    val answer: Transcript,
    val steps: List<String>,
    val approx: String?,
    val checked: Boolean,
) {
    companion object {
        /** Parses the JSON reply and double-checks plain arithmetic locally. */
        fun fromJson(json: String): Calculation {
            val o = JSONObject(json)
            fun part(name: String): Transcript {
                val p = o.getJSONObject(name)
                return Transcript(listOf(Transcript.Segment(true, p.getString("text"), p.optString("latex"), p.optString("mathml"))))
            }
            val steps = o.getJSONArray("steps").let { a -> (0 until a.length()).map { a.getString(it) } }
            var answer = part("result")
            var checked = false
            var approx: String? = null
            val local = Arithmetic.evaluate(o.optString("arithmetic"))
            if (local != null) {
                val claudeValue = Arithmetic.evaluate(answer.plain(OutputMode.UNICODE))
                if (claudeValue != null && !Arithmetic.agrees(claudeValue, local)) {
                    // The tablet's own arithmetic wins over the model's.
                    val v = Arithmetic.format(local)
                    answer = Transcript(listOf(Transcript.Segment(true, v, v.replace('−', '-'), "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"><mn>$v</mn></math>")))
                }
                // For exact forms like √2, 1/3 or π/4, also show the decimal.
                val shown = answer.plain(OutputMode.UNICODE)
                if (shown.toDoubleOrNull() == null && shown.replace('−', '-').toDoubleOrNull() == null) approx = Arithmetic.format(local)
                checked = true
            }
            return Calculation(part("expression"), answer, steps, approx, checked)
        }
    }
}

/** Handwritten calculation → worked answer, using Claude Opus 5.5. */
object Calculator {
    const val MODEL = "claude-opus-5-5"

    fun solve(apiKey: String, png: ByteArray): Calculation {
        val message = Claude.call(apiKey, request(Base64.encodeToString(png, Base64.NO_WRAP)))
        return try {
            Calculation.fromJson(Claude.text(message))
        } catch (e: JSONException) {
            throw ClaudeFailure("Unexpected reply from Claude.")
        }
    }

    /** The same through Claude Code in Termux (the Claude subscription). Blocking. */
    fun viaClaudeCode(context: android.content.Context, png: ByteArray): Calculation {
        val json = app.slate.tablet.ai.TermuxClaude.convert(
            context, "opus", "medium", SYSTEM_PROMPT, "Work out this handwritten calculation.", org.json.JSONObject(SCHEMA), png,
        )
        return try {
            Calculation.fromJson(json)
        } catch (e: JSONException) {
            throw ClaudeFailure("Unexpected reply from Claude Code.")
        }
    }

    fun request(pngBase64: String): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(16000L)
        // Opus 5.5 always thinks; medium is its default, set explicitly. Enough for school and university maths.
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).format(Requests.jsonFormat(SCHEMA)).build())
        .addBeta(Requests.FALLBACK_BETA)
        .fallbacks(Requests.fallbacks())
        .system(SYSTEM_PROMPT)
        .addUserMessageOfBetaContentBlockParams(
            listOf(Requests.image(pngBase64), BetaContentBlockParam.ofText("Work out this handwritten calculation.")),
        )
        .build()

    private val MATH = Requests.obj("text" to Requests.STRING, "latex" to Requests.STRING, "mathml" to Requests.STRING)

    private val SCHEMA = Requests.obj(
        "expression" to MATH,
        "result" to MATH,
        "arithmetic" to Requests.STRING,
        "steps" to mapOf("type" to "array", "items" to Requests.STRING),
    )

    private val SYSTEM_PROMPT = """
        The image is a handwritten calculation. Read it and work it out.

        - "expression": what was written, in three forms: "text" as plain Unicode (x², √, ×, ÷, π,
          fractions as a/b), "latex" without $ signs, and "mathml" as one presentation MathML
          <math xmlns="http://www.w3.org/1998/Math/MathML"> element.
        - "result": the answer in the same three forms. Give exact values where natural (1/3, √2,
          π/4) and plain decimals for decimal inputs. For an equation, give the solution(s), e.g.
          x = 3 or x = −2, x = 5. For an expression to simplify, differentiate or integrate, give
          the simplified result (with + C for indefinite integrals). An "=" with nothing after it
          means "work this out".
        - "arithmetic": if it's plain arithmetic with numbers only, the same calculation as a
          single ASCII expression using + - * / ^ ( ) sqrt() pi, e.g. (3.5+2)*4^2/sqrt(2).
          Otherwise an empty string. The tablet uses it to double-check the number.
        - "steps": for anything beyond one-line arithmetic, the key working as short lines in
          plain Unicode (at most 6). Empty for simple arithmetic.

        If the writing isn't a calculation you can work out, set "result" text to a short
        explanation and leave the other fields empty.
    """.trimIndent()
}
