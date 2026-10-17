package com.xern.xrnhabit.app.data

import androidx.core.util.AtomicFile
import com.xern.xrnhabit.domain.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Time spent on each website, per local day. Android keeps no per-site history we can read,
 * so this is the only record: it starts the day website tracking is switched on.
 *
 * Fed by the blocker service with the effective foreground site (null when the browser isn't in
 * front or the screen is off). Durations come from the monotonic clock and are attributed to
 * days by wall time, split at local midnight. Statistics only — not part of the engine's World.
 */
class SiteUsageLog(private val file: File, private val clock: Clock) {

    @Serializable
    private data class Stored(val days: Map<Long, Map<String, Long>> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val _days = MutableStateFlow(load())

    /** Epoch day -> host -> foreground ms. */
    val days: StateFlow<Map<Long, Map<String, Long>>> = _days.asStateFlow()

    private var openHost: String? = null
    private var openSinceMono = 0L

    @Synchronized
    fun onSite(host: String?) {
        close()
        openHost = host?.let(::displayHost)
        openSinceMono = clock.nowMonotonicMs()
    }

    /** Book the running interval so far, so Insights stays current and process death loses little. */
    @Synchronized
    fun flush() {
        val host = openHost ?: return
        close()
        openHost = host
        openSinceMono = clock.nowMonotonicMs()
    }

    private fun close() {
        val host = openHost ?: return
        openHost = null
        val duration = clock.nowMonotonicMs() - openSinceMono
        if (duration <= 0) return
        val end = clock.nowWallMs()
        var cursor = end - duration
        val days = _days.value.mapValues { it.value.toMutableMap() }.toMutableMap()
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(clock.zone()).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(clock.zone()).toInstant().toEpochMilli()
            val sliceEnd = minOf(end, nextMidnight)
            val day = days.getOrPut(date.toEpochDay()) { mutableMapOf() }
            day.merge(host, sliceEnd - cursor, Long::plus)
            cursor = sliceEnd
        }
        val oldest = Instant.ofEpochMilli(end).atZone(clock.zone()).toLocalDate().toEpochDay() - KEEP_DAYS
        val pruned = days.filterKeys { it > oldest }
        _days.value = pruned
        save(pruned)
    }

    private fun load(): Map<Long, Map<String, Long>> = runCatching {
        json.decodeFromString(Stored.serializer(), AtomicFile(file).readFully().decodeToString()).days
    }.getOrDefault(emptyMap())

    private fun save(days: Map<Long, Map<String, Long>>) {
        val atomic = AtomicFile(file)
        val out = runCatching { atomic.startWrite() }.getOrNull() ?: return
        runCatching {
            out.write(json.encodeToString(Stored.serializer(), Stored(days)).encodeToByteArray())
            atomic.finishWrite(out)
        }.onFailure { atomic.failWrite(out) }
    }

    private companion object {
        const val KEEP_DAYS = 14

        /** "www.youtube.com" and "m.youtube.com" are the same site to a person reading Insights. */
        fun displayHost(host: String): String {
            for (prefix in listOf("www.", "m.", "mobile.")) {
                val rest = host.removePrefix(prefix)
                if (rest != host && '.' in rest) return rest
            }
            return host
        }
    }
}
