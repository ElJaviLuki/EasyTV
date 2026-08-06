package com.eljaviluki.easytv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Intercepts remote Home / Guide (and TCL custom 4001) and opens EasyTV live
 * instead of the Google TV launcher.
 *
 * Must be enabled once under system Accessibility settings.
 */
class HomeGuideAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        } ?: return
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isTargetKey(event.keyCode)) return false
        // Consume UP as well so the system/launcher never sees the press.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true

        val now = SystemClock.uptimeMillis()
        if (now - lastHandledAtMs < DEBOUNCE_MS) return true
        lastHandledAtMs = now

        if (AppForeground.isLiveTvForeground()) return true

        launchEasyTv()
        return true
    }

    private fun launchEasyTv() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LAUNCH_TV, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        startActivity(intent)
    }

    companion object {
        /** TCL maps the physical TV/Guide key to this custom code (then may inject GUIDE). */
        const val TCL_KEYCODE_TV = 4001

        private const val DEBOUNCE_MS = 600L

        @Volatile
        private var lastHandledAtMs: Long = 0L

        fun isTargetKey(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_HOME ||
                keyCode == KeyEvent.KEYCODE_GUIDE ||
                keyCode == TCL_KEYCODE_TV
    }
}
