package com.xern.xrnhabit.app.data

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(val packageName: String, val label: String, val foregroundMs: Long)

data class DayUsage(val date: LocalDate, val totalMs: Long, val apps: List<AppUsage>)

/**
 * Per-app foreground time, the same thing Digital Wellbeing reports, rebuilt from raw
 * `UsageEvents` rather than `queryUsageStats`: the aggregated daily buckets are aligned to
 * whatever the OS rolled over at, not local midnight, so their "today" can include last night.
 * Sessions are clipped to local day boundaries here instead.
 */
object ScreenTime {

    /** Look back past the first day so apps already open at its midnight are counted from midnight. */
    private const val LOOKBACK_MS = 3 * 60 * 60_000L

    private val ALWAYS_EXCLUDED = setOf("com.android.systemui")

    /** Oldest first; the last entry is today (so far). Blocking — call off the main thread. */
    @SuppressLint("InlinedApi") // event-type constants are plain ints; newer ones just never occur on older OSes
    fun lastDays(context: Context, days: Int = 7, zone: ZoneId = ZoneId.systemDefault()): List<DayUsage> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val today = LocalDate.now(zone)
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val bounds = dates.map { it.atStartOfDay(zone).toInstant().toEpochMilli() } +
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val queryStart = bounds.first() - LOOKBACK_MS
        val excluded = ALWAYS_EXCLUDED + homePackages(context)

        val perDay = List(days) { HashMap<String, Long>() }
        fun credit(pkg: String, from: Long, to: Long) {
            if (pkg in excluded || to <= from) return
            for (d in 0 until days) {
                val overlap = minOf(to, bounds[d + 1]) - maxOf(from, bounds[d])
                if (overlap > 0) perDay[d].merge(pkg, overlap, Long::plus)
            }
        }

        // A package is foreground while at least one of its activities is resumed; tracking the
        // class names stops an in-app A→B transition (B resumes before A pauses) from closing it.
        val resumed = HashMap<String, MutableSet<String>>()
        val openSince = HashMap<String, Long>()
        val seen = HashSet<String>()
        var firstScreenOff: Long? = null
        fun closeAll(at: Long) {
            openSince.forEach { (pkg, since) -> credit(pkg, since, at) }
            openSince.clear()
            resumed.clear()
        }

        val events = usm.queryEvents(queryStart, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val cls = e.className.orEmpty()
            val ts = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = resumed.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty()) openSince[pkg] = ts
                    set += cls
                    seen += pkg
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = resumed[pkg]
                    if (set == null) {
                        // First thing we ever see for it is a pause: it was open before the query window.
                        if (pkg !in seen) credit(pkg, queryStart, minOf(ts, firstScreenOff ?: ts))
                    } else if (set.remove(cls) && set.isEmpty()) {
                        resumed -= pkg
                        openSince.remove(pkg)?.let { credit(pkg, it, ts) }
                    }
                    seen += pkg
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    if (firstScreenOff == null) firstScreenOff = ts
                    closeAll(ts)
                }
            }
        }
        closeAll(now)

        val labels = HashMap<String, String>()
        return dates.mapIndexed { d, date ->
            val apps = perDay[d].entries
                .map { (pkg, ms) -> AppUsage(pkg, labels.getOrPut(pkg) { InstalledApps.labelFor(context, pkg) }, ms) }
                .sortedByDescending { it.foregroundMs }
            DayUsage(date, apps.sumOf { it.foregroundMs }, apps)
        }
    }

    /** Launchers: Digital Wellbeing doesn't count time on the home screen as app usage. */
    private fun homePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }
}
