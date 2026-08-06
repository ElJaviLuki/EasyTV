package com.eljaviluki.easytv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Tracks which EasyTV activity is resumed (for Home/Guide override). */
object AppForeground {
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) {
                    resumed = null
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    fun current(): Activity? = resumed?.get()

    /** True when live TV player is already in the foreground. */
    fun isLiveTvForeground(): Boolean {
        val player = current() as? PlayerActivity ?: return false
        return player.isLiveZap
    }
}
