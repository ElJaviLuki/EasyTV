package com.eljaviluki.easytv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object HomeGuideAccess {
    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, HomeGuideAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val want = serviceComponent(context)
        if (enabled.any { it.resolveInfo.serviceInfo.let { s ->
                s.packageName == want.packageName && s.name == want.className
            }
        }) {
            return true
        }
        // Fallback: Secure setting (some TV builds lag the manager list).
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val flat = want.flattenToString()
        val shortFlat = want.flattenToShortString()
        return raw.split(':').any { part ->
            part.equals(flat, ignoreCase = true) ||
                part.equals(shortFlat, ignoreCase = true) ||
                ComponentName.unflattenFromString(part)?.let { it == want } == true
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (base in intents) {
            val intent = Intent(base)
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }
}
