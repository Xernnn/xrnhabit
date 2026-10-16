package com.xern.xrnhabit.domain

import kotlinx.serialization.Serializable

/**
 * Pure domain model for xrnhabit. No Android imports here — everything in `:domain`
 * must be unit-testable on a plain JVM. See `docs/domain-contract.md`.
 *
 * Every persisted type is [Serializable] so `:app` can store the whole [World] as one atomic
 * JSON document (see `FilePersistPort`). Days of week are ISO-8601 ints (1 = Monday .. 7 =
 * Sunday) to keep the model free of `java.time` in its serialized shape.
 */

enum class BlockState { ARMED, EARNED, UNLOCKED }

enum class TriggerType { TIMER, CHECKLIST, COMBINED }

enum class TriggerUnit { MS, ITEMS }

enum class EnforcementTier { SOFT, HARDCORE }

/** How a [TriggerType.COMBINED] trigger's two conditions resolve to `triggerMet` (I15). */
enum class CombineMode { AND, OR }

/**
 * How a block decides it is blocking (contract §2.2). Only [HABIT] runs the ARMED → EARNED →
 * UNLOCKED machine; the others have no unlock path and stay ARMED.
 */
enum class BlockMode { HABIT, ALLOWANCE, SCHEDULE, LOCKDOWN }

/**
 * Which day's external tick a linked [ChecklistItem] counts (I23). [YESTERDAY] is for habits
 * ticked at the end of the day: last night's tick pays for today's unlock.
 */
enum class LinkedDay { TODAY, YESTERDAY }

/** Contract defaults (§3). Settings can override per install; these are the fallback. */
object Defaults {
    const val CREDIT_TTL_MS = 2L * 60 * 60 * 1000        // I1
    const val DAILY_CAP_MS = 60L * 60 * 1000             // I9
    const val SKEW_TOLERANCE_MS = 5_000L                 // I3
    const val UNLOCK_WARNING_MS = 30_000L                // G3
    const val DEFAULT_UNLOCK_MS = 5L * 60 * 1000
    const val PAUSE_MS = 15L * 60 * 1000                 // I25
    const val ALL_ITEMS = -1L                            // trigger.requiredAmount sentinel (I11)
}

@Serializable
data class Trigger(
    val type: TriggerType,
    /**
     * For CHECKLIST: number of items to complete, or [Defaults.ALL_ITEMS] for "all". For TIMER
     * and COMBINED: milliseconds required. COMBINED's checklist half always requires *all*
     * items (see I15) — there is no separate partial-checklist threshold for it.
     */
    val requiredAmount: Long,
    val unit: TriggerUnit,
    /** Only meaningful when [type] is COMBINED (I15). */
    val combineMode: CombineMode = CombineMode.AND,
    /**
     * Packages whose foreground time counts toward the timer half of a TIMER or COMBINED
     * trigger. Empty (the default) means "count time in the blocked [Block.targetPackages]
     * instead" — the original behavior. Non-empty lets the habit be done in a *different* app
     * than the one being blocked (I16).
     */
    val habitPackages: Set<String> = emptySet(),
)

@Serializable
data class Reward(
    val unlockDurationMs: Long = Defaults.DEFAULT_UNLOCK_MS,
    val creditTtlMs: Long = Defaults.CREDIT_TTL_MS,
)

@Serializable
data class TimeWindow(val startMinuteOfDay: Int, val endMinuteOfDay: Int)

@Serializable
data class Schedule(
    /** ISO-8601 day numbers, 1 = Monday .. 7 = Sunday. */
    val activeDays: Set<Int>,
    val activeWindow: TimeWindow? = null,
) {
    companion object {
        val ALWAYS = Schedule((1..7).toSet(), null)
    }
}

@Serializable
data class Block(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val targetPackages: Set<String> = emptySet(),
    val targetCategories: Set<String> = emptySet(),
    /** Normalized domains (see [Sites.normalize]); each also covers its subdomains (I18). */
    val targetSites: Set<String> = emptySet(),
    /**
     * Laptop apps, by the `.desktop` id KWin reports (e.g. `steam`). Targets like
     * [targetPackages] (I29); the phone never sees them in the foreground.
     */
    val targetDesktopApps: Set<String> = emptySet(),
    val trigger: Trigger,
    val reward: Reward = Reward(),
    val schedule: Schedule = Schedule.ALWAYS,
    val enforcementTier: EnforcementTier = EnforcementTier.SOFT,
    val state: BlockState = BlockState.ARMED,
    val mode: BlockMode = BlockMode.HABIT,
    /** [BlockMode.ALLOWANCE]: free foreground time per local day before the block engages (I19). */
    val dailyAllowanceMs: Long = 0,
    /** [BlockMode.LOCKDOWN]: length of one lockdown, reused when it is started again (I21). */
    val lockdownMs: Long = 0,
    /** Wall-clock ms of the last edit; the later edit wins when two devices change a block at once (§2.4). */
    val editedAtWall: Long = 0,
) {
    /** Everything this block blocks that can be "the foreground app": phone packages and laptop apps. */
    val targetApps: Set<String> get() = targetPackages + targetDesktopApps
}

/**
 * A short break from every SOFT block that isn't a lockdown (I25). Counted down on the monotonic
 * clock, like [Lockdown].
 */
@Serializable
data class Pause(
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
)

