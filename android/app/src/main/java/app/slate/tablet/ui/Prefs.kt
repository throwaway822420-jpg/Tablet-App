package app.slate.tablet.ui

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    companion object {
        const val DEFAULT_SHORTCUTS = "Undo=Ctrl+Z\nRedo=Ctrl+Y\nCopy=Ctrl+C\nPaste=Ctrl+V\nEsc=Esc\nDel=Delete\n⏎=Enter\nSave=Ctrl+S\nApps=Alt+Tab\nDesktop=Win+D"

        /** "Label=Combo" lines → pairs, skipping blank or malformed lines. */
        fun parseShortcuts(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0 || i == line.length - 1) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
    }

    private val p: SharedPreferences = context.getSharedPreferences("slate", Context.MODE_PRIVATE)

    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", true)
        set(v) = p.edit().putBoolean("keep_screen_on", v).apply()

    var darkCanvas: Boolean
        get() = p.getBoolean("dark_canvas", true)
        set(v) = p.edit().putBoolean("dark_canvas", v).apply()

    var showOutline: Boolean
        get() = p.getBoolean("show_outline", true)
        set(v) = p.edit().putBoolean("show_outline", v).apply()

    var showStatus: Boolean
        get() = p.getBoolean("show_status", true)
        set(v) = p.edit().putBoolean("show_status", v).apply()

    /** Share of the tablet used as the active area (1 = as large as fits). Smaller = less hand movement. */
    var areaScale: Float
        get() = p.getFloat("area_scale", 1f)
        set(v) = p.edit().putFloat("area_scale", v).apply()

    /** Anthropic API key for write mode (handwriting → text). Stored in app-private storage. */
    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    /** Add a space after each piece of converted text, so consecutive writes don't run together. */
    var addSpaceAfterText: Boolean
        get() = p.getBoolean("add_space", true)
        set(v) = p.edit().putBoolean("add_space", v).apply()

    /** How maths in handwriting is written out: Unicode, Equation or LaTeX. */
    var outputMode: app.slate.tablet.write.OutputMode
        get() = runCatching { app.slate.tablet.write.OutputMode.valueOf(p.getString("output_mode", "UNICODE")!!) }
            .getOrDefault(app.slate.tablet.write.OutputMode.UNICODE)
        set(v) = p.edit().putString("output_mode", v.name).apply()

    /** Shortcut buttons on the screen-mirror strip, one "Label=Combo" per line. */
    var shortcuts: String
        get() = p.getString("shortcuts", DEFAULT_SHORTCUTS) ?: DEFAULT_SHORTCUTS
        set(v) = p.edit().putString("shortcuts", v).apply()

    var showShortcuts: Boolean
        get() = p.getBoolean("show_shortcuts", true)
        set(v) = p.edit().putBoolean("show_shortcuts", v).apply()

    /** Fingers on the mirrored screen: navigate (clicks, scroll, zoom the view) or real Windows touch. */
    var windowsTouch: Boolean
        get() = p.getBoolean("windows_touch", false)
        set(v) = p.edit().putBoolean("windows_touch", v).apply()

    /** In Screen mode, bring up the handwriting pad when a tap puts the PC's focus in a text field. */
    var writeOnTextField: Boolean
        get() = p.getBoolean("write_on_text_field", true)
        set(v) = p.edit().putBoolean("write_on_text_field", v).apply()

    var followCursor: Boolean
        get() = p.getBoolean("follow_cursor", true)
        set(v) = p.edit().putBoolean("follow_cursor", v).apply()

    /** Monitor to mirror (Windows device name); empty = the one the pen maps to. */
    var mirrorMonitor: String
        get() = p.getString("mirror_monitor", "") ?: ""
        set(v) = p.edit().putString("mirror_monitor", v).apply()

    /** Stream bitrate in bits/s; 0 = automatic (30 Mb/s USB, 15 Mb/s Wi-Fi). */
    var mirrorBitrate: Int
        get() = p.getInt("mirror_bitrate", 0)
        set(v) = p.edit().putInt("mirror_bitrate", v).apply()

    /** Settings view for a [app.slate.tablet.write.WritePanel]. */
    fun panelSettings() = object : app.slate.tablet.write.WritePanel.Settings {
        override val apiKey get() = this@Prefs.apiKey
        override val dark get() = darkCanvas
        override var outputMode
            get() = this@Prefs.outputMode
            set(v) { this@Prefs.outputMode = v }
        override val addSpace get() = addSpaceAfterText
        override val fast get() = fastHandwriting
    }

    /** Read handwriting with Claude Haiku 4.5 instead of Sonnet 5.5. */
    var fastHandwriting: Boolean
        get() = p.getBoolean("fast_handwriting", false)
        set(v) = p.edit().putBoolean("fast_handwriting", v).apply()

    /** Last pairing code that worked for a PC name, so reconnecting doesn't ask again. */
    fun pairCode(pcName: String): Int? = p.getInt("code_$pcName", -1).takeIf { it in 0..9999 }

    fun savePairCode(pcName: String, code: Int) = p.edit().putInt("code_$pcName", code).apply()
}
