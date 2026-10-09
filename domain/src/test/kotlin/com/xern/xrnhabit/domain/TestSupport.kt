package com.xern.xrnhabit.domain

import java.time.Instant
import java.time.ZoneId

/** A fixed instant well away from any DST edge, used as the base wall clock in tests. */
val BASE_WALL: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

class FakeClock(
    private var mono: Long = 0L,
    private var wall: Long = BASE_WALL,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock {
    override fun nowMonotonicMs(): Long = mono
    override fun nowWallMs(): Long = wall
    override fun zone(): ZoneId = zoneId

    /** Advance real time: both clocks move together. */
    fun advance(ms: Long) {
        mono += ms
        wall += ms
    }

    fun advanceWallOnly(ms: Long) {
        wall += ms
    }

    fun advanceMonoOnly(ms: Long) {
        mono += ms
    }

    fun setMono(value: Long) {
        mono = value
    }
}

class InMemoryPersist(initial: World) : PersistPort {
    var world: World = initial
        private set

    override fun loadWorld(): World = world
    override fun save(world: World) {
        this.world = world
    }
}

/** Persist + shield that append to a shared log so ordering (I7) can be asserted. */
class RecordingPersist(initial: World, private val log: MutableList<String>) : PersistPort {
    var world: World = initial
        private set

    override fun loadWorld(): World = world
    override fun save(world: World) {
        this.world = world
        log += "persist"
    }
}

class RecordingShield(private val log: MutableList<String>) : ShieldPort {
    var last: ShieldPlan? = null
        private set

    override fun apply(plan: ShieldPlan) {
        last = plan
        log += "shield"
    }
}

object NoopTelemetry : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = Unit
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = Unit
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long) = Unit
}

// ---- builders -------------------------------------------------------------------------

fun timerBlock(
    id: String = "t1",
    target: String = "com.target",
    requiredMs: Long = 30L * 60_000,
    habitPackages: Set<String> = emptySet(),
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Timer $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.TIMER, requiredMs, TriggerUnit.MS, habitPackages = habitPackages),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

fun checklistBlock(
    id: String = "c1",
    target: String = "com.target",
    required: Long = Defaults.ALL_ITEMS,
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Checklist $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.CHECKLIST, required, TriggerUnit.ITEMS),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

fun combinedBlock(
    id: String = "x1",
    target: String = "com.target",
    requiredMs: Long = 30L * 60_000,
    combineMode: CombineMode = CombineMode.AND,
    habitPackages: Set<String> = emptySet(),
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Combined $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.COMBINED, requiredMs, TriggerUnit.MS, combineMode, habitPackages),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

/** A non-HABIT block (contract §2.2); the trigger is required by the model but never read. */
fun modeBlock(
    id: String,
    mode: BlockMode,
    targetPackages: Set<String> = setOf("com.target"),
    targetSites: Set<String> = emptySet(),
    dailyAllowanceMs: Long = 0,
    lockdownMs: Long = 0,
    schedule: Schedule = Schedule.ALWAYS,
) = Block(
    id = id,
    name = "$mode $id",
    targetPackages = targetPackages,
    targetSites = targetSites,
    trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
    schedule = schedule,
    mode = mode,
    dailyAllowanceMs = dailyAllowanceMs,
    lockdownMs = lockdownMs,
)

fun everyDay(startMinute: Int, endMinute: Int) = Schedule((1..7).toSet(), TimeWindow(startMinute, endMinute))

fun items(blockId: String, count: Int, done: Int = 0): List<ChecklistItem> =
    (0 until count).map { i ->
        ChecklistItem(id = "$blockId-i$i", blockId = blockId, text = "todo $i", order = i, done = i < done)
    }

/** A checklist item ticked only by the external habit tracker (I23). Id is `"$blockId-$habitId"`. */
fun linkedItem(blockId: String, habitId: String, order: Int = 0) =
    ChecklistItem(id = "$blockId-$habitId", blockId = blockId, text = habitId, order = order, externalHabitId = habitId)

fun buildWorld(
    blocks: List<Block>,
    checklist: Map<String, List<ChecklistItem>> = emptyMap(),
    clock: Clock,
    foregroundPkg: String? = null,
    settings: Settings = Settings(),
): World {
    val progress = blocks.associate { b ->
        b.id to HabitProgress(
            blockId = b.id,
            windowStart = clock.startOfLocalDayMs(),
            lastUpdated = clock.nowMonotonicMs(),
        )
    }
    val world = World(
        blocks = blocks,
        progress = progress,
        checklist = checklist,
        dailyUsageEpochDay = clock.epochDay(),
        foregroundPkg = foregroundPkg,
        settings = settings,
    )
    // As if the shield had already been told about this starting world.
    return world.copy(appliedShield = BlockEngine().shieldPlan(world, clock))
}
