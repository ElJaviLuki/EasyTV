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
        if (savedInstanceState == null) {
            ModeNav.tryResume(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PlaylistStore.sources().isNotEmpty()) {
            binding.subtitle.text = getString(R.string.choose_server)
        }
    }
}
