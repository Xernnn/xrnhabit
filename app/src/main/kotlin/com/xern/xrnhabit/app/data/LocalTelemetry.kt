package com.xern.xrnhabit.app.data

import com.xern.xrnhabit.domain.Telemetry
import java.io.File

/**
 * Append-only local event log for the Insights screen. Local file only — no network, ever
 * (brainstorm §1). Phase D replaces the aggregation with `UsageStatsManager` queries.
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

    fun readLines(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())
}
