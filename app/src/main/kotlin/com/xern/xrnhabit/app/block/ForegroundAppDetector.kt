package com.xern.xrnhabit.app.block

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay

/**
 * Strategy behind the foreground-app signal. Phase A ships [UsageStatsDetector] only (Option C
 * in brainstorm §4.1): it survives Android 17 Advanced Protection Mode and carries no Play
 * policy risk. An `AccessibilityDetector` accelerator can be slotted in later without touching
 * callers.
 */
interface ForegroundAppDetector {
    /** Emits the current foreground package, or null before the first event is ever seen. */
    val foregroundPackage: Flow<String?>
}

/**
 * Tracks the foreground app by consuming `UsageEvents` incrementally from a moving cursor and
 * updating a single retained `current` value on every RESUMED / MOVE_TO_FOREGROUND.
 *
 * The retained value is the whole point: an earlier version re-queried a fixed 10 s window each
 * tick and returned `null` whenever that window happened to contain no resume events — which is
 * exactly what happens once you've sat in an app for ten seconds. The overlay treated that
 * `null` as "nothing is blocked" and dismissed itself. Here `current` only ever moves to
 * another package (the launcher's own RESUMED when you press Home, the next app you open); it is
 * never cleared, so the block never evaporates on its own.
 *
 * The cursor and `current` live on the detector, not in one collection of the flow: the service
 * stops collecting while the screen is off (polling then is pure battery drain) and the next
 * collection carries on from where the last one stopped, catching up on everything in between.
 *
 * It polls every [fastPollMs] while [fast] says a block could need covering at once, and only
 * every [slowPollMs] otherwise (no block yet: the site-time log is all that listens).
 */
class UsageStatsDetector(
    context: Context,
    private val fastPollMs: Long = 300L,
    private val slowPollMs: Long = 2_000L,
    private val fast: () -> Boolean = { true },
) : ForegroundAppDetector {

    private val usm = context.getSystemService(UsageStatsManager::class.java)

    // Seeded from the last minute so the very first poll already knows the current app.
    private var cursor = System.currentTimeMillis() - 60_000L
    private var current: String? = null

    override val foregroundPackage: Flow<String?> = flow {
        while (true) {
            emit(poll())
            delay(if (fast()) fastPollMs else slowPollMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    @Synchronized
    private fun poll(): String? {
        val now = System.currentTimeMillis()
        // Overlap the previous window slightly so an event landing between polls is not lost.
        val events = usm.queryEvents((cursor - 1_000L).coerceAtLeast(0L), now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // Same value: ACTIVITY_RESUMED (API 29) renamed MOVE_TO_FOREGROUND, which minSdk 26 still needs.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                current = event.packageName
            }
        }
        cursor = now
        return current
    }
}
