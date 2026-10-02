package app.slate.tablet.study

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Turns a session's history into the JSON that assets/study/viewer.html renders (History and the side chat). */
object Conversation {
    fun entriesJson(sessionId: String?): String {
        val entries = JSONArray()
        if (sessionId != null && StudyHub.store.session(sessionId) != null) {
            val dir = StudyHub.store.dir(sessionId)
            for (e in StudyHub.store.entries(sessionId)) {
                entries.put(
                    JSONObject()
                        .put("id", e.askId)
                        .put("label", StudyStore.INTENT_LABELS[e.intent] ?: "Question")
                        .put("time", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time)))
                        .put("image", if (e.image.isEmpty()) "" else "file://" + File(dir, e.image).absolutePath)
                        .put("text", e.text)
                        .put("markdown", if (e.state == "pending") StudyHub.liveText(e.askId) ?: "" else e.markdown)
                        .put("state", e.state)
                        .put("status", e.status)
                        .put("cost", e.costUsd),
                )
            }
        }
        return entries.toString()
    }

    /** JavaScript that renders a session in the viewer page. */
    fun renderScript(sessionId: String?) = "render(${JSONObject.quote(entriesJson(sessionId))})"
}
