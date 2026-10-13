package com.xern.xrnhabit.app.data

import androidx.core.util.AtomicFile
import com.xern.xrnhabit.domain.Telemetry
import java.io.File

/** One line of the local event log, as read back for Insights. */
sealed interface TelemetryEvent {
    val atWallMs: Long

    /** A blocked app was opened and the shield met it. */
    data class Blocked(val pkg: String, override val atWallMs: Long) : TelemetryEvent

    /** A habit block's habit was done (ARMED → EARNED). */
    data class HabitDone(val blockId: String, override val atWallMs: Long) : TelemetryEvent

    /** An unlock ended after [ms] of use. */
    data class UnlockUsed(val blockId: String, val ms: Long, override val atWallMs: Long) : TelemetryEvent
}

/**
 * Append-only local event log behind the Insights history. Local file only — no network, ever
 * (brainstorm §1). One `;`-separated line per event (the `.jsonl` name is historical).
 */
class LocalTelemetry(private val file: File) : Telemetry {

    override fun onBlockedImpression(pkg: String, atWallMs: Long) = append("blocked;$pkg;$atWallMs")
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = append("habit;$blockId;$atWallMs")
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long) =
        append("unlock;$blockId;$ms;$atWallMs")

    @Synchronized
    private fun append(line: String) {
        runCatching { file.appendText(line + "\n") }
    }

    /** Everything logged, oldest first. Blocking — call off the main thread. */
    @Synchronized
    fun events(): List<TelemetryEvent> = lines().mapNotNull(::parse)

    /** Drops events older than [keepDays], so the log can't grow without end. */
    @Synchronized
    fun prune(nowWallMs: Long, keepDays: Int = 120) {
        val cutoff = nowWallMs - keepDays * 24L * 60 * 60 * 1000
        val lines = lines()
        val kept = lines.filter { line -> parse(line)?.let { it.atWallMs >= cutoff } == true }
        if (kept.size == lines.size) return
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        runCatching {
            out.write(kept.joinToString("") { it + "\n" }.encodeToByteArray())
            atomic.finishWrite(out)
        }.onFailure { atomic.failWrite(out) }
    }

    private fun lines(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())

    private fun parse(line: String): TelemetryEvent? {
        val f = line.split(';')
        return when (f.firstOrNull()) {
            "blocked" -> f.getOrNull(2)?.toLongOrNull()?.let { TelemetryEvent.Blocked(f[1], it) }
            "habit" -> f.getOrNull(2)?.toLongOrNull()?.let { TelemetryEvent.HabitDone(f[1], it) }
            "unlock" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                if (ms != null && at != null) TelemetryEvent.UnlockUsed(f[1], ms, at) else null
            }
            else -> null
        }
    }
}
