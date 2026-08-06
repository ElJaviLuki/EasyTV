package com.eljaviluki.easytv

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.eljaviluki.easytv.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sourceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty().ifBlank {
            AppSettings.lastSourceId
        }
        if (sourceId.isBlank() || PlaylistStore.byId(sourceId) == null) {
            finish()
            return
        }

        AppSettings.lastSourceId = sourceId
        AppSettings.lastScreen = AppScreen.SETTINGS

        binding.title.text = getString(R.string.settings_title)
        binding.subtitle.visibility = android.view.View.GONE
        refreshZapToggle()
        binding.toggleZapWrap.setOnClickListener {
            AppSettings.zapWrapAround = !AppSettings.zapWrapAround
            refreshZapToggle()
        }
        binding.toggleZapWrap.requestFocus()
    }

    private fun refreshZapToggle() {
        val on = AppSettings.zapWrapAround
        binding.toggleZapWrap.text = getString(
            if (on) R.string.settings_zap_wrap_on else R.string.settings_zap_wrap_off
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            ModeNav.leaveSettings(this, sourceId)
            return true
        }
        if (ModeNav.handleColorKey(this, keyCode, sourceId)) return true
        return super.onKeyDown(keyCode, event)
    }
}
