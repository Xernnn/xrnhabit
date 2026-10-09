package com.xern.xrnhabit.app.data

import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.ShieldPlan
import com.xern.xrnhabit.domain.ShieldPort
import com.xern.xrnhabit.domain.Telemetry
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.domain.applyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes every change to the [World] through one mutex so the engine's ordering guarantees
 * (persist-before-shield, I7) hold no matter who calls — the UI or the service.
 */
class WorldStore(
    val engine: BlockEngine,
    private val persist: PersistPort,
    private val telemetry: Telemetry,
    val clock: Clock,
    private val scope: CoroutineScope,
) {
    /** Set by [com.xern.xrnhabit.app.block.BlockerForegroundService] once it is running. */
    @Volatile
    var shield: ShieldPort = NoopShield

    private val _world = MutableStateFlow(persist.loadWorld())
    val world: StateFlow<World> = _world.asStateFlow()

    private val mutex = Mutex()

    fun dispatch(event: BlockEvent) {
        scope.launch {
            mutex.withLock {
                val outcome = engine.reduce(_world.value, event, clock)
                applyOutcome(outcome, persist, shield, telemetry, clock.nowWallMs())
                _world.value = outcome.world
            }
        }
    }

    /** Structural edits (create a block, add a to-do item) that are not [BlockEvent]s. */
    fun edit(transform: (World) -> World) {
        scope.launch {
            mutex.withLock {
                val edited = transform(_world.value)
                val plan = engine.shieldPlan(edited, clock)
                val next = edited.copy(appliedShield = plan)
                persist.save(next)
                shield.apply(plan)
                _world.value = next
            }
        }
    }
}

object NoopShield : ShieldPort {
    override fun apply(plan: ShieldPlan) = Unit
}
