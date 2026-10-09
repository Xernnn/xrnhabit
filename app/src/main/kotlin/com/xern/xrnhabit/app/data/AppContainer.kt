package com.xern.xrnhabit.app.data

import android.content.Context
import android.os.SystemClock
import com.xern.xrnhabit.app.hardcore.HardcoreGuard
import com.xern.xrnhabit.app.hardcore.createHardcoreGuard
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.web.WebsiteMonitor
import com.xern.xrnhabit.app.web.createWebsiteMonitor
import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.Telemetry
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

/** Process-wide singletons. Created once in [com.xern.xrnhabit.app.XrnhabitApp]. */
class AppContainer(context: Context, val clock: Clock) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = BlockEngine()
    val persist: PersistPort = FilePersistPort(File(context.filesDir, "world.json"))
    val telemetry: Telemetry = LocalTelemetry(File(context.filesDir, "telemetry.jsonl"))

    /** The single source of truth for [World]. UI observes it, the service drives it. */
    val store = WorldStore(engine, persist, telemetry, clock, appScope)

    /** Anti-bypass (brainstorm §4.5) — real in the `sideload` flavor, a no-op in `play`. */
    val hardcoreGuard: HardcoreGuard = createHardcoreGuard(context)

    val websites: WebsiteMonitor = createWebsiteMonitor(context)

    val siteUsage = SiteUsageLog(File(context.filesDir, "site_usage.json"), clock)

    /** Habit ticks from xrnloops, our Loop fork (`../xrnloops`), for checklist items linked to it (I23). */
    val loop = LoopHabits(context, store, appScope)
}

class AndroidClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun nowMonotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun nowWallMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = zone
}

/**
 * Persists the whole [World] as one atomic JSON document (write-temp-then-rename). The data is
 * small and always read/written as a unit, so a single file beats a relational schema here.
 */
class FilePersistPort(private val file: File) : PersistPort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadWorld(): World = runCatching {
        json.decodeFromString(World.serializer(), file.readText())
    }.getOrDefault(World())

    @Synchronized
    override fun save(world: World) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(World.serializer(), world))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not persist world to ${file.path}" }
        }
    }
}
