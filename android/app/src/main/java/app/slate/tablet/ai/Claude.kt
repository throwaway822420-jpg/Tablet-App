package app.slate.tablet.ai

import android.content.Context
import android.content.SharedPreferences
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration
import java.time.YearMonth

/** A Claude API call that failed, with a message fit to show the user. */
class ClaudeFailure(message: String) : Exception(message)

/** Shared Anthropic client, error mapping and spend tracking for every API call Slate makes. */
object Claude {
    private var client: AnthropicClient? = null
    private var clientKey = ""
    @Volatile private var lastUseMs = 0L

    @Synchronized
    fun client(apiKey: String): AnthropicClient {
        client?.takeIf { clientKey == apiKey }?.let { return it }
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(90))
            .maxRetries(1)
            .build()
            .also {
                client = it
                clientKey = apiKey
            }
    }

    /**
     * Opens (or keeps open) the HTTPS connection to Anthropic in the background with a free request,
     * so the next real call skips the connection and TLS set-up. Does nothing if one was used recently.
     */
    fun warmUp(apiKey: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (apiKey.isBlank() || now - lastUseMs < WARM_INTERVAL_MS) return
        lastUseMs = now
        Thread({ runCatching { client(apiKey).models().list() } }, "slate-warmup").apply { isDaemon = true }.start()
    }

    // OkHttp keeps idle connections for 5 minutes.
    private const val WARM_INTERVAL_MS = 4 * 60_000L

    /** Blocking call; maps API errors to [ClaudeFailure], records spend, rejects refusals. */
    fun call(apiKey: String, params: MessageCreateParams): BetaMessage {
        if (apiKey.isBlank()) throw ClaudeFailure("Add your Anthropic API key on Slate's main screen first.")
        lastUseMs = android.os.SystemClock.elapsedRealtime()
        val message = try {
            client(apiKey).beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ClaudeFailure("Anthropic rejected the API key. Check it on Slate's main screen.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeFailure("This API key can't use ${params.model().asString()}.")
        } catch (e: RateLimitException) {
            throw ClaudeFailure("Too many requests to Anthropic right now. Try again in a moment.")
        } catch (e: AnthropicServiceException) {
            throw ClaudeFailure("Anthropic API error ${e.statusCode()}: ${e.message}")
        } catch (e: AnthropicIoException) {
            throw ClaudeFailure("Couldn't reach Anthropic. Check the tablet's internet connection.")
        }
        Spend.record(message)
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw ClaudeFailure("Claude declined this request.")
        return message
    }

    /** All text blocks of a reply, joined. */
    fun text(message: BetaMessage): String = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
}

/**
 * Approximate API spend this month, from each reply's token usage. Covers the tablet's own API
 * calls; Claude Code and Claude Desktop questions use the Claude subscription instead.
 */
object Spend {
    // USD per million tokens (input, output, cache read), from Anthropic's price list.
    private val PRICES = mapOf(
        "claude-sonnet-5-5" to Triple(2.0, 10.0, 0.20),
        "claude-sonnet-5" to Triple(2.0, 10.0, 0.20),
        "claude-opus-5-5" to Triple(4.0, 20.0, 0.20),
        "claude-opus-5" to Triple(5.0, 25.0, 0.50),
        "claude-opus-4-8" to Triple(5.0, 25.0, 0.50),
        "claude-haiku-4-5" to Triple(1.0, 5.0, 0.10),
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("slate_spend", Context.MODE_PRIVATE)
    }

    fun cost(model: String, input: Long, output: Long, cacheRead: Long): Double {
        val (i, o, c) = PRICES.entries.firstOrNull { model.startsWith(it.key) }?.value ?: Triple(4.0, 20.0, 0.40)
        return (input * i + output * o + cacheRead * c) / 1_000_000.0
    }

    fun record(message: BetaMessage) {
        val u = message.usage()
        add(cost(message.model().asString(), u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L)))
    }

    @Synchronized
    private fun add(usd: Double) {
        val p = prefs ?: return
        val month = YearMonth.now().toString()
        val current = if (p.getString("month", "") == month) p.getFloat("usd", 0f).toDouble() else 0.0
        p.edit().putString("month", month).putFloat("usd", (current + usd).toFloat()).putInt("calls", callsThisMonth() + 1).apply()
    }

    fun thisMonthUsd(): Double {
        val p = prefs ?: return 0.0
        return if (p.getString("month", "") == YearMonth.now().toString()) p.getFloat("usd", 0f).toDouble() else 0.0
    }

    fun callsThisMonth(): Int {
        val p = prefs ?: return 0
        return if (p.getString("month", "") == YearMonth.now().toString()) p.getInt("calls", 0) else 0
    }
}
