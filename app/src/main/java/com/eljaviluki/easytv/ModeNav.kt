package com.eljaviluki.easytv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Color-button navigation and resume helpers.
 *
 * Rojo = TV · Verde = Series · Amarillo = Películas ·
 * Azul ×6 (rápido) = Configuración
 */
object ModeNav {
    const val EXTRA_FROM_TV = "from_tv"

    /** Consecutive blue presses needed to open settings (accidental-press guard). */
    private const val BLUE_SETTINGS_PRESSES = 6
    private const val BLUE_SETTINGS_WINDOW_MS = 900L

    private var bluePressCount: Int = 0
    private var lastBluePressAt: Long = 0L

    /** Finish current screen unless it's Main (kept under) or Player (reused via singleTop). */
    private fun finishAfterNav(activity: AppCompatActivity) {
        if (activity is MainActivity || activity is PlayerActivity) return
        activity.finish()
    }

    private fun playerIntentFlags(): Int =
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    private fun resetBluePresses() {
        bluePressCount = 0
        lastBluePressAt = 0L
    }

    /** True when the 6th rapid blue press should open settings. */
    private fun registerBluePress(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBluePressAt > BLUE_SETTINGS_WINDOW_MS) {
            bluePressCount = 0
        }
        lastBluePressAt = now
        bluePressCount += 1
        if (bluePressCount >= BLUE_SETTINGS_PRESSES) {
            resetBluePresses()
            return true
        }
        return false
    }

    fun handleColorKey(activity: AppCompatActivity, keyCode: Int, sourceId: String): Boolean {
        val sid = sourceId.ifBlank { AppSettings.preferredSourceId() }
        // TV (red) works without a VOD playlist; series/movies need one.
        if (sid.isBlank() && keyCode != KeyEvent.KEYCODE_PROG_RED &&
            keyCode != KeyEvent.KEYCODE_PROG_BLUE
        ) {
            return false
        }
        if (activity is PlayerActivity) {
            activity.persistPlaybackForNav()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                resetBluePresses()
                if (activity is PlayerActivity && activity.isLiveZap) return true
                openTv(activity, sid)
                true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                resetBluePresses()
                if (sid.isBlank()) return true
                openSeries(activity, sid)
                true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                resetBluePresses()
                if (sid.isBlank()) return true
                openMovies(activity, sid)
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                if (activity is SettingsActivity) {
                    resetBluePresses()
                    return true
                }
                if (registerBluePress()) {
                    openSettings(activity, sid, currentScreenFor(activity))
                }
                true
            }
            else -> false
        }
    }

    fun currentScreenFor(activity: Activity): AppScreen = when (activity) {
        is SettingsActivity -> AppSettings.settingsReturnScreen
        is CatalogActivity -> when (activity.currentKind) {
            ContentKind.LIVE -> AppScreen.CHANNELS
            ContentKind.SERIES -> AppScreen.SERIES
            ContentKind.MOVIES -> AppScreen.MOVIES
        }
        is PlayerActivity -> when {
            activity.isLiveZap -> AppScreen.TV
            activity.isSeriesVod -> AppScreen.STREAMING_SERIES
            activity.isMovieVod -> AppScreen.STREAMING_MOVIE
            else -> AppSettings.lastScreen
        }
        is EpisodesActivity -> AppScreen.SERIES
        else -> AppSettings.lastScreen
    }

    fun openSeries(activity: AppCompatActivity, sourceId: String) {
        // Toggle: streaming ↔ catalog (resume at saved position).
        if (activity is PlayerActivity && activity.isSeriesVod) {
            openCatalog(activity, sourceId, ContentKind.SERIES)
            return
        }
        if (activity is CatalogActivity && activity.currentKind == ContentKind.SERIES) {
            val vod = AppSettings.lastSeriesVod()
            if (vod != null && vod.isSeries) {
                openVod(activity, sourceId, vod, startPaused = true)
            }
            return
        }
        // From elsewhere: prefer last hub (streaming if left mid-episode, else catalog).
        if (AppSettings.lastSeriesHub == AppScreen.STREAMING_SERIES) {
            val vod = AppSettings.lastSeriesVod()
            if (vod != null && vod.isSeries) {
                openVod(activity, sourceId, vod, startPaused = true)
                return
            }
            AppSettings.lastSeriesHub = AppScreen.SERIES
        }
        openCatalog(activity, sourceId, ContentKind.SERIES)
    }

    fun openMovies(activity: AppCompatActivity, sourceId: String) {
        // Toggle: streaming ↔ catalog (resume at saved position).
        if (activity is PlayerActivity && activity.isMovieVod) {
            openCatalog(activity, sourceId, ContentKind.MOVIES)
            return
        }
        if (activity is CatalogActivity && activity.currentKind == ContentKind.MOVIES) {
            val vod = AppSettings.lastMovieVod()
            if (vod != null && !vod.isSeries) {
                openVod(activity, sourceId, vod, startPaused = true)
            }
            return
        }
        if (AppSettings.lastMoviesHub == AppScreen.STREAMING_MOVIE) {
            val vod = AppSettings.lastMovieVod()
            if (vod != null && !vod.isSeries) {
                openVod(activity, sourceId, vod, startPaused = true)
                return
            }
            AppSettings.lastMoviesHub = AppScreen.MOVIES
        }
        openCatalog(activity, sourceId, ContentKind.MOVIES)
    }

    fun openCatalog(
        activity: AppCompatActivity,
        sourceId: String,
        kind: ContentKind,
        fromTv: Boolean = false
    ) {
        val sid = sourceId.ifBlank { AppSettings.preferredSourceId() }
        if (kind != ContentKind.LIVE && sid.isBlank()) return
        if (activity is CatalogActivity && activity.currentKind == kind) {
            if (sid.isNotBlank()) AppSettings.lastSourceId = sid
            AppSettings.lastScreen = when (kind) {
                ContentKind.LIVE -> AppScreen.CHANNELS
                ContentKind.SERIES -> AppScreen.SERIES
                ContentKind.MOVIES -> AppScreen.MOVIES
            }
            return
        }
        if (sid.isNotBlank()) AppSettings.lastSourceId = sid
        AppSettings.lastScreen = when (kind) {
            ContentKind.LIVE -> AppScreen.CHANNELS
            ContentKind.SERIES -> {
                AppSettings.lastSeriesHub = AppScreen.SERIES
                AppScreen.SERIES
            }
            ContentKind.MOVIES -> {
                AppSettings.lastMoviesHub = AppScreen.MOVIES
                AppScreen.MOVIES
            }
        }
        activity.startActivity(
            Intent(activity, CatalogActivity::class.java)
                .putExtra(CatalogActivity.EXTRA_SOURCE_ID, sid)
                .putExtra(CatalogActivity.EXTRA_KIND, kind.name)
                .putExtra(EXTRA_FROM_TV, fromTv || kind == ContentKind.LIVE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        // Reuse Catalog via onNewIntent; don't finish it. Finish player/other screens.
        if (activity !is MainActivity && activity !is CatalogActivity) {
            activity.finish()
        }
    }

    fun openTv(activity: AppCompatActivity, sourceId: String) {
        if (activity is PlayerActivity && activity.isLiveZap) return
        val sid = sourceId.ifBlank { AppSettings.preferredSourceId() }
        if (sid.isNotBlank()) AppSettings.lastSourceId = sid
        AppSettings.lastScreen = AppScreen.TV
        activity.lifecycleScope.launch {
            val channel = resolveLiveChannel(activity, sid) ?: run {
                openCatalog(activity, sid, ContentKind.LIVE, fromTv = true)
                return@launch
            }
            EpisodeQueue.clear()
            startLivePlayer(activity, sid, channel)
        }
    }

    fun openChannelCatalog(activity: AppCompatActivity, sourceId: String) {
        openCatalog(activity, sourceId, ContentKind.LIVE, fromTv = true)
    }

    fun openVod(
        activity: AppCompatActivity,
        sourceId: String,
        playback: VodPlayback,
        startPaused: Boolean = false
    ) {
        AppSettings.lastSourceId = sourceId
        AppSettings.lastScreen =
            if (playback.isSeries) AppScreen.STREAMING_SERIES else AppScreen.STREAMING_MOVIE
        AppSettings.saveLastVod(
            url = playback.url,
            name = playback.name,
            group = playback.group,
            logo = playback.logo,
            number = playback.number,
            seriesId = playback.seriesId,
            seriesName = playback.seriesName,
            positionMs = playback.positionMs
        )
        activity.lifecycleScope.launch {
            EpisodeQueue.clear()
            activity.startActivity(
                Intent(activity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, playback.url)
                    .putExtra(PlayerActivity.EXTRA_NAME, playback.name)
                    .putExtra(PlayerActivity.EXTRA_GROUP, playback.group)
                    .putExtra(PlayerActivity.EXTRA_NUMBER, playback.number)
                    .putExtra(PlayerActivity.EXTRA_LOGO, playback.logo)
                    .putExtra(PlayerActivity.EXTRA_SOURCE_ID, sourceId)
                    .putExtra(PlayerActivity.EXTRA_SEEK_ENABLED, true)
                    .putExtra(PlayerActivity.EXTRA_ZAP_ENABLED, false)
                    .putExtra(PlayerActivity.EXTRA_START_POSITION_MS, playback.positionMs)
                    .putExtra(PlayerActivity.EXTRA_START_PAUSED, startPaused)
                    .putExtra(PlayerActivity.EXTRA_SERIES_ID, playback.seriesId ?: -1)
                    .putExtra(PlayerActivity.EXTRA_SERIES_NAME, playback.seriesName)
                    .addFlags(playerIntentFlags())
            )
            finishAfterNav(activity)
            if (playback.seriesId != null) {
                restoreEpisodeQueue(activity, sourceId, playback)
            }
        }
    }

    fun openSettings(activity: AppCompatActivity, sourceId: String, returnTo: AppScreen) {
        AppSettings.lastSourceId = sourceId
        AppSettings.settingsReturnScreen = returnTo
        AppSettings.lastScreen = AppScreen.SETTINGS
        activity.startActivity(
            Intent(activity, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SOURCE_ID, sourceId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finishAfterNav(activity)
    }

    fun leaveSettings(activity: AppCompatActivity, sourceId: String) {
        when (val dest = AppSettings.settingsReturnScreen) {
            AppScreen.SERIES -> openCatalog(activity, sourceId, ContentKind.SERIES)
            AppScreen.MOVIES -> openCatalog(activity, sourceId, ContentKind.MOVIES)
            AppScreen.CHANNELS -> openCatalog(activity, sourceId, ContentKind.LIVE, fromTv = true)
            AppScreen.STREAMING_SERIES, AppScreen.STREAMING_MOVIE -> {
                val vod = if (dest == AppScreen.STREAMING_SERIES) {
                    AppSettings.lastSeriesVod()
                } else {
                    AppSettings.lastMovieVod()
                }
                if (vod != null) openVod(activity, sourceId, vod, startPaused = true)
                else openCatalog(
                    activity,
                    sourceId,
                    if (dest == AppScreen.STREAMING_SERIES) ContentKind.SERIES else ContentKind.MOVIES
                )
            }
            AppScreen.SETTINGS -> openTv(activity, sourceId)
            AppScreen.TV -> openTv(activity, sourceId)
        }
    }

    /** Resume last screen from cold start. Returns true if navigation started. */
    fun tryResume(activity: AppCompatActivity): Boolean {
        val sourceId = AppSettings.preferredSourceId()
        when (AppSettings.lastScreen) {
            AppScreen.TV -> {
                openTv(activity, sourceId)
                return true
            }
            AppScreen.CHANNELS -> {
                openCatalog(activity, sourceId, ContentKind.LIVE, fromTv = true)
                return true
            }
            AppScreen.SETTINGS -> {
                openSettings(activity, sourceId, AppSettings.settingsReturnScreen)
                return true
            }
            else -> Unit
        }
        if (sourceId.isBlank() || PlaylistStore.byId(sourceId) == null) return false
        when (AppSettings.lastScreen) {
            AppScreen.SERIES -> openCatalog(activity, sourceId, ContentKind.SERIES)
            AppScreen.MOVIES -> openCatalog(activity, sourceId, ContentKind.MOVIES)
            AppScreen.STREAMING_SERIES, AppScreen.STREAMING_MOVIE -> {
                val vod = if (AppSettings.lastScreen == AppScreen.STREAMING_SERIES) {
                    AppSettings.lastSeriesVod()
                } else {
                    AppSettings.lastMovieVod()
                }
                if (vod != null) openVod(activity, sourceId, vod, startPaused = true)
                else openCatalog(
                    activity,
                    sourceId,
                    if (AppSettings.lastScreen == AppScreen.STREAMING_SERIES) {
                        ContentKind.SERIES
                    } else {
                        ContentKind.MOVIES
                    }
                )
            }
            else -> return false
        }
        return true
    }

    private fun startLivePlayer(
        activity: AppCompatActivity,
        sourceId: String,
        channel: CatalogItem
    ) {
        val play = ChannelsCleanStore.preferred(channel)
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, play.url)
                .putExtra(PlayerActivity.EXTRA_NAME, play.name)
                .putExtra(PlayerActivity.EXTRA_GROUP, play.group)
                .putExtra(PlayerActivity.EXTRA_NUMBER, play.number)
                .putExtra(PlayerActivity.EXTRA_LOGO, play.logo)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, play.streamId ?: -1)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, play.channelId)
                .putExtra(PlayerActivity.EXTRA_SOURCE_ID, sourceId)
                .putExtra(PlayerActivity.EXTRA_ZAP_ENABLED, true)
                .putExtra(PlayerActivity.EXTRA_SEEK_ENABLED, false)
                .addFlags(playerIntentFlags())
        )
        finishAfterNav(activity)
    }

    private suspend fun restoreEpisodeQueue(
        context: Context,
        sourceId: String,
        playback: VodPlayback
    ) {
        val seriesId = playback.seriesId ?: return
        val source = PlaylistStore.byId(sourceId) ?: return
        runCatching {
            val episodes = withContext(Dispatchers.IO) {
                PlaylistRepository.loadEpisodes(source, seriesId)
            }
            EpisodeQueue.set(
                sourceId,
                seriesId,
                playback.seriesName.orEmpty().ifBlank { playback.group },
                episodes,
                playback.url
            )
        }
    }

    suspend fun resolveLiveChannel(context: Context, sourceId: String): CatalogItem? =
        withContext(Dispatchers.IO) {
            var live = PlaylistRepository.memoryCached(sourceId, ContentKind.LIVE)
            if (live.isEmpty()) {
                live = PlaylistRepository.diskCached(context, sourceId, ContentKind.LIVE)
            }
            if (live.isEmpty()) {
                val source = PlaylistStore.byId(sourceId)
                    ?: PlaylistStore.sources().firstOrNull()
                if (source != null) {
                    runCatching {
                        live = PlaylistRepository.loadCatalog(context, source, ContentKind.LIVE)
                    }
                } else {
                    live = ChannelsCleanStore.ensureLoaded(context)
                        .map { ChannelsCleanStore.preferred(it) }
                }
            }
            val playable = live.filter { it.url.isNotBlank() || it.lives.isNotEmpty() }
                .map { ChannelsCleanStore.preferred(it) }
            if (playable.isEmpty()) return@withContext null
            ZapPlaylist.set(playable)
            val last = AppSettings.lastLiveItem()
            if (last != null) {
                playable.find {
                    (last.channelId.isNotBlank() && it.channelId == last.channelId) ||
                        it.url == last.url ||
                        (last.streamId != null && it.streamId == last.streamId) ||
                        it.lives.any { live -> live.url == last.url }
                } ?: playable.first()
            } else {
                playable.first()
            }
        }
}
