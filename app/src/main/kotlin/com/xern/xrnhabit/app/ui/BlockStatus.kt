package com.xern.xrnhabit.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.Schedule
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a block is doing right now, in words — shared by the block cards, the detail screen and
 * the block screen. [tone] reuses the state palette: ARMED = blocking (clay red), UNLOCKED =
 * free (green), EARNED = ready (amber).
 */
data class BlockStatusUi(
    val label: String,
    val icon: ImageVector,
    val tone: BlockState,
    val line: String,
    val fraction: Float?,
)

fun blockStatus(block: Block, world: World, engine: BlockEngine, clock: Clock): BlockStatusUi {
    val blocking = engine.isBlocking(world, block, clock)
    return when (block.mode) {
        BlockMode.HABIT -> BlockStatusUi(
            label = stateVerbose(block.state),
            icon = stateIcon(block.state),
            tone = block.state,
            line = progressLine(block, world),
            fraction = if (block.state == BlockState.UNLOCKED) null else triggerFraction(block, world),
        )

        BlockMode.ALLOWANCE -> {
            val used = engine.allowanceUsedMs(world, block, clock)
            val allowance = block.dailyAllowanceMs
            BlockStatusUi(
                label = if (blocking) "Limit reached" else "${formatRemaining(allowance - used)} left",
                icon = if (blocking) Icons.Filled.Lock else Icons.Filled.HourglassBottom,
                tone = if (blocking) BlockState.ARMED else BlockState.UNLOCKED,
                line = "${formatDuration(used)} of ${formatDuration(allowance)} used today" +
                    if (blocking) " · opens at midnight" else "",
                fraction = if (allowance == 0L) 1f else used.toFloat() / allowance,
            )
        }

        BlockMode.SCHEDULE -> {
            val until = if (blocking) freeAtWall(block, world, engine, clock) else null
            BlockStatusUi(
                label = if (blocking) "Blocking now" else "Off now",
                icon = if (blocking) Icons.Filled.Lock else Icons.Filled.Schedule,
                tone = if (blocking) BlockState.ARMED else BlockState.UNLOCKED,
                line = describeSchedule(block.schedule) +
                    (until?.let { " · until ${formatClockTime(it, clock.zone())}" } ?: ""),
                fraction = null,
            )
        }

        BlockMode.LOCKDOWN -> {
            val remaining = engine.lockdownRemainingMs(world, block.id, clock)
            val duration = world.lockdowns[block.id]?.durationMs ?: block.lockdownMs
            if (remaining > 0) {
                BlockStatusUi(
                    label = "Blocking",
                    icon = Icons.Filled.Lock,
                    tone = BlockState.ARMED,
                    line = "${formatRemaining(remaining)} left",
                    fraction = if (duration == 0L) 1f else 1f - remaining.toFloat() / duration,
                )
            } else {
                BlockStatusUi(
                    label = "Ended",
                    icon = Icons.Filled.LockOpen,
                    tone = BlockState.UNLOCKED,
                    line = "Not running",
                    fraction = null,
                )
            }
        }
    }
}

/** For a non-HABIT block that is blocking now: when it lets go, as wall-clock ms. Null if unknown/never. */
fun freeAtWall(block: Block, world: World, engine: BlockEngine, clock: Clock): Long? {
    val zone = clock.zone()
    val now = Instant.ofEpochMilli(clock.nowWallMs()).atZone(zone)
    return when (block.mode) {
        BlockMode.HABIT -> null
        BlockMode.LOCKDOWN -> engine.lockdownRemainingMs(world, block.id, clock)
            .takeIf { it > 0 }?.let { clock.nowWallMs() + it }
        BlockMode.ALLOWANCE -> now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        BlockMode.SCHEDULE -> {
            val s = block.schedule
            val w = s.activeWindow
            if (w == null) {
                // All day on active days: free at the first midnight that starts an inactive day.
                (1..7).map { now.toLocalDate().plusDays(it.toLong()) }
                    .firstOrNull { it.dayOfWeek.value !in s.activeDays }
                    ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            } else {
                val minute = now.hour * 60 + now.minute
                val endDate = if (w.startMinuteOfDay > w.endMinuteOfDay && minute >= w.startMinuteOfDay) {
                    now.toLocalDate().plusDays(1)
                } else {
                    now.toLocalDate()
                }
                endDate.atStartOfDay(zone).plusMinutes(w.endMinuteOfDay.toLong()).toInstant().toEpochMilli()
            }
        }
    }
}

/** "Weekdays · 09:00–17:00", "Every day · All day", "Mon, Wed · 22:00–06:00". */
fun describeSchedule(schedule: Schedule): String {
    val days = schedule.activeDays
    val dayText = when (days) {
        (1..7).toSet() -> "Every day"
        (1..5).toSet() -> "Weekdays"
        setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(", ") { shortDayName(it) }
    }
    val window = schedule.activeWindow
    val timeText = if (window == null) "All day" else "${minuteText(window.startMinuteOfDay)}–${minuteText(window.endMinuteOfDay)}"
    return "$dayText · $timeText"
}

fun shortDayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun minuteText(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** "17:00" today, "Mon 09:00" on another day. */
fun formatClockTime(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(wallMs).atZone(zone)
    val time = t.format(HourMinute)
    return if (t.toLocalDate() == LocalDate.now(zone)) time else "${shortDayName(t.dayOfWeek.value)} $time"
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

/** "3 h 12 min" / "45 min" / "< 1 min" / "0 min". */
fun formatDuration(ms: Long): String {
    if (ms in 1 until 60_000) return "< 1 min"
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/** Like [formatDuration] but rounds up, so a countdown never says "0 min" while time is left. */
fun formatRemaining(ms: Long): String =
    if (ms <= 0) "0 min" else formatDuration(((ms + 59_999) / 60_000) * 60_000)

/**
 * Recomposes the caller every [periodMs]. Countdowns need it: a lockdown or allowance changes by
 * time alone, so the World (and its StateFlow) often doesn't change at all.
 */
@Composable
fun rememberTicker(periodMs: Long = 1_000): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            tick++
        }
    }
    return tick
}
