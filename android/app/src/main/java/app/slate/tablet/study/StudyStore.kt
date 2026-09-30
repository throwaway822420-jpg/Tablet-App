package app.slate.tablet.study

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The tablet's history of questions about the screen and Claude's answers, grouped into sessions.
 * Plain JSON files under [root], so they survive app updates and are easy to export.
 */
class StudyStore(private val root: File) {
    data class Session(
        val id: String,
        val title: String,
        /** "code" (Claude Code on the PC), "desktop" (Claude Desktop app) or "tablet" (asked from the tablet). */
        val backend: String,
        /** The session's ID on the PC side (Claude Code session UUID / desktop marker); "" until known. */
        val remote: String,
        val created: Long,
        val updated: Long,
    )

    data class Entry(
        val askId: String,
        val time: Long,
        val intent: String,
        /** Image file name inside the session folder. */
        val image: String,
        val markdown: String,
        /** "pending", "done" or "error". */
        val state: String,
        val status: String = "",
        val costUsd: Double = 0.0,
    )

    private val lock = Any()

    init {
        root.mkdirs()
    }

    fun sessions(): List<Session> = synchronized(lock) { readSessions() }.sortedByDescending { it.updated }

    fun session(id: String): Session? = sessions().firstOrNull { it.id == id }

    fun newSession(title: String, backend: String): Session = synchronized(lock) {
        val now = System.currentTimeMillis()
        val s = Session(UUID.randomUUID().toString(), title, backend, "", now, now)
        writeSessions(readSessions() + s)
        File(root, s.id).mkdirs()
        s
    }

    fun updateSession(id: String, change: (Session) -> Session) = synchronized(lock) {
        writeSessions(readSessions().map { if (it.id == id) change(it).copy(updated = System.currentTimeMillis()) else it })
    }

    fun deleteSession(id: String) = synchronized(lock) {
        writeSessions(readSessions().filter { it.id != id })
        File(root, id).deleteRecursively()
    }

    fun dir(sessionId: String) = File(root, sessionId).apply { mkdirs() }

    fun entries(sessionId: String): List<Entry> = synchronized(lock) { readEntries(sessionId) }

    fun addEntry(sessionId: String, e: Entry) = synchronized(lock) {
        writeEntries(sessionId, readEntries(sessionId) + e)
        updateSession(sessionId) { it }
    }

    /** Updates the entry with [askId] in whichever session holds it; returns that session's id. */
    fun updateEntry(askId: String, change: (Entry) -> Entry): String? = synchronized(lock) {
        for (s in readSessions()) {
            val list = readEntries(s.id)
            if (list.any { it.askId == askId }) {
                writeEntries(s.id, list.map { if (it.askId == askId) change(it) else it })
                return s.id
            }
        }
        null
    }

    fun findSessionOf(askId: String): String? = synchronized(lock) {
        readSessions().firstOrNull { s -> readEntries(s.id).any { it.askId == askId } }?.id
    }

    /** Markdown for a whole session, with images referenced by file name next to it. */
    fun exportMarkdown(sessionId: String): String {
        val s = session(sessionId) ?: return ""
        val sb = StringBuilder("# ${s.title}\n\n")
        for (e in entries(sessionId)) {
            sb.append("## ").append(INTENT_LABELS[e.intent] ?: "Question").append(" — ")
                .append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.time))).append("\n\n")
            sb.append("![](${e.image})\n\n").append(e.markdown.ifBlank { "_(no answer)_" }).append("\n\n")
        }
        return sb.toString()
    }

    private val sessionsFile get() = File(root, "sessions.json")

    private fun readSessions(): List<Session> = try {
        val a = JSONArray(sessionsFile.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Session(o.getString("id"), o.optString("title"), o.optString("backend"), o.optString("remote"), o.optLong("created"), o.optLong("updated"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeSessions(list: List<Session>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("backend", it.backend).put("remote", it.remote)
                .put("created", it.created).put("updated", it.updated))
        }
        atomicWrite(sessionsFile, a.toString())
    }

    private fun readEntries(sessionId: String): List<Entry> = try {
        val a = JSONArray(File(root, "$sessionId/entries.json").readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(o.getString("askId"), o.optLong("time"), o.optString("intent"), o.optString("image"), o.optString("markdown"),
                o.optString("state"), o.optString("status"), o.optDouble("cost", 0.0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeEntries(sessionId: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("askId", it.askId).put("time", it.time).put("intent", it.intent).put("image", it.image)
                .put("markdown", it.markdown).put("state", it.state).put("status", it.status).put("cost", it.costUsd))
        }
        atomicWrite(File(dir(sessionId), "entries.json"), a.toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        val INTENT_LABELS = linkedMapOf(
            "ask" to "Ask", "explain" to "Explain", "steps" to "Step by step", "hint" to "Just a hint",
            "check" to "Check my working", "quiz" to "Quiz me", "followup" to "Follow-up",
        )
    }
}
