package com.eljaviluki.easytv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eljaviluki.easytv.databinding.ActivityMainBinding

/**
 * Home: ¿Qué quieres ver? (TV / Series / Películas).
 * List picker lives under Settings — grandpa never sees "listas" here.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /** From Home/Guide accessibility override: jump straight to live TV. */
        const val EXTRA_LAUNCH_TV = "launch_tv"
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.brand.text = getString(R.string.app_name)
        binding.serverList.layoutManager = LinearLayoutManager(this)

        val sourceId = AppSettings.preferredSourceId()
        if (sourceId.isBlank() && PlaylistStore.sources().isEmpty()) {
            // TV still works from bundled channels; VOD needs playlists.json.
            binding.subtitle.text = getString(R.string.choose_section)
            binding.serverList.adapter = SectionAdapter(listOf(ContentKind.LIVE)) { _ ->
                ModeNav.openTv(this, "")
            }
        } else {
            binding.subtitle.text = getString(R.string.choose_section)
            binding.serverList.adapter = SectionAdapter(
                listOf(ContentKind.LIVE, ContentKind.SERIES, ContentKind.MOVIES)
            ) { kind ->
                val sid = AppSettings.preferredSourceId()
                when (kind) {
                    ContentKind.LIVE -> ModeNav.openTv(this, sid)
                    ContentKind.SERIES -> ModeNav.openCatalog(this, sid, ContentKind.SERIES)
                    ContentKind.MOVIES -> ModeNav.openCatalog(this, sid, ContentKind.MOVIES)
                }
            }
        }

        if (consumeLaunchTv(intent)) return
        if (savedInstanceState == null) {
            ModeNav.tryResume(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchTv(intent)
    }

    /** @return true if this intent requested live TV. */
    private fun consumeLaunchTv(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_LAUNCH_TV, false) != true) return false
        intent.removeExtra(EXTRA_LAUNCH_TV)
        ModeNav.openTv(this, AppSettings.preferredSourceId())
        return true
    }
}
