package tv.facil.abuelo

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings (no UI yet). Defaults are grandpa-friendly.
 * Backed by SharedPreferences; changeable later from a settings screen or adb.
 */
object AppSettings {
    private const val PREFS = "tv_facil_settings"
    private const val KEY_ZAP_WRAP_AROUND = "zap_wrap_around"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("AppSettings.init() not called")

    /** When false (default), CH+/- stops at the first/last channel. */
    var zapWrapAround: Boolean
        get() = requirePrefs().getBoolean(KEY_ZAP_WRAP_AROUND, false)
        set(value) {
            requirePrefs().edit().putBoolean(KEY_ZAP_WRAP_AROUND, value).apply()
        }
}
