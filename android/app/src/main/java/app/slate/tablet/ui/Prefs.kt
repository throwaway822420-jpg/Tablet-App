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

    /** Last pairing code that worked for a PC name, so reconnecting doesn't ask again. */
    fun pairCode(pcName: String): Int? = p.getInt("code_$pcName", -1).takeIf { it in 0..9999 }

    fun savePairCode(pcName: String, code: Int) = p.edit().putInt("code_$pcName", code).apply()
}
