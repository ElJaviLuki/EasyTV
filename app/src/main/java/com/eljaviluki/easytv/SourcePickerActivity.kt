package com.eljaviluki.easytv

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eljaviluki.easytv.databinding.ActivityMainBinding

/**
 * VOD server list (Manolo1…), opened from Settings — not the app home.
 */
class SourcePickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }

    private lateinit var sourceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
            .ifBlank { AppSettings.preferredSourceId() }

        binding.brand.text = getString(R.string.settings_list_title)
        binding.subtitle.text = getString(R.string.settings_list_subtitle)

        val sources = PlaylistStore.sources()
        binding.serverList.layoutManager = LinearLayoutManager(this)
        if (sources.isEmpty()) {
            binding.subtitle.text = PlaylistStore.errorMessage()
                ?: getString(R.string.missing_playlists)
            binding.serverList.adapter = ServerAdapter(emptyList()) {}
            return
        }

        binding.serverList.adapter = ServerAdapter(sources) { source ->
            AppSettings.lastSourceId = source.id
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        if (sourceId.isNotBlank() && ModeNav.handleColorKey(this, keyCode, sourceId)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
