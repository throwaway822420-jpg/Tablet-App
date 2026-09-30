package app.slate.tablet.write

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.slate.tablet.ai.ClaudeFailure
import java.util.concurrent.Executors

/** Receives pen activity from a [WriteView]. */
interface InkListener {
    fun onStrokeStarted()
    fun onInkChanged()
}

/**
 * Converts the ink with Claude and hands the result on when Enter (or =) is pressed.
 *
 * To hide the API round trip, the ink is converted as soon as the pen pauses. If the ink hasn't
 * changed by the time Enter is pressed, that result (or the request still in flight) is used, so
 * the result usually appears straight away. Any new stroke makes the earlier result stale; it's
 * ignored and a fresh request goes out at the next pause.
 *
 * With [clearOnEnter] (writing), Enter takes the ink off the page at once so you can keep writing;
 * each Enter's text is typed in order as it's ready, and writing that fails comes back to the page.
 * Without it (calculator), the ink stays and the result is shown and delivered in place.
 *
 * All methods run on the main thread.
 */
class InkConverter<R : Any>(
    private val ink: Ink,
    private val apiKey: () -> String,
    /** Blocking conversion of a PNG of the ink; runs on a worker thread. */
    private val convert: (key: String, png: ByteArray) -> R,
    private val isEmpty: (R) -> Boolean,
    /** Hands the result on; call done(null) on success or done(message) on failure. */
    private val deliver: (R, done: (String?) -> Unit) -> Unit,
    private val clearOnEnter: Boolean,
    /** Redraws the ink after the converter changed it. */
    private val redraw: () -> Unit,
    private val onState: (State<R>) -> Unit,
) : InkListener {
    sealed interface State<out R> {
        data object Empty : State<Nothing>
        data object Writing : State<Nothing>
        data object Converting : State<Nothing>
        data class Ready<R>(val result: R) : State<R>
        data class Delivering<R>(val result: R) : State<R>
        data class Delivered<R>(val result: R, val ms: Long) : State<R>
        data class Failed(val message: String) : State<Nothing>
    }

    /** Ink already taken off the page by Enter, waiting to be converted and typed. */
    private class Queued<R>(val version: Int, val strokes: List<Stroke>, val enterAtMs: Long) {
        var result: R? = null
        var error: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val pause = Runnable { startRequest() }

    private var resultVersion = -1
    private var result: R? = null
    private val inFlight = HashSet<Int>()

    // Calculator (ink stays on the page).
    private var deliveringVersion = -1
    private var enterVersion = -1
    private var enterAtMs = 0L

    // Writing (ink cleared on Enter).
    private val queue = ArrayDeque<Queued<R>>()
    private var queueDelivering = false

    /** The latest result for the current ink, if any. */
    val current: R? get() = result.takeIf { resultVersion == ink.version }

    override fun onStrokeStarted() {
        main.removeCallbacks(pause)
        // Open the connection to Anthropic while you write, so the first conversion doesn't wait for it.
        app.slate.tablet.ai.Claude.warmUp(apiKey())
        enterVersion = -1
        if (queue.isEmpty()) onState(State.Writing)
    }

    override fun onInkChanged() {
        main.removeCallbacks(pause)
        enterVersion = -1
        if (ink.isEmpty) {
            if (queue.isEmpty()) onState(State.Empty)
            return
        }
        if (queue.isEmpty()) onState(State.Writing)
        if (apiKey().isNotBlank()) main.postDelayed(pause, PAUSE_MS)
    }

    /** Re-announce the current state, e.g. after switching to this converter. */
    fun refresh() {
        val r = current
        onState(
            when {
                ink.isEmpty -> State.Empty
                r != null -> State.Ready(r)
                else -> State.Writing
            },
        )
        if (!ink.isEmpty && r == null && ink.version !in inFlight && apiKey().isNotBlank()) main.postDelayed(pause, PAUSE_MS)
    }

    fun enter() {
        if (ink.isEmpty) return
        if (apiKey().isBlank()) {
            onState(State.Failed("Add your Anthropic API key on Slate's main screen first."))
            return
        }
        main.removeCallbacks(pause)
        val v = ink.version
        if (clearOnEnter) {
            val q = Queued<R>(v, ink.snapshot(), SystemClock.uptimeMillis())
            q.result = current
            queue.addLast(q)
            if (q.result == null && v !in inFlight) request(v, q.strokes)
            ink.clear()
            redraw()
            pump()
            return
        }
        if (deliveringVersion == v) return // already on its way
        enterVersion = v
        enterAtMs = SystemClock.uptimeMillis()
        val r = current
        when {
            r != null -> deliverNow(r)
            v in inFlight -> onState(State.Converting)
            else -> startRequest()
        }
    }

    fun cancelPending() = main.removeCallbacks(pause)

    private fun startRequest() {
        if (ink.isEmpty || apiKey().isBlank()) return
        val v = ink.version
        if (resultVersion == v || v in inFlight) return
        if (enterVersion == v) onState(State.Converting)
        request(v, ink.snapshot())
    }

    private fun request(v: Int, strokes: List<Stroke>) {
        val key = apiKey()
        if (key.isBlank()) return
        inFlight += v
        val started = SystemClock.uptimeMillis()
        POOL.execute {
            val outcome = try {
                val png = InkRenderer.toPng(strokes) ?: throw ClaudeFailure("Nothing to read.")
                Result.success(convert(key, png))
            } catch (e: ClaudeFailure) {
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed", e)
                Result.failure(ClaudeFailure("Conversion failed: ${e.message}"))
            }
            Log.i(TAG, "Conversion for v$v took ${SystemClock.uptimeMillis() - started} ms")
            main.post { onResult(v, outcome) }
        }
    }

    private fun onResult(v: Int, outcome: Result<R>) {
        inFlight -= v
        queue.firstOrNull { it.version == v }?.let { q ->
            outcome.onSuccess { q.result = it }.onFailure { q.error = it.message ?: "Conversion failed." }
            pump()
            return
        }
        if (v != ink.version) return // the ink changed since; a newer request will follow
        outcome.onSuccess { r ->
            resultVersion = v
            result = r
            if (enterVersion == v) deliverNow(r) else onState(State.Ready(r))
        }.onFailure { e ->
            // A failed speculative request is retried when Enter is pressed; only report it then.
            if (enterVersion == v) {
                enterVersion = -1
                onState(State.Failed(e.message ?: "Conversion failed."))
            }
        }
    }

    /** Types queued writing in the order Enter was pressed, one at a time. */
    private fun pump() {
        if (queueDelivering) return
        val q = queue.firstOrNull() ?: return
        val r = q.result
        val error = q.error ?: if (r != null && isEmpty(r)) "Couldn't read any writing there." else null
        if (error != null) {
            queue.removeFirst()
            giveBack(q, error)
            pump()
            return
        }
        if (r == null) {
            onState(State.Converting)
            return
        }
        queueDelivering = true
        onState(State.Delivering(r))
        deliver(r) { err ->
            queueDelivering = false
            queue.remove(q)
            if (err != null) giveBack(q, err) else onState(State.Delivered(r, SystemClock.uptimeMillis() - q.enterAtMs))
            pump()
        }
    }

    /** Writing that couldn't be typed goes back on the page, so it isn't lost. */
    private fun giveBack(q: Queued<R>, error: String) {
        ink.restore(q.strokes)
        redraw()
        onInkChanged()
        onState(State.Failed(error))
    }

    private fun deliverNow(r: R) {
        enterVersion = -1
        if (isEmpty(r)) {
            onState(State.Failed("Couldn't read any writing there."))
            return
        }
        val v = ink.version
        deliveringVersion = v
        onState(State.Delivering(r))
        deliver(r) { error ->
            if (deliveringVersion == v) deliveringVersion = -1
            onState(if (error != null) State.Failed(error) else State.Delivered(r, SystemClock.uptimeMillis() - enterAtMs))
        }
    }

    private companion object {
        const val TAG = "SlateConvert"
        const val PAUSE_MS = 500L
        val POOL = Executors.newCachedThreadPool { r -> Thread(r, "slate-convert").apply { isDaemon = true } }
    }
}
