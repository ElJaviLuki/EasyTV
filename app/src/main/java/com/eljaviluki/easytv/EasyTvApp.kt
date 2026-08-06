package com.eljaviluki.easytv

import android.app.Application

class EasyTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        PlaylistStore.load(this)
    }
}
