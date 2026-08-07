package com.eljaviluki.easytv

import android.app.Activity
import android.os.Bundle

/**
 * Thin HOME entry so [MainActivity] stays a normal Leanback app
 * (eligible in kids / "Gestionar aplicaciones") while still competing
 * as the system Home / TV button target.
 */
class HomeEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(BootLaunch.mainIntent(this))
        finish()
    }
}
