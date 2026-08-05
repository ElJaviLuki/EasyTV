package tv.facil.abuelo

import android.app.Application

class TvFacilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaylistStore.load(this)
    }
}
