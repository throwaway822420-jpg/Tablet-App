package app.slate.tablet.study

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.slate.tablet.link.BulkLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Sends questions about the screen to Claude and keeps the history up to date. With a PC connected,
 * questions go to the PC (a Claude Code session, or the Claude Desktop app, per the PC's setting);
 * without one, the tablet asks Claude Opus 5.5 directly with the API key.
 */
object StudyHub {
    private const val TAG = "SlateStudy"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val live = ConcurrentHashMap<String, StringBuilder>()
    private var appContext: Context? = null

    lateinit var store: StudyStore
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = StudyStore(File(context.filesDir, "study"))
        BulkLink.addJsonListener(::onPcMessage)
    }

    /** Listener gets the session id whose history changed (main thread). */
    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)

    private fun changed(sessionId: String?) {
        if (sessionId == null) return
        main.post { listeners.forEach { it(sessionId) } }
    }

    /** Text streamed so far for a question still being answered. */
    fun liveText(askId: String): String? = live[askId]?.toString()

    /**
     * Asks Claude. [images] are (name, JPEG bytes); the first is kept as the history thumbnail.
     * Returns the local session id.
     */
    fun ask(sessionId: String?, intent: String, images: List<Pair<String, ByteArray>>, title: String, apiKey: String): String {
        val pc = BulkLink.connected
        val session = sessionId?.let { store.session(it) } ?: store.newSession(title.ifBlank { defaultTitle() }, if (pc) "pc" else "tablet")
        val askId = UUID.randomUUID().toString()
        val imageName = "$askId.jpg"
        File(store.dir(session.id), imageName).writeBytes(images.first().second)
        images.drop(1).forEachIndexed { i, (_, bytes) -> File(store.dir(session.id), "$askId-extra$i.jpg").writeBytes(bytes) }
        store.addEntry(session.id, StudyStore.Entry(askId, System.currentTimeMillis(), intent, imageName, "", "pending", "Sending…"))
        changed(session.id)

        if (pc && session.backend != "tablet") {
            val header = JSONObject().put("t", "ask").put("askId", askId).put("intent", intent).put("title", session.title)
                .put("session", session.remote).put("new", session.remote.isEmpty())
            val sizes = JSONArray()
            images.forEach { (name, bytes) -> sizes.put(JSONObject().put("name", name).put("size", bytes.size)) }
            header.put("images", sizes)
            val all = images.fold(ByteArray(0)) { acc, (_, b) -> acc + b }
            if (!BulkLink.sendBlob(header, all)) fail(askId, "Lost the connection to the PC. Try again.")
        } else {
            thread(name = "slate-ask", isDaemon = true) {
                try {
                    val history = store.entries(session.id).filter { it.askId != askId && it.state == "done" }
                    val (markdown, cost) = TabletAsk.ask(apiKey, intent, images, history)
                    store.updateEntry(askId) { it.copy(markdown = markdown, state = "done", status = "", costUsd = cost) }
                } catch (e: Exception) {
                    Log.w(TAG, "Ask failed", e)
                    store.updateEntry(askId) { it.copy(state = "error", status = e.message ?: "Failed") }
                }
                changed(session.id)
            }
        }
        return session.id
    }

    /**
     * The session a new question most likely continues: the most recently used one if it was
     * active in the last two hours (and, with [tabletOnly], answered on the tablet).
     */
    fun recentSession(tabletOnly: Boolean): String? {
        val s = store.sessions().firstOrNull() ?: return null
        if (System.currentTimeMillis() - s.updated > 2 * 60 * 60 * 1000) return null
        if (tabletOnly && s.backend != "tablet") return null
        return s.id
    }

    fun openInClaude(sessionId: String): Boolean {
        val s = store.session(sessionId) ?: return false
        if (s.remote.isEmpty()) return false
        return BulkLink.send(JSONObject().put("t", "ask.open").put("session", s.remote))
    }

    private fun fail(askId: String, message: String) {
        changed(store.updateEntry(askId) { it.copy(state = "error", status = message) })
    }

    private fun onPcMessage(o: JSONObject) {
        val askId = o.optString("askId")
        when (o.optString("t")) {
            "ask.status" -> {
                val remote = o.optString("session")
                val sid = store.updateEntry(askId) {
                    if (o.optString("state") == "error") it.copy(state = "error", status = o.optString("message"))
                    else it.copy(status = o.optString("message"))
                }
                if (sid != null && remote.isNotEmpty()) store.updateSession(sid) { it.copy(remote = remote) }
                changed(sid)
            }
            "ask.delta" -> {
                live.getOrPut(askId) { StringBuilder() }.append(o.optString("text"))
                changed(store.findSessionOf(askId))
            }
            "ask.done" -> {
                live.remove(askId)
                val sid = store.updateEntry(askId) {
                    it.copy(markdown = o.optString("markdown"), state = "done", status = "", costUsd = o.optDouble("cost", 0.0))
                }
                if (sid != null) store.updateSession(sid) { it.copy(remote = o.optString("session", it.remote), backend = o.optString("backend", it.backend)) }
                changed(sid)
            }
        }
    }

    private fun defaultTitle() = "Study " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
}
