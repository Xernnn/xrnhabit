package com.xern.xrnhabit.app.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * OEM battery managers (Xiaomi/MIUI, Oppo/ColorOS, Vivo, Huawei/EMUI, OnePlus) kill background
 * services that aren't explicitly whitelisted for autostart, regardless of the standard
 * battery-optimization exemption — brainstorm §8 calls this the highest-risk item with "no full
 * programmatic fix". Best effort: deep-link straight to the known autostart-manager screen for
 * the current manufacturer, falling back to the app's details page if that activity isn't
 * present on this particular ROM build. These are explicit-component intents, so they don't
 * need a `<queries>` manifest entry — Android 11+ package-visibility rules gate *querying*
 * other packages, not launching a component you already know by name.
 */
object OemAutostart {

    fun isKnownOem(): Boolean = candidates(Build.MANUFACTURER.lowercase()).isNotEmpty()

    fun open(context: Context) {
        for (candidate in candidates(Build.MANUFACTURER.lowercase())) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next known component for this manufacturer.
            }
        }
        context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun candidates(manufacturer: String): List<Intent> = when {
        "xiaomi" in manufacturer -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.permcenter.MainActivity"),
        )
        "oppo" in manufacturer -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo" in manufacturer -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        "huawei" in manufacturer -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        "oneplus" in manufacturer -> listOf(
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        else -> emptyList()
    }

    private fun component(pkg: String, cls: String) = Intent().apply { component = ComponentName(pkg, cls) }
}
