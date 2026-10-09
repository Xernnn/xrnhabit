package com.xern.xrnhabit.app.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** The permissions the core loop depends on (brainstorm §4.4 "onboarding is a live checklist"). */
enum class AppPermission(val label: String) {
    USAGE_ACCESS("Usage access"),
    OVERLAY("Draw over other apps"),
    NOTIFICATIONS("Notifications"),
    BATTERY_OPTIMIZATION("Ignore battery optimizations"),
}

fun AppPermission.isGranted(context: Context): Boolean = when (this) {
    AppPermission.USAGE_ACCESS -> hasUsageAccess(context)
    AppPermission.OVERLAY -> Settings.canDrawOverlays(context)
    AppPermission.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    AppPermission.BATTERY_OPTIMIZATION -> {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}

/** Deep-links straight to the screen that grants this permission, scoped to this app where possible. */
fun AppPermission.settingsIntent(context: Context): Intent = when (this) {
    AppPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    AppPermission.OVERLAY -> Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    AppPermission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    AppPermission.BATTERY_OPTIMIZATION -> Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}

fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
