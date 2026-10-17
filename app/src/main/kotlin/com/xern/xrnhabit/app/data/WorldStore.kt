package com.xern.xrnhabit.app.data

import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.Effect
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.ShieldPlan
import com.xern.xrnhabit.domain.ShieldPort
import com.xern.xrnhabit.domain.Telemetry
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.domain.applyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 32)

    /**
     * The engine's side effects, after [applyOutcome] has handled the telemetry ones. The service
     * turns the rest (unlock ending soon, tamper, a refused redeem) into notifications.
     */
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: BlockEvent) {
        scope.launch { dispatchAndGet(event) }
    }

    /** [dispatch], waiting for it: the world right after this event, before anything else ran. */
    suspend fun dispatchAndGet(event: BlockEvent): World = mutex.withLock {
        val outcome = engine.reduce(_world.value, event, clock)
        applyOutcome(outcome, persist, shield, telemetry, clock.nowWallMs())
        _world.value = outcome.world
        outcome.effects.forEach { _effects.tryEmit(it) }
        outcome.world
    }

    /**
     * Structural edits (create a block, add a to-do item) that are not [BlockEvent]s. A block
     * whose settings change is stamped with the time, so the later edit wins when two devices
     * change it at once (contract §2.4).
     */
    fun edit(transform: (World) -> World) {
        scope.launch {
            mutex.withLock {
                val edited = stampEdits(_world.value, transform(_world.value))
                val plan = engine.shieldPlan(edited, clock)
                val next = edited.copy(appliedShield = plan)
                persist.save(next)
                shield.apply(plan)
                _world.value = next
            }
        }
    }
}

private fun WorldStore.stampEdits(before: World, after: World): World {
    val now = clock.nowWallMs()
    val old = before.blocks.associateBy { it.id }
    val blocks = after.blocks.map { b ->
        val was = old[b.id]
        val changed = was == null || was.copy(state = b.state, editedAtWall = b.editedAtWall) != b
        if (changed) b.copy(editedAtWall = now) else b
    }
    return after.copy(blocks = blocks)
}

object NoopShield : ShieldPort {
    override fun apply(plan: ShieldPlan) = Unit
}
