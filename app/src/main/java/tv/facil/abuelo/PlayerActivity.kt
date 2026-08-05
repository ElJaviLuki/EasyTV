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
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        binding.overlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val group = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        binding.nowPlaying.text = name
        binding.nowGroup.text = group
        showOverlay()

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            binding.playerView.useController = false
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    binding.nowPlaying.text = name
                    binding.nowGroup.text = "Error: ${error.errorCodeName}"
                    showOverlay(permanent = true)
                }
            })
        }
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
