package app.slate.tablet.ui

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
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

    /** Last pairing code that worked for a PC name, so reconnecting doesn't ask again. */
    fun pairCode(pcName: String): Int? = p.getInt("code_$pcName", -1).takeIf { it in 0..9999 }

    fun savePairCode(pcName: String, code: Int) = p.edit().putInt("code_$pcName", code).apply()
}
