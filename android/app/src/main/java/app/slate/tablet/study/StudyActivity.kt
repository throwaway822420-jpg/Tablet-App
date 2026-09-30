package app.slate.tablet.study

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.slate.tablet.write.WritePanel
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * History of questions about the screen: sessions on the left, the selected conversation on the
 * right (Markdown, maths and diagrams rendered), with follow-ups, Open in Claude, export and delete.
 */
class StudyActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var web: WebView
    private lateinit var header: TextView
    private lateinit var actions: LinearLayout
    private var selected: String? = null
    private var pageReady = false

    private val onChange: (String) -> Unit = { sid ->
        refreshList()
        if (sid == selected) render()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudyHub.init(this)
        selected = intent.getStringExtra(EXTRA_SESSION) ?: StudyHub.store.sessions().firstOrNull()?.id

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val left = ScrollView(this).apply { addView(list); setBackgroundColor(Color.rgb(32, 34, 38)) }

        header = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true // the history's images live in app storage
            setBackgroundColor(Color.rgb(24, 26, 30))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    render()
                }
            }
            loadUrl("file:///android_asset/study/viewer.html")
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 26, 30))
            addView(header)
            addView(actions)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        refreshList()
    }

    override fun onStart() {
        super.onStart()
        StudyHub.addListener(onChange)
        refreshList()
        render()
    }

    override fun onStop() {
        StudyHub.removeListener(onChange)
        super.onStop()
    }

    private fun refreshList() {
        list.removeAllViews()
        val sessions = StudyHub.store.sessions()
        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                setTextColor(Color.rgb(180, 180, 180))
                text = "No questions yet. In Screen mode tap Ask, or use Ask about the tablet screen on Slate's main screen."
            })
        }
        for (s in sessions) {
            val count = StudyHub.store.entries(s.id).size
            list.addView(TextView(this).apply {
                text = "${s.title}\n${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.updated))} · $count question${if (count == 1) "" else "s"} · ${backendLabel(s.backend)}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (s.id == selected) Color.rgb(40, 130, 90) else Color.TRANSPARENT)
                setOnClickListener {
                    selected = s.id
                    refreshList()
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
    }

    private fun backendLabel(b: String) = when (b) {
        "code" -> "Claude Code"
        "desktop" -> "Claude Desktop"
        "tablet" -> "tablet"
        else -> "PC"
    }

    private fun render() {
        val sid = selected
        val session = sid?.let { StudyHub.store.session(it) }
        header.text = session?.title ?: ""
        buildActions(session)
        if (!pageReady) return
        val entries = JSONArray()
        if (session != null) {
            val dir = StudyHub.store.dir(session.id)
            for (e in StudyHub.store.entries(session.id)) {
                entries.put(JSONObject()
                    .put("id", e.askId)
                    .put("label", StudyStore.INTENT_LABELS[e.intent] ?: "Question")
                    .put("time", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time)))
                    .put("image", "file://" + java.io.File(dir, e.image).absolutePath)
                    .put("markdown", if (e.state == "pending") StudyHub.liveText(e.askId) ?: "" else e.markdown)
                    .put("state", e.state)
                    .put("status", e.status)
                    .put("cost", e.costUsd))
            }
        }
        web.evaluateJavascript("render(${JSONObject.quote(entries.toString())})", null)
    }

    private fun buildActions(session: StudyStore.Session?) {
        actions.removeAllViews()
        if (session == null) return
        fun action(label: String, accent: Boolean = false, onClick: () -> Unit) = actions.addView(
            WritePanel.makeButton(this, label, accent, onClick),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) },
        )
        action("Follow up", accent = true) {
            startActivity(Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_SESSION, session.id).putExtra(AskActivity.EXTRA_FOLLOW_UP, true))
        }
        if (session.backend != "tablet") action("Open in Claude") {
            val ok = StudyHub.openInClaude(session.id)
            Toast.makeText(
                this,
                if (ok) "Opening on the PC. It'll also appear at claude.ai/code and in the Claude app (Code)." else "Connect the PC first (and wait for the first answer).",
                Toast.LENGTH_LONG,
            ).show()
        }
        action("Export PDF") { exportPdf(session) }
        action("Share Markdown") { FileShare.shareMarkdown(this, StudyHub.store, session) }
        action("Rename") { rename(session) }
        action("Delete") {
            AlertDialog.Builder(this).setMessage("Delete \"${session.title}\" and its answers from the tablet?")
                .setPositiveButton("Delete") { _, _ ->
                    StudyHub.store.deleteSession(session.id)
                    selected = StudyHub.store.sessions().firstOrNull()?.id
                    refreshList(); render()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun rename(session: StudyStore.Session) {
        val field = EditText(this).apply { setText(session.title); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Rename").setView(field)
            .setPositiveButton("Save") { _, _ ->
                StudyHub.store.updateSession(session.id) { it.copy(title = field.text.toString().ifBlank { it.title }) }
                refreshList(); render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Android's print dialog, with "Save as PDF", renders exactly what's on screen (maths and diagrams included). */
    private fun exportPdf(session: StudyStore.Session) {
        val pm = getSystemService(PrintManager::class.java) ?: return
        pm.print("Slate - ${session.title}", web.createPrintDocumentAdapter(session.title), PrintAttributes.Builder().build())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SESSION = "session"
    }
}
