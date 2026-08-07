package com.eljaviluki.easytv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Legacy entry — redirects to [MainActivity] home sections. */
class SectionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sid = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        if (sid.isNotBlank()) AppSettings.lastSourceId = sid
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
