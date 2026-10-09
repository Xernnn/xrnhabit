package com.xern.xrnhabit.app.web

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a browser's address bar showed. [host] is null on a new-tab page or a non-URL screen. */
data class SiteSighting(val browserPackage: String, val host: String?)

/**
 * In-process hand-off from the browser reader (an AccessibilityService that exists only in the
 * `sideload` flavor) to the blocker service. The main source set only ever sees this object, the
 * same way it only sees [com.xern.xrnhabit.app.hardcore.HardcoreGuard] and never `:hardcore`.
 */
object SiteSightings {
    private val _current = MutableStateFlow<SiteSighting?>(null)
    val current: StateFlow<SiteSighting?> = _current.asStateFlow()

    /** Set while the reader is connected: sends the system Back action to the browser. */
    @Volatile
    var backAction: (() -> Boolean)? = null

    /** Whether the reader is actually running, as opposed to merely switched on in Settings. */
    val connected: Boolean get() = backAction != null

    fun report(browserPackage: String, host: String?) {
        _current.value = SiteSighting(browserPackage, host)
    }

    fun clear() {
        _current.value = null
    }
}

/**
 * Website tracking & blocking needs the Accessibility reader, which carries Play-policy risk and
 * so ships in the `sideload` flavor only. `createWebsiteMonitor` (per-flavor factory) returns the
 * real implementation there and [NoopWebsiteMonitor] in `play`, where all website UI is hidden.
 */
interface WebsiteMonitor {
    fun isSupported(): Boolean

    /** Whether the user has switched the reader on in system Accessibility settings. */
    fun isEnabled(context: Context): Boolean

    fun settingsIntent(): Intent
}

enum class WebsiteTracking { OFF, NOT_RUNNING, ON }

/**
 * [WebsiteTracking.NOT_RUNNING] is the silent failure worth surfacing: switched on in
 * Accessibility settings, but Android never started the reader. On Xiaomi/MIUI that happens when
 * Autostart is off for xrnhabit — the system's attempt to start it is rejected and website
 * blocking quietly does nothing.
 */
fun WebsiteMonitor.tracking(context: Context): WebsiteTracking = when {
    !isEnabled(context) -> WebsiteTracking.OFF
    !SiteSightings.connected -> WebsiteTracking.NOT_RUNNING
    else -> WebsiteTracking.ON
}

object NoopWebsiteMonitor : WebsiteMonitor {
    override fun isSupported() = false
    override fun isEnabled(context: Context) = false
    override fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
