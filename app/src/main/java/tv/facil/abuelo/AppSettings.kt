package tv.facil.abuelo

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings. Defaults are grandpa-friendly.
 */
object AppSettings {
    private const val PREFS = "tv_facil_settings"
    private const val KEY_ZAP_WRAP_AROUND = "zap_wrap_around"
    private const val KEY_LAST_SOURCE_ID = "last_source_id"
    private const val KEY_LAST_SCREEN = "last_screen"
    private const val KEY_SETTINGS_RETURN = "settings_return_screen"
    private const val KEY_LAST_LIVE_URL = "last_live_url"
    private const val KEY_LAST_LIVE_NAME = "last_live_name"
    private const val KEY_LAST_LIVE_GROUP = "last_live_group"
    private const val KEY_LAST_LIVE_NUMBER = "last_live_number"
    private const val KEY_LAST_LIVE_LOGO = "last_live_logo"
    private const val KEY_LAST_LIVE_STREAM_ID = "last_live_stream_id"

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

    var lastSourceId: String
        get() = requirePrefs().getString(KEY_LAST_SOURCE_ID, "").orEmpty()
        set(value) {
            requirePrefs().edit().putString(KEY_LAST_SOURCE_ID, value).apply()
        }

    var lastScreen: AppScreen
        get() = AppScreen.fromKey(requirePrefs().getString(KEY_LAST_SCREEN, null))
        set(value) {
            requirePrefs().edit().putString(KEY_LAST_SCREEN, value.key).apply()
        }

    /** Where Back from Settings should return. */
    var settingsReturnScreen: AppScreen
        get() = AppScreen.fromKey(requirePrefs().getString(KEY_SETTINGS_RETURN, null))
            .let { if (it == AppScreen.SETTINGS) AppScreen.TV else it }
        set(value) {
            val safe = if (value == AppScreen.SETTINGS) AppScreen.TV else value
            requirePrefs().edit().putString(KEY_SETTINGS_RETURN, safe.key).apply()
        }

    fun saveLastLive(item: CatalogItem) {
        requirePrefs().edit()
            .putString(KEY_LAST_LIVE_URL, item.url)
            .putString(KEY_LAST_LIVE_NAME, item.name)
            .putString(KEY_LAST_LIVE_GROUP, item.group)
            .putInt(KEY_LAST_LIVE_NUMBER, item.number)
            .putString(KEY_LAST_LIVE_LOGO, item.logo.orEmpty())
            .putInt(KEY_LAST_LIVE_STREAM_ID, item.streamId ?: -1)
            .apply()
    }

    fun lastLiveItem(): CatalogItem? {
        val url = requirePrefs().getString(KEY_LAST_LIVE_URL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return CatalogItem(
            number = requirePrefs().getInt(KEY_LAST_LIVE_NUMBER, 0),
            name = requirePrefs().getString(KEY_LAST_LIVE_NAME, "").orEmpty(),
            group = requirePrefs().getString(KEY_LAST_LIVE_GROUP, "").orEmpty(),
            logo = requirePrefs().getString(KEY_LAST_LIVE_LOGO, null)?.ifBlank { null },
            url = url,
            streamId = requirePrefs().getInt(KEY_LAST_LIVE_STREAM_ID, -1).takeIf { it > 0 }
        )
    }
}

enum class AppScreen(val key: String) {
    TV("tv"),
    SERIES("series"),
    MOVIES("movies"),
    CHANNELS("channels"),
    SETTINGS("settings");

    companion object {
        fun fromKey(value: String?): AppScreen =
            entries.find { it.key == value } ?: TV
    }
}
