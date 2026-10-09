package com.xern.xrnhabit.app.block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the enforcement service after reboot or an app update. The engine rebuilds state
 * from storage and re-applies the shield before any UI is shown (I8). Phase B adds the
 * WorkManager watchdog and OEM autostart guidance.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> BlockerForegroundService.start(context)
        }
    }
}
