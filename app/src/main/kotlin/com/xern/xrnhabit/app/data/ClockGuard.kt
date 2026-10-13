package com.xern.xrnhabit.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.xern.xrnhabit.domain.Clock
import java.time.ZoneId
import kotlin.math.abs

class AndroidClock : Clock {
    override fun nowMonotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun nowWallMs(): Long = System.currentTimeMillis()

    /** Read each time: Android resets the process default when the time zone changes. */
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * The engine's clock: wall time with manual clock changes taken back out, so pushing the date
 * forward doesn't reset a daily limit and moving the time doesn't step around a schedule.
 *
 * Wall minus monotonic time only moves when the clock is set. While Android's automatic time is
 * off, every such jump is a manual change and is added to [skewMs], which [nowWallMs] takes back
 * off; once automatic time is on again the clock is trusted and the skew cleared. Jumps made while
 * xrnhabit wasn't running are caught at the next [check] too, unless the phone rebooted in
 * between: the monotonic clock restarts then, so there is nothing to compare against.
 */
class ClockGuard(private val context: Context, private val raw: Clock) : Clock {

    private val prefs = context.getSharedPreferences("clock_guard", Context.MODE_PRIVATE)

    /** How far the phone's clock has been moved by hand, in ms (positive = set ahead). */
    @Volatile
    var skewMs: Long = prefs.getLong(KEY_SKEW, 0)
        private set

    init {
        check()
    }

    override fun nowMonotonicMs(): Long = raw.nowMonotonicMs()
    override fun nowWallMs(): Long = raw.nowWallMs() - skewMs
    override fun zone(): ZoneId = raw.zone()

    /** Call on `ACTION_TIME_CHANGED` and now and then: books any clock jump since the last look. */
    @Synchronized
    fun check() {
        val mono = raw.nowMonotonicMs()
        val offset = raw.nowWallMs() - mono
        val lastMono = prefs.getLong(KEY_MONO, Long.MAX_VALUE)
        val lastOffset = prefs.getLong(KEY_OFFSET, offset)
        val sameBoot = mono >= lastMono // otherwise the two offsets aren't comparable
        val jumped = sameBoot && abs(offset - lastOffset) > TOLERANCE_MS
        var skew = skewMs
        if (automaticTime()) {
            skew = 0
        } else if (jumped) {
            skew += offset - lastOffset
        }
        // The baseline only needs writing when it moved: offsets don't change between clock sets.
        val write = skew != skewMs || jumped || !sameBoot || !prefs.contains(KEY_MONO)
        skewMs = skew
        if (write) {
            prefs.edit()
                .putLong(KEY_SKEW, skew)
                .putLong(KEY_OFFSET, offset)
                .putLong(KEY_MONO, mono)
                .apply()
        }
    }

    fun automaticTime(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1

    private companion object {
        const val KEY_SKEW = "skew"
        const val KEY_OFFSET = "offset"
        const val KEY_MONO = "mono"

        /** Clock drift and network corrections stay well under this; a hand-set change doesn't. */
        const val TOLERANCE_MS = 5_000L
    }
}
