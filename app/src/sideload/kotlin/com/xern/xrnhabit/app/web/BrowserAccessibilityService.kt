package com.xern.xrnhabit.app.web

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.xern.xrnhabit.app.block.BlockerForegroundService
import com.xern.xrnhabit.domain.Sites

/**
 * Reads the address bar of whichever browser is on screen and hands the host to [SiteSightings].
 * That is the only thing it does: no other app's content is read, nothing is stored here, and
 * nothing leaves the process. Brainstorm §2.1: Accessibility is never the *only* detection path —
 * app blocking keeps working without it; only website features depend on it.
 *
 * Every browser lays its toolbar out differently, so it tries known address-bar view ids first,
 * then any field whose id looks like one, then an editable field near the top of the screen.
 */
class BrowserAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var browsers: Set<String> = emptySet()
    private var pendingPackage: String? = null
    private val readNow = Runnable { pendingPackage?.let(::readAddressBar); pendingPackage = null }

    override fun onServiceConnected() {
        super.onServiceConnected()
        browsers = findBrowsers()
        SiteSightings.backAction = { performGlobalAction(GLOBAL_ACTION_BACK) }
        // A system-bound service may start a foreground service even when the process was
        // started in the background, so this also revives the blocker after the OS killed it.
        BlockerForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in browsers) return
        // Browsers fire content-changed events constantly while a page scrolls; read once the burst settles.
        pendingPackage = pkg
        handler.removeCallbacks(readNow)
        handler.postDelayed(readNow, SETTLE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        SiteSightings.backAction = null
        SiteSightings.clear()
        handler.removeCallbacks(readNow)
        return super.onUnbind(intent)
    }

    private fun readAddressBar(pkg: String) {
        // Not rootInActiveWindow: while the block screen is up, *it* can be the active window.
        val root = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg } }
            ?: return
        // No bar (toolbar scrolled away, fullscreen video): keep the last known host.
        val bar = findAddressBar(root, pkg) ?: return
        if (bar.isFocused) return // the user is typing a new address or a search
        val text = bar.text?.toString().orEmpty()
        val host = Sites.normalize(text)
        when {
            host != null -> SiteSightings.report(pkg, host)
            text.isBlank() || bar.isShowingHintText -> SiteSightings.report(pkg, null) // new tab page
            // A page title or something unparseable: keeping the last host is safer than
            // clearing it, which would let a blocked site slip out from under the shield.
            else -> Unit
        }
    }

    private fun findAddressBar(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in ID_SUFFIXES) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                .firstOrNull { it.text != null || it.isEditable }
                ?.let { return it }
        }
        return searchTree(root)
    }

    /** Breadth-first, bounded: a field whose id looks like an address bar, else an editable one up top. */
    private fun searchTree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenTop = Rect().also(root::getBoundsInScreen)
        val topBand = screenTop.top + (screenTop.height() * 0.3f).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var editableNearTop: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            if (id != null && ID_HINTS.any { it in id } && (node.text != null || node.isEditable)) return node
            if (editableNearTop == null && node.isEditable) {
                val r = Rect().also(node::getBoundsInScreen)
                if (r.top < topBand && Sites.normalize(node.text?.toString().orEmpty()) != null) editableNearTop = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return editableNearTop
    }

    private fun findBrowsers(): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
        return (resolved + KNOWN_BROWSERS).toSet() - packageName
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val MAX_NODES = 400

        /** Address-bar view ids across Chromium forks, Firefox, Samsung, Opera, DuckDuckGo. */
        val ID_SUFFIXES = listOf(
            "url_bar",
            "mozac_browser_toolbar_url_view",
            "location_bar_edit_text",
            "url_field",
            "omnibarTextInput",
            "search_box_text",
            "url",
        )
        val ID_HINTS = listOf("url_bar", "urlbar", "url_field", "url_view", "location_bar", "omnibar", "address_bar", "addressbar")
        val KNOWN_BROWSERS = setOf(
            "com.android.chrome",
            "net.quetta.browser",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.duckduckgo.mobile.android",
            "com.mi.globalbrowser",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
        )
    }
}
