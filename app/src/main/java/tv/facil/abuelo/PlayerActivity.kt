package tv.facil.abuelo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import tv.facil.abuelo.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_GROUP = "group"
        /** When true, CH+/CH- zap within [ZapPlaylist]. */
        const val EXTRA_ZAP_ENABLED = "zap_enabled"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentUrl: String = ""
    private var currentName: String = ""
    private var currentGroup: String = ""
    private var zapEnabled: Boolean = false
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
        zapEnabled = intent.getBooleanExtra(EXTRA_ZAP_ENABLED, false)
        if (currentUrl.isBlank()) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            binding.playerView.useController = false
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    binding.nowGroup.text = "Error: ${error.errorCodeName}"
                    showOverlay(permanent = true)
                }
            })
        }
        playCurrent()
    }

    private fun playCurrent() {
        binding.nowPlaying.text = currentName
        binding.nowGroup.text = currentGroup
        showOverlay()
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(currentUrl))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun zap(delta: Int) {
        if (!zapEnabled) return
        val next = ZapPlaylist.neighbor(currentUrl, delta) ?: return
        currentUrl = next.url
        currentName = next.name
        currentGroup = next.group
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
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
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
        handler.removeCallbacks(hideOverlay)
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
