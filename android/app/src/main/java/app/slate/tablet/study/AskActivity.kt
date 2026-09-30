package app.slate.tablet.study

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.slate.tablet.link.BulkLink
import app.slate.tablet.ui.Prefs
import app.slate.tablet.write.WritePanel
import java.io.File
import kotlin.concurrent.thread

/**
 * Draw on a frozen screenshot (or a blank page for a handwritten follow-up) and send it to Claude
 * with one tap on an intent: Ask, Explain, Step by step, Just a hint, Check my working, Quiz me.
 */
class AskActivity : Activity() {
    private lateinit var annotator: Annotator
    private var sessionId: String? = null
    private var followUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudyHub.init(this)
        sessionId = intent.getStringExtra(EXTRA_SESSION)
        followUp = intent.getBooleanExtra(EXTRA_FOLLOW_UP, false)

        val image = loadImage() ?: run {
            Toast.makeText(this, "Couldn't open that image.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        annotator = Annotator(this, image)
        annotator.color = if (followUp) Color.rgb(20, 20, 20) else Color.rgb(230, 40, 40)

        val root = FrameLayout(this)
        root.addView(annotator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Tools along the top.
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        val swatches = listOf(Color.rgb(230, 40, 40), Color.rgb(30, 110, 230), Color.rgb(20, 160, 80), Color.rgb(20, 20, 20))
        val colourButtons = ArrayList<Button>()
        fun select(b: Button?) {
            colourButtons.forEach { (it.background as GradientDrawable).setStroke(0, 0) }
            b?.let { (it.background as GradientDrawable).setStroke(dp(3), Color.WHITE) }
        }
        for (c in swatches) {
            val b = Button(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener {
                    annotator.color = c; annotator.highlighter = false; annotator.erasing = false; select(this)
                }
            }
            colourButtons.add(b)
            tools.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6); marginEnd = dp(6) })
        }
        select(colourButtons[swatches.indexOf(annotator.color).coerceAtLeast(0)])
        fun tool(label: String, action: () -> Unit) = WritePanel.makeButton(this, label, onClick = action).also {
            tools.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(6) })
        }
        tool("Highlighter") { annotator.highlighter = true; annotator.erasing = false; select(null) }
        tool("Eraser") { annotator.erasing = true; select(null) }
        if (!followUp && sessionId != null) {
            var topicButton: Button? = null
            topicButton = tool("Continue topic") {
                val continuing = topicButton!!.text == "New topic"
                sessionId = if (continuing) intent.getStringExtra(EXTRA_SESSION) else null
                topicButton!!.text = if (continuing) "Continue topic" else "New topic"
                updateWhere()
            }
        }
        tool("Undo") { annotator.undo() }
        tool("Clear") { annotator.clear() }
        tool("Cancel") { finish() }
        root.addView(tools, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(8) })

        // One tap sends with that intent.
        val intents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val choices = if (followUp) listOf("followup" to "Send") else StudyStore.INTENT_LABELS.entries.filter { it.key != "followup" }.map { it.key to it.value }
        choices.forEachIndexed { i, (key, label) ->
            intents.addView(
                WritePanel.makeButton(this, label, accent = i == 0) { send(key) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { topMargin = dp(6) },
            )
        }
        root.addView(intents, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))

        where.apply {
            setTextColor(Color.rgb(200, 200, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        updateWhere()
        root.addView(where, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })

        setContentView(root)
        immersive()
    }

    private val where by lazy { TextView(this) }

    private fun updateWhere() {
        val session = sessionId?.let { StudyHub.store.session(it) }
        where.text = buildString {
            append(if (session != null) "Continuing: ${session.title}" else "New topic")
            append(" · ")
            append(if (BulkLink.connected && session?.backend != "tablet") "answered via the PC" else "answered on the tablet (Claude Opus 5.5)")
            append(if (followUp) " · write your follow-up" else " · circle or highlight, write your question, then tap an intent")
        }
    }

    private fun loadImage(): Bitmap? {
        if (followUp) {
            val dm = resources.displayMetrics
            return Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val uri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null
        return try {
            when {
                path != null -> BitmapFactory.decodeFile(path)
                uri != null -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                else -> null
            }?.let { Annotator.limit(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun send(intentKey: String) {
        val prefs = Prefs(this)
        if (!BulkLink.connected && prefs.apiKey.isBlank()) {
            Toast.makeText(this, "Connect the PC, or add your Anthropic API key on Slate's main screen.", Toast.LENGTH_LONG).show()
            return
        }
        if (followUp && annotator.marks.isEmpty()) {
            Toast.makeText(this, "Write your follow-up first.", Toast.LENGTH_SHORT).show()
            return
        }
        val annotated = annotator.composite()
        val crop = if (followUp) null else annotator.circledCrop(annotated)
        val session = sessionId
        thread(name = "slate-ask-prep", isDaemon = true) {
            val images = buildList {
                add((if (followUp) "followup.jpg" else "screen.jpg") to Annotator.jpeg(annotated))
                crop?.let { add("crop.jpg" to Annotator.jpeg(Annotator.limit(it, 1600), 92)) }
            }
            val sid = StudyHub.ask(session, intentKey, images, "", prefs.apiKey)
            runOnUiThread {
                startActivity(Intent(this, StudyActivity::class.java).putExtra(StudyActivity.EXTRA_SESSION, sid))
                finish()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_SESSION = "session"
        const val EXTRA_FOLLOW_UP = "follow_up"

        /** Saves a screenshot to the cache and opens it for annotation. */
        fun start(activity: Activity, screenshot: Bitmap, sessionId: String? = null) {
            val f = File(activity.cacheDir, "ask-screen.jpg")
            f.outputStream().use { screenshot.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            activity.startActivity(
                Intent(activity, AskActivity::class.java).putExtra(EXTRA_IMAGE_PATH, f.absolutePath).putExtra(EXTRA_SESSION, sessionId),
            )
        }
    }
}
