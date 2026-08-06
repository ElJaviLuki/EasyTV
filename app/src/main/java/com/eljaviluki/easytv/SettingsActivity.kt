package com.eljaviluki.easytv

import android.content.Intent
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
            AppSettings.preferredSourceId()
        }
        // Settings is reachable even without a VOD list (TV-only).
        if (sourceId.isNotBlank()) {
            AppSettings.lastSourceId = sourceId
        }
        AppSettings.lastScreen = AppScreen.SETTINGS

        binding.title.text = getString(R.string.settings_title)
        binding.subtitle.visibility = android.view.View.GONE

        refreshZapToggle()
        binding.toggleZapWrap.setOnClickListener {
            AppSettings.zapWrapAround = !AppSettings.zapWrapAround
            refreshZapToggle()
        }

        refreshListRow()
        binding.openListPicker.setOnClickListener {
            startActivity(
                Intent(this, SourcePickerActivity::class.java)
                    .putExtra(SourcePickerActivity.EXTRA_SOURCE_ID, sourceId)
            )
        }

        refreshHomeGuideRow()
        binding.toggleHomeGuide.setOnClickListener {
            HomeGuideAccess.openAccessibilitySettings(this)
        }

        binding.toggleZapWrap.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        sourceId = AppSettings.preferredSourceId().ifBlank { sourceId }
        refreshListRow()
        refreshHomeGuideRow()
    }

    private fun refreshZapToggle() {
        val on = AppSettings.zapWrapAround
        binding.toggleZapWrap.text = getString(
            if (on) R.string.settings_zap_wrap_on else R.string.settings_zap_wrap_off
        )
    }

    private fun refreshListRow() {
        val name = PlaylistStore.byId(AppSettings.preferredSourceId())?.name
            ?: getString(R.string.settings_list_none)
        binding.openListPicker.text = getString(R.string.settings_list_row, name)
    }

    private fun refreshHomeGuideRow() {
        val on = HomeGuideAccess.isEnabled(this)
        binding.toggleHomeGuide.text = getString(
            if (on) R.string.settings_home_guide_on else R.string.settings_home_guide_off
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val sid = AppSettings.preferredSourceId().ifBlank { sourceId }
            ModeNav.leaveSettings(this, sid)
            return true
        }
        val sid = AppSettings.preferredSourceId().ifBlank { sourceId }
        if (sid.isNotBlank() && ModeNav.handleColorKey(this, keyCode, sid)) return true
        return super.onKeyDown(keyCode, event)
    }
}
