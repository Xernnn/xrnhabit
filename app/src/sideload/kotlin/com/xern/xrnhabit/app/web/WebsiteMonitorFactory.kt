package com.xern.xrnhabit.app.web

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** `sideload` flavor: the [BrowserAccessibilityService] reader is declared in this flavor's manifest. */
fun createWebsiteMonitor(context: Context): WebsiteMonitor = AccessibilityWebsiteMonitor

private object AccessibilityWebsiteMonitor : WebsiteMonitor {
    override fun isSupported() = true

    override fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = ComponentName(context, BrowserAccessibilityService::class.java)
        val ids = setOf(component.flattenToString(), component.flattenToShortString())
        return enabled.split(':').any { entry -> ids.any { it.equals(entry, ignoreCase = true) } }
    }

    override fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
