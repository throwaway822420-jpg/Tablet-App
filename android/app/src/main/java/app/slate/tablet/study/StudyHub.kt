package app.slate.tablet.study

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.slate.tablet.ai.TermuxClaude
import app.slate.tablet.link.BulkLink
import app.slate.tablet.ui.Prefs
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

    /** Why a question can't be sent right now, or null if it can (PC, Claude Code in Termux, or the API key). */
    fun cannotAsk(context: Context, sessionId: String?): String? {
        val backend = sessionId?.let { store.session(it)?.backend }
        val termux = TermuxClaude.available(context)
        val api = Prefs(context).let { it.askApiFallback && it.apiKey.isNotBlank() }
        return when (backend) {
            "termux" -> if (termux) null else "Start Claude Code in Termux (slate-claude), or start a + New chat."
            "tablet" -> if (api) null else "This chat used the API key, which is off for Ask. Start a + New chat."
            null -> if (BulkLink.connected || termux || api) null
                else "Connect the PC or start Claude Code in Termux (slate-claude). Or allow the API key for Ask on Slate's main screen."
            else -> if (BulkLink.connected) null else "This conversation is with Claude Code on the PC: connect the PC, or start a + New chat."
        }
    }

    /** Text streamed so far for a question still being answered. */
    fun liveText(askId: String): String? = live[askId]?.toString()

    /**
     * Asks Claude. [images] are (name, JPEG bytes); the first is kept as the history thumbnail. A
     * typed chat message has intent "chat", [text] and usually no images. Returns the local session id.
     */
    fun ask(sessionId: String?, intent: String, images: List<Pair<String, ByteArray>>, title: String, apiKey: String, text: String = ""): String {
        val pc = BulkLink.connected
        val session = sessionId?.let { store.session(it) }
            ?: store.newSession(title.ifBlank { if (text.isNotBlank()) text.take(40) else defaultTitle() }, newBackend())
        val askId = UUID.randomUUID().toString()
        val imageName = if (images.isEmpty()) "" else "$askId.jpg"
        images.firstOrNull()?.let { File(store.dir(session.id), imageName).writeBytes(it.second) }
        images.drop(1).forEachIndexed { i, (_, bytes) -> File(store.dir(session.id), "$askId-extra$i.jpg").writeBytes(bytes) }
        store.addEntry(session.id, StudyStore.Entry(askId, System.currentTimeMillis(), intent, imageName, "", "pending", "Sending…", text = text))
        changed(session.id)

        if (session.backend == "termux") {
            askTermux(session, askId, intent, images, text)
        } else if (session.backend != "tablet" && !pc) {
            fail(askId, "This conversation is with Claude Code on the PC. Connect the PC, or start a + New chat.")
        } else if (session.backend == "tablet" && !Prefs(appContext!!).askApiFallback) {
            fail(askId, "Start Claude Code in Termux (slate-claude) or connect the PC. (Or allow the API key for Ask on Slate's main screen.)")
        } else if (pc && session.backend != "tablet") {
            val header = JSONObject().put("t", "ask").put("askId", askId).put("intent", intent).put("title", session.title)
                .put("session", session.remote).put("new", session.remote.isEmpty()).put("text", text)
            val sizes = JSONArray()
            images.forEach { (name, bytes) -> sizes.put(JSONObject().put("name", name).put("size", bytes.size)) }
            header.put("images", sizes)
            val all = images.fold(ByteArray(0)) { acc, (_, b) -> acc + b }
            val sent = if (images.isEmpty()) BulkLink.send(header) else BulkLink.sendBlob(header, all)
            if (!sent) fail(askId, "Lost the connection to the PC. Try again.")
        } else {
            thread(name = "slate-ask", isDaemon = true) {
                try {
                    val history = store.entries(session.id).filter { it.askId != askId && it.state == "done" }
                    val (markdown, cost) = TabletAsk.ask(apiKey, intent, images, history, text)
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
     * Where a new conversation goes: the PC if connected, else Claude Code in Termux if running
     * (both use the Claude subscription), else the API key. Call off the main thread.
     */
    private fun newBackend(): String = when {
        BulkLink.connected -> "pc"
        TermuxClaude.available(appContext!!) -> "termux"
        else -> "tablet"
    }

    /** Claude Code in Termux on this tablet; it streams the same messages the PC sends. */
    private fun askTermux(session: StudyStore.Session, askId: String, intent: String, images: List<Pair<String, ByteArray>>, text: String) {
        val ctx = appContext ?: return
        val words = listOfNotNull(if (intent == "chat") null else TabletAsk.INTENTS[intent] ?: TabletAsk.INTENTS.getValue("ask"), text.takeIf { it.isNotBlank() })
        val imgs = JSONArray()
        images.forEach { (name, bytes) -> imgs.put(JSONObject().put("name", name).put("type", "image/jpeg").put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))) }
        val body = JSONObject().put("askId", askId).put("session", session.remote).put("new", session.remote.isEmpty())
            .put("title", session.title).put("system", TabletAsk.SYSTEM).put("text", words.joinToString("\n\n")).put("images", imgs)
        thread(name = "slate-ask-termux", isDaemon = true) {
            val error = TermuxClaude.ask(ctx, body) { o -> main.post { onPcMessage(o) } }
            if (error != null) main.post { if (store.entries(session.id).any { it.askId == askId && it.state == "pending" }) fail(askId, error) }
        }
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
