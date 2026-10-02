package app.slate.tablet.ai

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Claude Code running in Termux on this tablet, signed in with the user's Claude subscription,
 * reached through Slate's small bridge script (assets/termux/slate-bridge.js) on 127.0.0.1.
 */
object TermuxClaude {
    const val PORT = 47820

    @Volatile private var checkedAt = 0L
    @Volatile private var lastOk = false

    /** The secret shared with the bridge at setup, so other apps can't use the subscription through it. */
    fun token(context: Context): String {
        val p = context.getSharedPreferences("slate_termux", Context.MODE_PRIVATE)
        p.getString("token", null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString("token", t).apply()
        return t
    }

    /**
     * True if the bridge is running and has our token; cached for a few seconds. Off the main thread
     * it checks (≤ 0.5 s); on the main thread it answers from the cache and refreshes in the background.
     */
    fun available(context: Context, fresh: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!fresh && now - checkedAt < 4000) return lastOk
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh(context)
            return lastOk
        }
        lastOk = try {
            val c = open("/health", context, timeoutMs = 500)
            val ok = c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("authorised")
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
        checkedAt = now
        return lastOk
    }

    /** Re-checks in the background (e.g. when a Slate screen opens), so main-thread answers are current. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        thread(name = "slate-termux-check", isDaemon = true) { available(app, fresh = true) }
    }

    /**
     * Sends a question; each streamed line (ask.status / ask.delta / ask.done, the same messages the
     * PC sends) goes to [onLine]. Blocking. Returns an error message, or null when it finished.
     */
    fun ask(context: Context, body: JSONObject, onLine: (JSONObject) -> Unit): String? = try {
        val c = open("/ask", context, timeoutMs = 10_000, readTimeoutMs = 10 * 60_000)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            runCatching { JSONObject(err).optString("error") }.getOrNull()?.ifBlank { null } ?: "Claude Code in Termux answered ${c.responseCode}."
        } else {
            BufferedReader(InputStreamReader(c.inputStream)).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line -> runCatching { JSONObject(line) }.getOrNull()?.let(onLine) }
            }
            null
        }
    } catch (e: Exception) {
        checkedAt = 0
        "Lost Claude Code in Termux: ${e.message}. Is slate-claude still running?"
    }

    /** One image in, structured JSON out (handwriting, calculator). Blocking. */
    fun convert(context: Context, model: String, effort: String, system: String, prompt: String, schema: JSONObject, png: ByteArray): String {
        val body = JSONObject()
            .put("model", model).put("effort", effort).put("system", system).put("prompt", prompt)
            .put("schema", schema).put("type", "image/png")
            .put("image", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
        val c = try {
            open("/convert", context, timeoutMs = 3000, readTimeoutMs = 3 * 60_000).apply {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        } catch (e: Exception) {
            checkedAt = 0
            throw ClaudeFailure("Couldn't reach Claude Code in Termux. Is slate-claude running?")
        }
        val text = (if (c.responseCode == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: throw ClaudeFailure("Unexpected reply from Claude Code in Termux.")
        if (!o.optBoolean("ok")) throw ClaudeFailure("Claude Code in Termux: " + o.optString("error"))
        return o.get("json").toString()
    }

    private fun open(path: String, context: Context, timeoutMs: Int, readTimeoutMs: Int = timeoutMs): HttpURLConnection =
        (URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("x-slate-token", token(context))
            setRequestProperty("content-type", "application/json")
            if (path != "/health") requestMethod = "POST"
        }

    // --- One-time setup: everything travels in the copied command (no server, works offline) ---

    /**
     * One line to paste into Termux. It carries the whole setup script, the bridge included, as
     * base64, because Slate can't serve files once it's in the background (Android freezes it).
     */
    fun setupCommand(context: Context): String {
        fun asset(name: String) = context.applicationContext.assets.open("termux/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        val script = setupScript(token(context), asset("slate-bridge.js"), asset("slate-claude.sh"), asset("slate-bridge-start.sh"))
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        return "echo $b64 | base64 -d | sh"
    }

    internal fun setupScript(token: String, bridge: String, launcher: String, starter: String): String {
        require(listOf(bridge, launcher, starter).none { "SLATE_EOF" in it.lines() }) { "a file contains the heredoc marker" }
        return """
        set -e
        echo "Setting up Slate's bridge to Claude Code…"
        command -v node >/dev/null 2>&1 || { echo "Installing Node.js…"; pkg install -y nodejs; }
        mkdir -p "${'$'}HOME/.slate"
        cat > "${'$'}HOME/.slate/slate-bridge.js" <<'SLATE_EOF'
        @BRIDGE@
        SLATE_EOF
        cat > "${'$'}HOME/.slate/slate-bridge-start" <<'SLATE_EOF'
        @STARTER@
        SLATE_EOF
        printf '%s' '$token' > "${'$'}HOME/.slate/token"
        chmod 600 "${'$'}HOME/.slate/token"
        cat > "${'$'}PREFIX/bin/slate-claude" <<'SLATE_EOF'
        @LAUNCHER@
        SLATE_EOF
        chmod +x "${'$'}PREFIX/bin/slate-claude"
        pkill -f 'node .*/[.]slate/slate-bridge[.]js$' 2>/dev/null || true
        echo "Done. Start it with:  slate-claude   (in Termux; it finds Claude Code in Termux or in your proot-distro Linux. Leave it running while you study.)"
        """.trimIndent()
            .replace("@BRIDGE@", bridge.trimEnd())
            .replace("@STARTER@", starter.trimEnd())
            .replace("@LAUNCHER@", launcher.trimEnd()) + "\n"
    }
}
