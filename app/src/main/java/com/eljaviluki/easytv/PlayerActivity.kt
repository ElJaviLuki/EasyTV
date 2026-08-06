package com.eljaviluki.easytv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.eljaviluki.easytv.databinding.ActivityPlayerBinding
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_GROUP = "group"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_SOURCE_ID = "source_id"
        /** When true, CH+/CH- zap within [ZapPlaylist]. */
        const val EXTRA_ZAP_ENABLED = "zap_enabled"
        /** When true, DPAD left/right seek (series/movies). */
        const val EXTRA_SEEK_ENABLED = "seek_enabled"
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_START_PAUSED = "start_paused"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_NAME = "series_name"

        private const val SEEK_STEP_MS = 5_000L
        private const val SEEK_HOLD_STEP_MS = 15_000L
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_TICK_MS = 500L
        private const val NEXT_EPISODE_DELAY_SEC = 10
        private const val CENTER_TRIPLE_WINDOW_MS = 900L
        private const val PLAY_ICON_FLASH_MS = 700L
        private const val CHANNEL_ENTRY_DELAY_MS = 2500L
        private const val CHANNEL_ENTRY_MAX_DIGITS = 5
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentUrl: String = ""
    private var currentName: String = ""
    private var currentGroup: String = ""
    private var currentNumber: Int = 0
    private var currentLogo: String? = null
    private var currentStreamId: Int? = null
    private var sourceId: String = ""
    private var zapEnabled: Boolean = false
    private var seekEnabled: Boolean = false
    private var seriesId: Int? = null
    private var seriesName: String? = null
    private var pendingStartPositionMs: Long = 0L
    private var startPaused: Boolean = false
    private var endPromptVisible: Boolean = false
    private var nextCountdownSec: Int = NEXT_EPISODE_DELAY_SEC
    private var centerPressCount: Int = 0
    private var lastCenterPressAt: Long = 0L
    private var channelEntryBuffer: String = ""
    private var epgJob: Job? = null

    val isLiveZap: Boolean get() = zapEnabled
    /** Series only when this playback was opened with a series id (not leftover EpisodeQueue). */
    val isSeriesVod: Boolean get() = seekEnabled && seriesId != null
    val isMovieVod: Boolean get() = seekEnabled && seriesId == null

    private val handler = Handler(Looper.getMainLooper())
    private val resetCenterPresses = Runnable { centerPressCount = 0 }
    private val commitChannelEntry = Runnable { commitChannelNumberEntry() }
    private val hideTransportIconRunnable = Runnable { clearTransportIcon() }
    private val hideOverlay = Runnable {
        stopProgressTicks()
        binding.overlay.visibility = View.GONE
    }
    private val progressTick = object : Runnable {
        override fun run() {
            if (!seekEnabled || endPromptVisible || binding.overlay.visibility != View.VISIBLE) return
            updateProgressUi()
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }
    private val nextEpisodeTick = object : Runnable {
        override fun run() {
            if (!endPromptVisible || !EpisodeQueue.hasNext()) return
            nextCountdownSec -= 1
            if (nextCountdownSec <= 0) {
                playNextEpisode()
                return
            }
            binding.btnNextEpisode.text =
                getString(R.string.next_episode_countdown, nextCountdownSec)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!applyIntent(intent)) {
            finish()
            return
        }

        binding.btnNextEpisode.setOnClickListener { playNextEpisode() }
        binding.btnBackToTv.setOnClickListener { goToLiveTv() }

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            binding.playerView.useController = false
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    binding.nowEpg.text = "Error: ${error.errorCodeName}"
                    showOverlay(permanent = true)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (seekEnabled && !endPromptVisible) updateProgressUi()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (seekEnabled && !endPromptVisible) updateProgressUi()
                    if (playbackState == Player.STATE_ENDED && EpisodeQueue.isActive) {
                        showEndPrompt()
                    }
                }
            })
        }
        playCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!applyIntent(intent)) return
        playCurrent()
    }

    /** @return false if intent has no playable URL. */
    private fun applyIntent(intent: Intent): Boolean {
        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        currentName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        currentGroup = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        currentNumber = intent.getIntExtra(EXTRA_NUMBER, 0)
        currentLogo = intent.getStringExtra(EXTRA_LOGO)
        currentStreamId = intent.getIntExtra(EXTRA_STREAM_ID, -1).takeIf { it > 0 }
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        zapEnabled = intent.getBooleanExtra(EXTRA_ZAP_ENABLED, false)
        seekEnabled = intent.getBooleanExtra(EXTRA_SEEK_ENABLED, false)
        // Only trust explicit extras — never inherit EpisodeQueue (would tag movies as series).
        seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1).takeIf { it > 0 }
        seriesName = intent.getStringExtra(EXTRA_SERIES_NAME)?.ifBlank { null }
            ?: seriesId?.let { EpisodeQueue.seriesName.takeIf { n -> n.isNotBlank() } }
        pendingStartPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)
        startPaused = intent.getBooleanExtra(EXTRA_START_PAUSED, false)
        if (currentUrl.isBlank()) return false

        if (zapEnabled || isMovieVod) {
            EpisodeQueue.clear()
        }

        AppSettings.lastSourceId = sourceId
        when {
            zapEnabled -> AppSettings.lastScreen = AppScreen.TV
            isSeriesVod -> AppSettings.lastScreen = AppScreen.STREAMING_SERIES
            seekEnabled -> AppSettings.lastScreen = AppScreen.STREAMING_MOVIE
        }
        binding.progressBar.visibility = if (seekEnabled) View.VISIBLE else View.GONE
        return true
    }

    private fun playCurrent() {
        dismissEndPrompt()
        clearChannelNumberEntry()
        if (!startPaused) clearTransportIcon()
        binding.infoNumber.text = if (currentNumber > 0) currentNumber.toString() else ""
        binding.nowPlaying.text = currentName
        binding.nowGroup.text = currentGroup
        binding.nowEpg.text = when {
            zapEnabled -> "Cargando guía…"
            seekEnabled -> formatPositionHint()
            else -> ""
        }
        binding.infoLogo.load(currentLogo) {
            crossfade(true)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
        binding.progressBar.visibility = if (seekEnabled) View.VISIBLE else View.GONE
        if (zapEnabled) {
            AppSettings.lastScreen = AppScreen.TV
            AppSettings.saveLastLive(
                CatalogItem(
                    number = currentNumber,
                    name = currentName,
                    group = currentGroup,
                    logo = currentLogo,
                    url = currentUrl,
                    streamId = currentStreamId
                )
            )
        } else if (seekEnabled) {
            persistVodState()
        }
        updateProgressUi()
        showOverlay()
        loadEpg()
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(currentUrl))
        exo.prepare()
        if (pendingStartPositionMs > 0L) {
            exo.seekTo(pendingStartPositionMs)
            pendingStartPositionMs = 0L
        }
        val resumePaused = startPaused && seekEnabled
        startPaused = false
        exo.playWhenReady = !resumePaused
        if (resumePaused) {
            showTransportIcon(playing = false)
        }
    }

    fun persistPlaybackForNav() {
        if (zapEnabled) {
            AppSettings.saveLastLive(
                CatalogItem(
                    number = currentNumber,
                    name = currentName,
                    group = currentGroup,
                    logo = currentLogo,
                    url = currentUrl,
                    streamId = currentStreamId
                )
            )
            AppSettings.lastScreen = AppScreen.TV
        } else if (seekEnabled) {
            persistVodState()
        }
    }

    /**
     * @param updateScreen when true, marks series/movies hub as streaming.
     *                     onStop uses false so leaving to catalog (hub=catalog) is not overwritten.
     */
    private fun persistVodState(updateScreen: Boolean = true) {
        if (!seekEnabled) return
        val sid = seriesId
        if (updateScreen) {
            AppSettings.lastScreen =
                if (sid != null) AppScreen.STREAMING_SERIES else AppScreen.STREAMING_MOVIE
        }
        AppSettings.saveLastVod(
            url = currentUrl,
            name = currentName,
            group = currentGroup,
            logo = currentLogo,
            number = currentNumber,
            seriesId = sid,
            seriesName = if (sid != null) seriesName else null,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        )
    }

    private fun leaveVodToCatalog() {
        persistVodState()
        if (isSeriesVod) {
            ModeNav.openCatalog(this, sourceId, ContentKind.SERIES)
        } else {
            ModeNav.openCatalog(this, sourceId, ContentKind.MOVIES)
        }
    }

    private fun applyItem(item: CatalogItem, live: Boolean) {
        currentUrl = item.url
        currentName = item.name
        currentGroup = if (live) item.group else EpisodeQueue.seriesName.ifBlank { item.group }
        currentNumber = item.number
        currentLogo = item.logo
        currentStreamId = item.streamId
        zapEnabled = live
        seekEnabled = !live
    }

    private fun loadEpg() {
        epgJob?.cancel()
        val streamId = currentStreamId
        val source = PlaylistStore.byId(sourceId)
        if (!zapEnabled || streamId == null || source == null) {
            if (!seekEnabled) binding.nowEpg.text = ""
            return
        }
        epgJob = lifecycleScope.launch {
            val now = EpgRepository.nowPlaying(source, streamId)
            binding.nowEpg.text = now?.scheduleLine().orEmpty()
        }
    }

    private fun showEndPrompt() {
        if (endPromptVisible) return
        endPromptVisible = true
        clearTransportIcon()
        handler.removeCallbacks(hideOverlay)
        stopProgressTicks()
        binding.overlay.visibility = View.GONE

        val hasNext = EpisodeQueue.hasNext()
        binding.btnNextEpisode.visibility = if (hasNext) View.VISIBLE else View.GONE
        binding.endOverlay.visibility = View.VISIBLE

        if (hasNext) {
            nextCountdownSec = NEXT_EPISODE_DELAY_SEC
            binding.btnNextEpisode.text =
                getString(R.string.next_episode_countdown, nextCountdownSec)
            binding.btnNextEpisode.post { binding.btnNextEpisode.requestFocus() }
            handler.removeCallbacks(nextEpisodeTick)
            handler.postDelayed(nextEpisodeTick, 1_000L)
        } else {
            binding.btnBackToTv.post { binding.btnBackToTv.requestFocus() }
        }
    }

    private fun dismissEndPrompt() {
        endPromptVisible = false
        handler.removeCallbacks(nextEpisodeTick)
        binding.endOverlay.visibility = View.GONE
        binding.btnNextEpisode.visibility = View.GONE
        binding.btnNextEpisode.text = getString(R.string.next_episode)
        binding.btnBackToTv.isEnabled = true
        binding.btnBackToTv.text = getString(R.string.back_to_tv)
    }

    private fun playNextEpisode() {
        val next = EpisodeQueue.advance() ?: return
        applyItem(next, live = false)
        seriesId = EpisodeQueue.seriesId ?: seriesId
        seriesName = EpisodeQueue.seriesName.ifBlank { seriesName }
        playCurrent()
    }

    private fun goToLiveTv() {
        if (!binding.btnBackToTv.isEnabled) return
        handler.removeCallbacks(nextEpisodeTick)
        binding.btnBackToTv.isEnabled = false
        binding.btnBackToTv.text = getString(R.string.loading_tv)

        val sid = sourceId.ifBlank { EpisodeQueue.sourceId }
        val source = PlaylistStore.byId(sid)
        if (source == null) {
            binding.btnBackToTv.isEnabled = true
            binding.btnBackToTv.text = getString(R.string.back_to_tv)
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                var live = PlaylistRepository.memoryCached(source.id, ContentKind.LIVE)
                if (live.isEmpty()) {
                    live = PlaylistRepository.diskCached(applicationContext, source.id, ContentKind.LIVE)
                }
                if (live.isEmpty()) {
                    live = PlaylistRepository.loadCatalog(applicationContext, source, ContentKind.LIVE)
                }
                val first = live.firstOrNull { it.url.isNotBlank() }
                    ?: error("Sin canales")
                EpisodeQueue.clear()
                ZapPlaylist.set(live)
                sourceId = source.id
                AppSettings.lastScreen = AppScreen.TV
                applyItem(first, live = true)
                playCurrent()
            } catch (e: Exception) {
                binding.btnBackToTv.isEnabled = true
                binding.btnBackToTv.text = getString(R.string.back_to_tv)
                binding.endTitle.text = getString(R.string.load_error)
            }
        }
    }

    private fun togglePlayPause() {
        if (endPromptVisible) return
        val exo = player ?: return
        val willPlay = !exo.isPlaying
        if (exo.isPlaying) exo.pause() else exo.play()
        if (seekEnabled) {
            updateProgressUi()
            showTransportIcon(playing = willPlay)
        }
        showOverlay()
    }

    private fun showTransportIcon(playing: Boolean) {
        if (!seekEnabled || endPromptVisible) {
            clearTransportIcon()
            return
        }
        handler.removeCallbacks(hideTransportIconRunnable)
        binding.transportIcon.animate().cancel()
        binding.transportIcon.setImageResource(
            if (playing) R.drawable.ic_transport_play else R.drawable.ic_transport_pause
        )
        binding.transportIcon.alpha = 1f
        binding.transportIcon.scaleX = 0.86f
        binding.transportIcon.scaleY = 0.86f
        binding.transportIcon.visibility = View.VISIBLE
        binding.transportIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .start()
        if (playing) {
            handler.postDelayed(hideTransportIconRunnable, PLAY_ICON_FLASH_MS)
        }
    }

    private fun clearTransportIcon() {
        handler.removeCallbacks(hideTransportIconRunnable)
        binding.transportIcon.animate().cancel()
        binding.transportIcon.visibility = View.GONE
        binding.transportIcon.alpha = 1f
        binding.transportIcon.scaleX = 1f
        binding.transportIcon.scaleY = 1f
    }

    private fun seekBy(deltaMs: Long) {
        if (endPromptVisible) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateProgressUi(target, duration)
        showOverlay()
    }

    private fun updateProgressUi(
        positionMs: Long = player?.currentPosition ?: 0L,
        durationMs: Long = player?.duration ?: 0L
    ) {
        if (!seekEnabled) return
        binding.nowEpg.text = formatPositionHint(positionMs, durationMs)
        val durationOk = durationMs > 0L && durationMs != C.TIME_UNSET
        binding.progressBar.progress = if (durationOk) {
            ((positionMs.toDouble() / durationMs) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
        } else {
            0
        }
        val buffered = player?.bufferedPosition ?: 0L
        binding.progressBar.secondaryProgress = if (durationOk) {
            ((buffered.toDouble() / durationMs) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
        } else {
            0
        }
    }

    private fun startProgressTicks() {
        if (!seekEnabled || endPromptVisible) return
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
    }

    private fun stopProgressTicks() {
        handler.removeCallbacks(progressTick)
    }

    private fun seekStepMs(event: KeyEvent?): Long {
        val repeat = event?.repeatCount ?: 0
        return if (repeat == 0) SEEK_STEP_MS else SEEK_HOLD_STEP_MS
    }

    private fun formatPositionHint(
        positionMs: Long = player?.currentPosition ?: 0L,
        durationMs: Long = player?.duration?.takeIf { it > 0 } ?: 0L
    ): String {
        val pos = formatMs(positionMs)
        return if (durationMs > 0L && durationMs != C.TIME_UNSET) {
            val playing = player?.isPlaying == true
            val state = if (playing) "" else "  ·  Pausado"
            "$pos / ${formatMs(durationMs)}$state"
        } else {
            pos
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = max(0L, TimeUnit.MILLISECONDS.toSeconds(ms))
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    private fun onLiveCenterPress() {
        val now = System.currentTimeMillis()
        if (now - lastCenterPressAt > CENTER_TRIPLE_WINDOW_MS) {
            centerPressCount = 0
        }
        lastCenterPressAt = now
        centerPressCount += 1
        handler.removeCallbacks(resetCenterPresses)
        if (centerPressCount >= 3) {
            centerPressCount = 0
            ModeNav.openChannelCatalog(this, sourceId)
            return
        }
        handler.postDelayed(resetCenterPresses, CENTER_TRIPLE_WINDOW_MS)
        showOverlay()
    }

    private fun zap(delta: Int) {
        if (!zapEnabled || endPromptVisible) return
        clearChannelNumberEntry()
        val next = ZapPlaylist.neighbor(currentUrl, delta) ?: return
        applyItem(next, live = true)
        playCurrent()
    }

    private fun digitFromKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> null
    }

    private fun onChannelDigit(digit: Int) {
        if (!zapEnabled || endPromptVisible) return
        if (channelEntryBuffer.length >= CHANNEL_ENTRY_MAX_DIGITS) {
            channelEntryBuffer = ""
        }
        channelEntryBuffer += digit.toString()
        binding.channelNumberEntry.text = channelEntryBuffer
        binding.channelNumberEntry.visibility = View.VISIBLE
        handler.removeCallbacks(commitChannelEntry)
        handler.postDelayed(commitChannelEntry, CHANNEL_ENTRY_DELAY_MS)
    }

    private fun commitChannelNumberEntry() {
        val raw = channelEntryBuffer
        clearChannelNumberEntry()
        if (!zapEnabled || raw.isBlank()) return
        val number = raw.toIntOrNull() ?: return
        val channel = ZapPlaylist.byNumber(number) ?: return
        applyItem(channel, live = true)
        playCurrent()
    }

    private fun clearChannelNumberEntry() {
        handler.removeCallbacks(commitChannelEntry)
        channelEntryBuffer = ""
        binding.channelNumberEntry.visibility = View.GONE
        binding.channelNumberEntry.text = ""
    }

    private fun showOverlay(permanent: Boolean = false) {
        if (endPromptVisible) return
        binding.overlay.visibility = View.VISIBLE
        if (seekEnabled) updateProgressUi()
        startProgressTicks()
        handler.removeCallbacks(hideOverlay)
        if (!permanent) {
            handler.postDelayed(hideOverlay, 3500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (endPromptVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    goToLiveTv()
                    return true
                }
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_PROG_GREEN,
                KeyEvent.KEYCODE_PROG_YELLOW,
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    if (ModeNav.handleColorKey(this, keyCode, sourceId)) return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> return true
            }
            // Let DPAD move focus between end-prompt buttons.
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(+1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(-1)
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9 -> {
                val digit = digitFromKey(keyCode)
                if (digit != null && zapEnabled) {
                    onChannelDigit(digit)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (seekEnabled) {
                    seekBy(seekStepMs(event))
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (seekEnabled) {
                    seekBy(-seekStepMs(event))
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (seekEnabled) {
                    togglePlayPause()
                } else if (zapEnabled) {
                    onLiveCenterPress()
                } else {
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW,
            KeyEvent.KEYCODE_PROG_BLUE -> {
                if (ModeNav.handleColorKey(this, keyCode, sourceId)) return true
            }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                if (seekEnabled) {
                    updateProgressUi()
                    showTransportIcon(playing = true)
                }
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                if (seekEnabled) {
                    updateProgressUi()
                    showTransportIcon(playing = false)
                }
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (seekEnabled) {
                    leaveVodToCatalog()
                } else {
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        // Position only — do not reset hub/screen (e.g. green → catalog already set hub).
        if (seekEnabled) persistVodState(updateScreen = false)
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        epgJob?.cancel()
        stopProgressTicks()
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(nextEpisodeTick)
        handler.removeCallbacks(resetCenterPresses)
        handler.removeCallbacks(hideTransportIconRunnable)
        handler.removeCallbacks(commitChannelEntry)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
