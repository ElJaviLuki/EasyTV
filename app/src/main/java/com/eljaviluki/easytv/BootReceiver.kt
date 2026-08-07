package com.eljaviluki.easytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Starts EasyTV as soon as the device finishes booting so it can take over
 * from the OEM TV launcher (together with HOME / accessibility remaps).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action !in HANDLED_ACTIONS) return
        Log.i(TAG, "boot signal: $action")

        // Keep the process alive briefly while storage / system UI settle.
        val pending = goAsync()
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        fun launch(attempt: Int) {
            runCatching {
                // Reload playlists — external storage may not be ready at first BOOT_COMPLETED.
                PlaylistStore.load(appContext)
                AppSettings.init(appContext)
                appContext.startActivity(BootLaunch.mainIntent(appContext))
                Log.i(TAG, "launched EasyTV (attempt $attempt)")
            }.onFailure {
                Log.w(TAG, "launch attempt $attempt failed: ${it.message}")
            }
        }

        handler.post {
            launch(attempt = 1)
        }
        // Retry once — many TV boxes mount app files a second or two later.
        handler.postDelayed({
            launch(attempt = 2)
            pending.finish()
        }, RETRY_DELAY_MS)
    }

    companion object {
        private const val TAG = "EasyTV-Boot"
        private const val RETRY_DELAY_MS = 2_500L

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}

object BootLaunch {
    const val EXTRA_FROM_BOOT = "from_boot"

    fun mainIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            .putExtra(EXTRA_FROM_BOOT, true)
}
