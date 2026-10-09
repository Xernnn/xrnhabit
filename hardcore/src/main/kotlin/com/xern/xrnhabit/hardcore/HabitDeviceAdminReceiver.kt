package com.xern.xrnhabit.hardcore

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Phase C. Being an active device admin blocks uninstall from Settings. Deactivation must go
 * through the app's password gate (not implemented here yet).
 *
 * This whole module is excluded from the `play` build flavor.
 */
class HabitDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: android.content.Intent): CharSequence =
        "Turning this off lets xrnhabit be uninstalled. Enter your app password to continue."

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, HabitDeviceAdminReceiver::class.java)
    }
}