/** A running [BlockMode.LOCKDOWN]. Counted down on the monotonic clock, like [UnlockSession] (I21). */
@Serializable
data class Lockdown(
    val blockId: String,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
)

@Serializable
data class ChecklistItem(
    val id: String,
    val blockId: String,
    val text: String,
    val order: Int,
    val done: Boolean = false,
    val doneAtMonotonic: Long? = null,
    /**
     * Set on a *linked item*: the external tracker's habit id (Loop's `uuid`). Only
     * [BlockEvent.ExternalHabitsSynced] ticks it, never the user (I23).
     */
    val externalHabitId: String? = null,
    /** Linked items only: whether today's or yesterday's external tick counts (I23). */
    val linkedDay: LinkedDay = LinkedDay.TODAY,
    /**
     * Linked items only: count the day when the tracker says the habit's goal is met, not just
     * when it was ticked that day — for "3 times a week" habits, whose goal covers days without a
     * tick of their own (I23).
     */
    val countsGoal: Boolean = false,
    /** Local epoch day this linked item's tick last paid for an unlock; kept across resets (I24). */
    val spentEpochDay: Long? = null,
)

@Serializable
data class HabitProgress(
    val blockId: String,
    val accumulatedMs: Long = 0,
    /** Wall-clock ms of local start-of-day this progress window belongs to (I13). */
    val windowStart: Long,
    /** Monotonic ms marker of the last timer accrual (I4). */
    val lastUpdated: Long,
    val timerRunning: Boolean = false,
)

/**
 * TTL is enforced on the wall clock ([expiresAtWall]) so it survives reboot; the monotonic
 * stamp is a live cross-check against the user winding the clock backward (I1/I3).
 */
@Serializable
data class EarnedCredit(
    val blockId: String,
    val earnedAtMonotonic: Long,
    val earnedAtWall: Long,
    val expiresAtWall: Long,
    val amountMs: Long,
)

@Serializable
data class UnlockSession(
    val blockId: String,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
) {
    /** Names this session on every device, for the [World.unlockLog] (I28). */
    val key: String get() = "$blockId@$startedAtWall"
}

/** One device's ALLOWANCE use of a block on the local day starting at [dayStartWall] (I19, §2.4). */
@Serializable
data class AllowanceUse(val dayStartWall: Long, val ms: Long)

@Serializable
data class Settings(
    val dailyCapMs: Long = Defaults.DAILY_CAP_MS,
    val skewToleranceMs: Long = Defaults.SKEW_TOLERANCE_MS,
    val unlockWarningMs: Long = Defaults.UNLOCK_WARNING_MS,
)

/**
 * The whole persisted world. `:app` loads it on boot and saves it back after every [Outcome]
 * (save must complete before the shield is touched — I7).
 */
@Serializable
data class World(
    val blocks: List<Block> = emptyList(),
    val progress: Map<String, HabitProgress> = emptyMap(),
    val credits: Map<String, EarnedCredit> = emptyMap(),
    val sessions: Map<String, UnlockSession> = emptyMap(),
    val checklist: Map<String, List<ChecklistItem>> = emptyMap(),
    /** Unlock time used today before [unlockLog] existed; only old data has it (I9, I28). */
    val dailyUnlockUsedMs: Long = 0,
    /** Epoch-day number [dailyUnlockUsedMs] and [unlockLog] apply to. */
    val dailyUsageEpochDay: Long = 0,
    /** Consumed unlock time per ended session today, keyed by [UnlockSession.key] (I28). */
    val unlockLog: Map<String, Long> = emptyMap(),
    val foregroundPkg: String? = null,
    /** Host shown in [foregroundPkg] when that is a browser and its address bar could be read (I18). */
    val foregroundSite: String? = null,
    val lockdowns: Map<String, Lockdown> = emptyMap(),
    /** The running break, if any (I25). */
    val pause: Pause? = null,
    /** Local epoch day the last break was taken; one a day (I25). */
    val lastPauseEpochDay: Long? = null,
    val settings: Settings = Settings(),
    /** This install's id in the shared state (§2.4); empty until the app assigns one. */
    val deviceId: String = "",
    /** Other devices' ALLOWANCE use, block id -> device id -> use (I19, §2.4). */
    val otherAllowance: Map<String, Map<String, AllowanceUse>> = emptyMap(),
    /**
     * The last plan handed to the shield. A new plan is emitted when the projection differs
     * from this, not from the previous world's projection: schedules and lockdowns change the
     * projection by time alone, so "before" and "after" evaluated now would always agree.
     */
    val appliedShield: ShieldPlan = ShieldPlan(emptySet()),
) {
    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }
    fun items(blockId: String): List<ChecklistItem> = checklist[blockId].orEmpty()

    /** Starts (or restarts, once ended) a [BlockMode.LOCKDOWN] block's lockdown (I21). */
    fun withLockdownStarted(blockId: String, clock: Clock): World {
        val block = block(blockId) ?: return this
        if (block.mode != BlockMode.LOCKDOWN || block.lockdownMs <= 0) return this
        val running = lockdowns[blockId]?.let { clock.nowMonotonicMs() - it.startedAtMonotonic < it.durationMs } == true
        if (running) return this
        val lockdown = Lockdown(blockId, clock.nowMonotonicMs(), clock.nowWallMs(), block.lockdownMs)
        return copy(lockdowns = lockdowns + (blockId to lockdown))
    }
}
