package com.xern.xrnhabit.app.data

import androidx.core.util.AtomicFile
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.World
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * Persists the whole [World] as one JSON document. The data is small and always read/written as
 * a unit, so a single file beats a relational schema here. [AtomicFile] syncs the new copy to
 * disk before swapping it in, so a crash or power cut mid-save leaves the previous copy intact.
 */
class FilePersistPort(private val file: File) : PersistPort {

    private val atomic = AtomicFile(file)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Set when the file existed but couldn't be read at start: a copy of it, kept because the
     * next save would otherwise replace the user's blocks with an empty world for good.
     */
    @Volatile
    var unreadableCopy: File? = null
        private set

    override fun loadWorld(): World {
        val bytes = try {
            atomic.readFully()
        } catch (_: FileNotFoundException) {
            return World() // first run
        }
        return runCatching { json.decodeFromString(World.serializer(), bytes.decodeToString()) }
            .getOrElse {
                val copy = File(file.parentFile, "world.unreadable-${System.currentTimeMillis()}.json")
                if (runCatching { copy.writeBytes(bytes) }.isSuccess) unreadableCopy = copy
                World()
            }
    }

    @Synchronized
    override fun save(world: World) {
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(World.serializer(), world).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }
}

/**
 * Skips saves that only move timer bookkeeping forward. Every ~1 s tick advances
 * [com.xern.xrnhabit.domain.HabitProgress] markers, so saving each one rewrote the file every
 * second, all day. Anything else — a state change, a credit, the applied shield (so I7 still
 * holds: a new shield plan is always saved first) — is written at once; timer-only changes at
 * most every [maxDelayMs], and on [flush]. A process death loses at most that much accrual.
 */
class ThrottledPersist(
    private val inner: PersistPort,
    private val clock: Clock,
    private val maxDelayMs: Long = 30_000,
) : PersistPort {

    private var saved: World? = null
    private var savedAtMono = 0L
    private var pending: World? = null

    @Synchronized
    override fun loadWorld(): World = inner.loadWorld().also {
        saved = it
        savedAtMono = clock.nowMonotonicMs()
    }

    @Synchronized
    override fun save(world: World) {
        val last = saved
        val bookkeepingOnly = last != null && world.withoutBookkeeping() == last.withoutBookkeeping()
        if (bookkeepingOnly && clock.nowMonotonicMs() - savedAtMono < maxDelayMs) {
            pending = world
            return
        }
        write(world)
    }

    /** Writes a held-back save now (screen off, service stopping). */
    @Synchronized
    fun flush() {
        pending?.let(::write)
    }

    private fun write(world: World) {
        inner.save(world)
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        pending = null
    }

    private fun World.withoutBookkeeping(): World = copy(
        progress = progress.mapValues { (_, p) -> p.copy(accumulatedMs = 0, lastUpdated = 0) },
        foregroundPkg = null,
        foregroundSite = null,
    )
}
