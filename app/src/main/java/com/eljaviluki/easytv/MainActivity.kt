package com.eljaviluki.easytv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eljaviluki.easytv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindUi(resume = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home / boot may re-deliver MAIN while we are already alive.
        if (intent.getBooleanExtra(BootLaunch.EXTRA_FROM_BOOT, false) ||
            intent.hasCategory(Intent.CATEGORY_HOME) ||
            intent.action == Intent.ACTION_MAIN
        ) {
            PlaylistStore.load(this)
            bindUi(resume = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PlaylistStore.sources().isNotEmpty()) {
            binding.subtitle.text = getString(R.string.choose_server)
        }
    }

    private fun bindUi(resume: Boolean) {
        val sources = PlaylistStore.sources()
        if (sources.isEmpty()) {
            binding.brand.text = getString(R.string.app_name)
            binding.subtitle.text = PlaylistStore.errorMessage()
                ?: getString(R.string.missing_playlists)
            binding.serverList.layoutManager = LinearLayoutManager(this)
            binding.serverList.adapter = ServerAdapter(emptyList()) {}
            return
        }

        binding.subtitle.text = getString(R.string.choose_server)
        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = ServerAdapter(sources) { source ->
            AppSettings.lastSourceId = source.id
            startActivity(
                Intent(this, SectionActivity::class.java)
                    .putExtra(SectionActivity.EXTRA_SOURCE_ID, source.id)
            )
        }

        // Resume last screen on top; keep this list underneath for Back.
        if (resume) {
            ModeNav.tryResume(this)
        }
    }
}
