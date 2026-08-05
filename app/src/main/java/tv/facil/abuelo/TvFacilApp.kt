package tv.facil.abuelo

import android.app.Application

class TvFacilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        PlaylistStore.load(this)
    }
}
