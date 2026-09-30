package app.slate.tablet.write

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.slate.tablet.link.SlateLink
import java.util.concurrent.Executors

/**
 * Turns the ink into text and sends it to the PC.
 *
 * To hide the API round trip, the ink is sent for recognition as soon as the pen pauses. If the
 * ink hasn't changed by the time Enter is pressed, that result (or the request still in flight)
 * is used, so the text usually appears straight away. Any new stroke makes the earlier result
 * stale; it's ignored and a fresh request goes out at the next pause.
 *
 * All methods run on the main thread.
 */
class WriteController(
    private val ink: Ink,
    private val apiKey: () -> String,
    private val addSpace: () -> Boolean,
    private val onState: (State) -> Unit,
) {
    sealed interface State {
        data object Empty : State
        data object Writing : State
        data object Converting : State
        data class Preview(val text: String) : State
        data class Typing(val text: String) : State
        data class Typed(val text: String, val ms: Long) : State
        data class Failed(val message: String) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "slate-recognize").apply { isDaemon = true } }
    private val pause = Runnable { startRequest() }

    private var recognizer: Recognizer? = null
    private var recognizerKey = ""

    /** Latest finished result, and the ink version it was made from. */
    private var resultVersion = -1
    private var resultText = ""

    /** Version of the ink the request in flight was made from, or -1. */
    private var inFlightVersion = -1

    /** Ink version whose text is on its way to the PC, or -1. */
    private var typingVersion = -1

    /** Enter was pressed and we're waiting for the result for [enterVersion]. */
    private var enterVersion = -1
    private var enterAtMs = 0L

    fun onStrokeStarted() {
        main.removeCallbacks(pause)
        enterVersion = -1
        onState(State.Writing)
    }

    /** After a stroke ends, or undo / clear / erase. */
    fun onInkChanged() {
        main.removeCallbacks(pause)
        enterVersion = -1
        if (ink.isEmpty) {
            onState(State.Empty)
            return
        }
        onState(State.Writing)
        if (apiKey().isNotBlank()) main.postDelayed(pause, PAUSE_MS)
    }

    fun enter() {
        if (ink.isEmpty) return
        if (apiKey().isBlank()) {
            onState(State.Failed("Add your Anthropic API key on Slate's main screen first."))
            return
        }
        main.removeCallbacks(pause)
        val v = ink.version
        if (typingVersion == v) return // already sent; waiting for the PC to confirm
        enterVersion = v
        enterAtMs = SystemClock.uptimeMillis()
        when {
            resultVersion == v -> deliver(resultText)
            inFlightVersion == v -> onState(State.Converting)
            else -> startRequest()
        }
    }

    fun shutdown() {
        main.removeCallbacks(pause)
        pool.shutdownNow()
    }

    private fun startRequest() {
        val key = apiKey()
        if (ink.isEmpty || key.isBlank()) return
        val v = ink.version
        if (resultVersion == v || inFlightVersion == v) return
        val strokes = ink.snapshot()
        val rec = recognizerFor(key)
        inFlightVersion = v
        if (enterVersion == v) onState(State.Converting)
        val started = SystemClock.uptimeMillis()
        pool.execute {
            val outcome = try {
                val png = InkRenderer.toPng(strokes) ?: throw Recognizer.Failure("Nothing to read.")
                Result.success(rec.recognize(png))
            } catch (e: Recognizer.Failure) {
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                Result.failure(Recognizer.Failure("Recognition failed: ${e.message}"))
            }
            Log.i(TAG, "Recognition for v$v took ${SystemClock.uptimeMillis() - started} ms")
            main.post { onResult(v, outcome) }
        }
    }

    private fun onResult(v: Int, outcome: Result<String>) {
        if (inFlightVersion == v) inFlightVersion = -1
        if (v != ink.version) return // the ink changed since; a newer request will follow
        outcome.onSuccess { text ->
            resultVersion = v
            resultText = text
            if (enterVersion == v) deliver(text) else onState(State.Preview(text))
        }.onFailure { e ->
            // A failed speculative request is retried when Enter is pressed; only report it then.
            if (enterVersion == v) {
                enterVersion = -1
                onState(State.Failed(e.message ?: "Recognition failed."))
            }
        }
    }

    private fun deliver(recognized: String) {
        enterVersion = -1
        if (recognized.isEmpty()) {
            onState(State.Failed("Couldn't read any writing there."))
            return
        }
        val text = if (addSpace() && !recognized.last().isWhitespace()) "$recognized " else recognized
        val deliveredVersion = ink.version
        typingVersion = deliveredVersion
        onState(State.Typing(recognized))
        SlateLink.sendText(text) { error ->
            if (typingVersion == deliveredVersion) typingVersion = -1
            if (error != null) {
                onState(State.Failed(error))
                return@sendText
            }
            // Only clear if nothing was written while the text was on its way.
            if (ink.version == deliveredVersion) ink.clear()
            onState(State.Typed(recognized, SystemClock.uptimeMillis() - enterAtMs))
        }
    }

    private fun recognizerFor(key: String): Recognizer {
        val current = recognizer
        if (current != null && recognizerKey == key) return current
        return Recognizer(key).also {
            recognizer = it
            recognizerKey = key
        }
    }

    private companion object {
        const val TAG = "SlateWrite"
        const val PAUSE_MS = 800L
    }
}
