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
    private const val KEY_SERIES_HUB = "series_hub"
    private const val KEY_MOVIES_HUB = "movies_hub"
    private const val KEY_LAST_LIVE_URL = "last_live_url"
    private const val KEY_LAST_LIVE_NAME = "last_live_name"
    private const val KEY_LAST_LIVE_GROUP = "last_live_group"
    private const val KEY_LAST_LIVE_NUMBER = "last_live_number"
    private const val KEY_LAST_LIVE_LOGO = "last_live_logo"
    private const val KEY_LAST_LIVE_STREAM_ID = "last_live_stream_id"

    private const val PREFIX_SERIES_VOD = "series_vod_"
    private const val PREFIX_MOVIE_VOD = "movie_vod_"
    // Legacy single-slot keys (migrated on read).
    private const val KEY_VOD_URL = "vod_url"
    private const val KEY_VOD_NAME = "vod_name"
    private const val KEY_VOD_GROUP = "vod_group"
    private const val KEY_VOD_LOGO = "vod_logo"
    private const val KEY_VOD_NUMBER = "vod_number"
    private const val KEY_VOD_SERIES_ID = "vod_series_id"
    private const val KEY_VOD_SERIES_NAME = "vod_series_name"
    private const val KEY_VOD_POSITION_MS = "vod_position_ms"

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
            when (value) {
                AppScreen.SERIES, AppScreen.STREAMING_SERIES -> lastSeriesHub = value
                AppScreen.MOVIES, AppScreen.STREAMING_MOVIE -> lastMoviesHub = value
                else -> Unit
            }
        }

    /**
     * Last place inside Series: catalog or streaming.
     * Green button restores this.
     */
    var lastSeriesHub: AppScreen
        get() = AppScreen.fromKey(requirePrefs().getString(KEY_SERIES_HUB, null)).let {
            if (it == AppScreen.STREAMING_SERIES) it else AppScreen.SERIES
        }
        set(value) {
            val safe =
                if (value == AppScreen.STREAMING_SERIES) AppScreen.STREAMING_SERIES
                else AppScreen.SERIES
            requirePrefs().edit().putString(KEY_SERIES_HUB, safe.key).apply()
        }

    /**
     * Last place inside Movies: catalog or streaming.
     * Yellow button restores this.
     */
    var lastMoviesHub: AppScreen
        get() = AppScreen.fromKey(requirePrefs().getString(KEY_MOVIES_HUB, null)).let {
            if (it == AppScreen.STREAMING_MOVIE) it else AppScreen.MOVIES
        }
        set(value) {
            val safe =
                if (value == AppScreen.STREAMING_MOVIE) AppScreen.STREAMING_MOVIE
                else AppScreen.MOVIES
            requirePrefs().edit().putString(KEY_MOVIES_HUB, safe.key).apply()
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

    fun saveLastVod(
        url: String,
        name: String,
        group: String,
        logo: String?,
        number: Int,
        seriesId: Int?,
        seriesName: String?,
        positionMs: Long = 0L
    ) {
        if (seriesId != null) {
            writeVod(PREFIX_SERIES_VOD, url, name, group, logo, number, seriesId, seriesName, positionMs)
            lastSeriesHub = AppScreen.STREAMING_SERIES
        } else {
            writeVod(PREFIX_MOVIE_VOD, url, name, group, logo, number, null, null, positionMs)
            lastMoviesHub = AppScreen.STREAMING_MOVIE
        }
    }

    fun lastSeriesVod(): VodPlayback? =
        readVod(PREFIX_SERIES_VOD)?.takeIf { it.isSeries }

    fun lastMovieVod(): VodPlayback? =
        readVod(PREFIX_MOVIE_VOD)?.takeIf { !it.isSeries }

    fun lastVodPlayback(): VodPlayback? = when (lastScreen) {
        AppScreen.STREAMING_SERIES -> lastSeriesVod()
        AppScreen.STREAMING_MOVIE -> lastMovieVod()
        else -> lastSeriesVod() ?: lastMovieVod()
    }

    private fun writeVod(
        prefix: String,
        url: String,
        name: String,
        group: String,
        logo: String?,
        number: Int,
        seriesId: Int?,
        seriesName: String?,
        positionMs: Long
    ) {
        requirePrefs().edit()
            .putString(prefix + "url", url)
            .putString(prefix + "name", name)
            .putString(prefix + "group", group)
            .putString(prefix + "logo", logo.orEmpty())
            .putInt(prefix + "number", number)
            .putInt(prefix + "series_id", seriesId ?: -1)
            .putString(prefix + "series_name", seriesName.orEmpty())
            .putLong(prefix + "position_ms", positionMs.coerceAtLeast(0L))
            .apply()
    }

    private fun readVod(prefix: String): VodPlayback? {
        val url = requirePrefs().getString(prefix + "url", null)?.takeIf { it.isNotBlank() }
            ?: return null
        val seriesId = requirePrefs().getInt(prefix + "series_id", -1).takeIf { it > 0 }
        return VodPlayback(
            url = url,
            name = requirePrefs().getString(prefix + "name", "").orEmpty(),
            group = requirePrefs().getString(prefix + "group", "").orEmpty(),
            logo = requirePrefs().getString(prefix + "logo", null)?.ifBlank { null },
            number = requirePrefs().getInt(prefix + "number", 0),
            seriesId = seriesId,
            seriesName = requirePrefs().getString(prefix + "series_name", null)?.ifBlank { null },
            positionMs = requirePrefs().getLong(prefix + "position_ms", 0L)
        )
    }

    private fun legacyVod(): VodPlayback? {
        val url = requirePrefs().getString(KEY_VOD_URL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val seriesId = requirePrefs().getInt(KEY_VOD_SERIES_ID, -1).takeIf { it > 0 }
        return VodPlayback(
            url = url,
            name = requirePrefs().getString(KEY_VOD_NAME, "").orEmpty(),
            group = requirePrefs().getString(KEY_VOD_GROUP, "").orEmpty(),
            logo = requirePrefs().getString(KEY_VOD_LOGO, null)?.ifBlank { null },
            number = requirePrefs().getInt(KEY_VOD_NUMBER, 0),
            seriesId = seriesId,
            seriesName = requirePrefs().getString(KEY_VOD_SERIES_NAME, null)?.ifBlank { null },
            positionMs = requirePrefs().getLong(KEY_VOD_POSITION_MS, 0L)
        )
    }
}

data class VodPlayback(
    val url: String,
    val name: String,
    val group: String,
    val logo: String?,
    val number: Int,
    val seriesId: Int?,
    val seriesName: String?,
    val positionMs: Long
) {
    val isSeries: Boolean get() = seriesId != null
}

enum class AppScreen(val key: String) {
    TV("tv"),
    SERIES("series"),
    MOVIES("movies"),
    CHANNELS("channels"),
    STREAMING_SERIES("streaming_series"),
    STREAMING_MOVIE("streaming_movie"),
    SETTINGS("settings");

    companion object {
        fun fromKey(value: String?): AppScreen =
            entries.find { it.key == value } ?: TV
    }
}
