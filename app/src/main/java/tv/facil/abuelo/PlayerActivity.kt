package tv.facil.abuelo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.facil.abuelo.databinding.ActivityPlayerBinding
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

        private const val SEEK_STEP_MS = 5_000L
        private const val SEEK_HOLD_STEP_MS = 15_000L
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
    private var epgJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        binding.overlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        currentName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        currentGroup = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        currentNumber = intent.getIntExtra(EXTRA_NUMBER, 0)
        currentLogo = intent.getStringExtra(EXTRA_LOGO)
        currentStreamId = intent.getIntExtra(EXTRA_STREAM_ID, -1).takeIf { it > 0 }
        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        zapEnabled = intent.getBooleanExtra(EXTRA_ZAP_ENABLED, false)
        seekEnabled = intent.getBooleanExtra(EXTRA_SEEK_ENABLED, false)
        if (currentUrl.isBlank()) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            binding.playerView.useController = false
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    binding.nowEpg.text = "Error: ${error.errorCodeName}"
                    showOverlay(permanent = true)
                }
            })
        }
        playCurrent()
    }

    private fun playCurrent() {
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
        showOverlay()
        loadEpg()
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(currentUrl))
        exo.prepare()
        exo.playWhenReady = true
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

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        if (seekEnabled) binding.nowEpg.text = formatPositionHint()
        showOverlay()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        binding.nowEpg.text = formatPositionHint(target, duration)
        showOverlay()
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
        return if (durationMs > 0L && durationMs != androidx.media3.common.C.TIME_UNSET) {
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

    private fun zap(delta: Int) {
        if (!zapEnabled) return
        val next = ZapPlaylist.neighbor(currentUrl, delta) ?: return
        currentUrl = next.url
        currentName = next.name
        currentGroup = next.group
        currentNumber = next.number
        currentLogo = next.logo
        currentStreamId = next.streamId
        playCurrent()
    }

    private fun showOverlay(permanent: Boolean = false) {
        binding.overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        if (!permanent) {
            handler.postDelayed(hideOverlay, 3500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(+1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(-1)
                return true
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
                } else {
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (seekEnabled) binding.nowEpg.text = formatPositionHint()
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                if (seekEnabled) binding.nowEpg.text = formatPositionHint()
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                if (seekEnabled) binding.nowEpg.text = formatPositionHint()
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        epgJob?.cancel()
        handler.removeCallbacks(hideOverlay)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
