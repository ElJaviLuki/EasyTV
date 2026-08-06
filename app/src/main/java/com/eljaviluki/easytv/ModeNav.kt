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
 * Rojo = TV · Verde = Series · Amarillo = Películas · Azul = Configuración
 */
object ModeNav {
    const val EXTRA_FROM_TV = "from_tv"

    /** Finish current screen unless it's Main (kept under) or Player (reused via singleTop). */
    private fun finishAfterNav(activity: AppCompatActivity) {
        if (activity is MainActivity || activity is PlayerActivity) return
        activity.finish()
    }

    private fun playerIntentFlags(): Int =
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun handleColorKey(activity: AppCompatActivity, keyCode: Int, sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        if (activity is PlayerActivity) {
            activity.persistPlaybackForNav()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                if (activity is PlayerActivity && activity.isLiveZap) return true
                openTv(activity, sourceId)
                true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                openSeries(activity, sourceId)
                true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                openMovies(activity, sourceId)
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                if (activity is SettingsActivity) return true
                openSettings(activity, sourceId, currentScreenFor(activity))
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
        if (activity is CatalogActivity && activity.currentKind == kind) {
            AppSettings.lastSourceId = sourceId
            AppSettings.lastScreen = when (kind) {
                ContentKind.LIVE -> AppScreen.CHANNELS
                ContentKind.SERIES -> AppScreen.SERIES
                ContentKind.MOVIES -> AppScreen.MOVIES
            }
            return
        }
        AppSettings.lastSourceId = sourceId
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
                .putExtra(CatalogActivity.EXTRA_SOURCE_ID, sourceId)
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
        AppSettings.lastSourceId = sourceId
        AppSettings.lastScreen = AppScreen.TV
        activity.lifecycleScope.launch {
            val channel = resolveLiveChannel(activity, sourceId) ?: run {
                openCatalog(activity, sourceId, ContentKind.LIVE, fromTv = true)
                return@launch
            }
            EpisodeQueue.clear()
            startLivePlayer(activity, sourceId, channel)
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
        val sourceId = AppSettings.lastSourceId
        if (sourceId.isBlank() || PlaylistStore.byId(sourceId) == null) return false
        when (AppSettings.lastScreen) {
            AppScreen.TV -> openTv(activity, sourceId)
            AppScreen.SERIES -> openCatalog(activity, sourceId, ContentKind.SERIES)
            AppScreen.MOVIES -> openCatalog(activity, sourceId, ContentKind.MOVIES)
            AppScreen.CHANNELS -> openCatalog(activity, sourceId, ContentKind.LIVE, fromTv = true)
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
            AppScreen.SETTINGS -> openSettings(
                activity,
                sourceId,
                AppSettings.settingsReturnScreen
            )
        }
        return true
    }

    private fun startLivePlayer(
        activity: AppCompatActivity,
        sourceId: String,
        channel: CatalogItem
    ) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, channel.url)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_GROUP, channel.group)
                .putExtra(PlayerActivity.EXTRA_NUMBER, channel.number)
                .putExtra(PlayerActivity.EXTRA_LOGO, channel.logo)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, channel.streamId ?: -1)
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
            val source = PlaylistStore.byId(sourceId) ?: return@withContext null
            var live = PlaylistRepository.memoryCached(source.id, ContentKind.LIVE)
            if (live.isEmpty()) {
                live = PlaylistRepository.diskCached(context, source.id, ContentKind.LIVE)
            }
            if (live.isEmpty()) {
                runCatching {
                    live = PlaylistRepository.loadCatalog(context, source, ContentKind.LIVE)
                }
            }
            val playable = live.filter { it.url.isNotBlank() }
            if (playable.isEmpty()) return@withContext null
            ZapPlaylist.set(playable)
            val last = AppSettings.lastLiveItem()
            if (last != null) {
                playable.find {
                    it.url == last.url || (last.streamId != null && it.streamId == last.streamId)
                } ?: playable.first()
            } else {
                playable.first()
            }
        }
}
