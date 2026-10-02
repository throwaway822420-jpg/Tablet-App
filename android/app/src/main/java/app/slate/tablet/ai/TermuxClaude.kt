package app.slate.tablet.ai

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Claude Code running in Termux on this tablet, signed in with the user's Claude subscription,
 * reached through Slate's small bridge script (assets/termux/slate-bridge.js) on 127.0.0.1.
 */
object TermuxClaude {
    const val PORT = 47820
    const val SETUP_PORT = 47821

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

    // --- One-time setup: Slate serves the bridge to Termux over localhost ---

    @Volatile private var setupServer: ServerSocket? = null

    /** The command to paste into Termux while the setup server runs. */
    const val SETUP_COMMAND = "command -v curl >/dev/null || pkg install -y curl; curl -fsS http://127.0.0.1:$SETUP_PORT/setup | sh"

    fun startSetupServer(context: Context) {
        if (setupServer != null) return
        val app = context.applicationContext
        val script = setupScript(token(app))
        val bridge = app.assets.open("termux/slate-bridge.js").use { it.readBytes() }
        val server = try {
            ServerSocket(SETUP_PORT, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return
        }
        setupServer = server
        thread(name = "slate-termux-setup", isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                runCatching {
                    s.use {
                        val first = BufferedReader(InputStreamReader(it.getInputStream())).readLine().orEmpty()
                        val (status, type, data) = when {
                            first.startsWith("GET /setup ") -> Triple("200 OK", "text/x-shellscript", script.toByteArray())
                            first.startsWith("GET /slate-bridge.js ") -> Triple("200 OK", "application/javascript", bridge)
                            else -> Triple("404 Not Found", "text/plain", "not found".toByteArray())
                        }
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(data)
                        out.flush()
                    }
                }
            }
        }
    }

    fun stopSetupServer() {
        runCatching { setupServer?.close() }
        setupServer = null
    }

    internal fun setupScript(token: String) = """
        set -e
        echo "Setting up Slate's bridge to Claude Code…"
        command -v node >/dev/null 2>&1 || { echo "Installing Node.js…"; pkg install -y nodejs; }
        mkdir -p "${'$'}HOME/.slate"
        curl -fsS http://127.0.0.1:$SETUP_PORT/slate-bridge.js -o "${'$'}HOME/.slate/slate-bridge.js"
        printf '%s' '$token' > "${'$'}HOME/.slate/token"
        chmod 600 "${'$'}HOME/.slate/token"
        cat > "${'$'}PREFIX/bin/slate-claude" <<'EOS'
        #!/data/data/com.termux/files/usr/bin/sh
        termux-wake-lock 2>/dev/null || true
        exec node "${'$'}HOME/.slate/slate-bridge.js" "${'$'}@"
        EOS
        chmod +x "${'$'}PREFIX/bin/slate-claude"
        if ! command -v claude >/dev/null 2>&1; then
          echo "Claude Code isn't installed yet: run  npm install -g @anthropic-ai/claude-code  then  claude  once to sign in."
        fi
        echo "Done. Start it with:  slate-claude   (leave Termux running while you study)"
    """.trimIndent() + "\n"
}
