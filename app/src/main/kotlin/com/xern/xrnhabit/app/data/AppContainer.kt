package com.xern.xrnhabit.app.data

import android.content.Context
import com.xern.xrnhabit.app.hardcore.HardcoreGuard
import com.xern.xrnhabit.app.hardcore.createHardcoreGuard
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.web.WebsiteMonitor
import com.xern.xrnhabit.app.web.createWebsiteMonitor
import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide singletons. Created once in [com.xern.xrnhabit.app.XrnhabitApp]. */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wall time with hand-set clock changes taken back out; see [ClockGuard]. */
    val clockGuard = ClockGuard(context, AndroidClock())
    val clock: Clock = clockGuard

    val engine = BlockEngine()
    private val worldFile = FilePersistPort(File(context.filesDir, "world.json"))
    val persist = ThrottledPersist(worldFile, clock)
    val telemetry = LocalTelemetry(File(context.filesDir, "telemetry.jsonl"))

    /** Set when world.json couldn't be read at start; the blocks are in this copy. */
    val unreadableWorldCopy: File? get() = worldFile.unreadableCopy

    /** The single source of truth for [World]. UI observes it, the service drives it. */
    val store = WorldStore(engine, persist, telemetry, clock, appScope)

    /** Anti-bypass (brainstorm §4.5) — real in the `sideload` flavor, a no-op in `play`. */
    val hardcoreGuard: HardcoreGuard = createHardcoreGuard(context)

    val websites: WebsiteMonitor = createWebsiteMonitor(context)

    val siteUsage = SiteUsageLog(File(context.filesDir, "site_usage.json"), clock)

    /** Habit ticks from xrnloops, our Loop fork (`../xrnloops`), for checklist items linked to it (I23). */
    val loop = LoopHabits(context, store, appScope)
}
