package com.xern.xrnhabit.domain

/** Inputs to [BlockEngine.reduce]. See `docs/domain-contract.md` §5. */
sealed interface BlockEvent {

    /**
     * Foreground app changed (from `ForegroundAppDetector`). `pkg == null` means nothing is in
     * use (home screen, screen off). [site] is the host a foreground browser is showing (I18).
     */
    data class ForegroundChanged(val pkg: String?, val site: String? = null) : BlockEvent

    /** Periodic wake (~1s) while any timer, session, or non-HABIT block is live. */
    data object Tick : BlockEvent

    /** Start a [BlockMode.LOCKDOWN] block's lockdown; no-op while one is running (I21). */
    data class StartLockdown(val blockId: String) : BlockEvent

    /** User ticked/unticked a to-do item on the block detail or the overlay. No-op unless ARMED (I12). */
    data class ToggleChecklistItem(val itemId: String, val done: Boolean) : BlockEvent

    /** User pressed "Redeem" on an EARNED block. */
    data class Redeem(val blockId: String) : BlockEvent

    /** User discarded an EARNED credit without spending it. */
    data class UserResetEarned(val blockId: String) : BlockEvent

    /** User pressed "Start" / "Pause" on a TIMER habit. */
    data class TimerControl(val blockId: String, val running: Boolean) : BlockEvent

    /**
     * A full snapshot from the external habit tracker for local day [epochDay] and the day before
     * it ([LinkedDay.YESTERDAY] items): the habits ticked that day, and the habits whose goal is
     * met that day ([ChecklistItem.countsGoal] items). Drives linked checklist items (I23, I24).
     * Never sent unless both days were read.
     */
    data class ExternalHabitsSynced(
        val epochDay: Long,
        val doneHabitIds: Set<String>,
        val doneYesterdayHabitIds: Set<String> = emptySet(),
        val goalMetHabitIds: Set<String> = emptySet(),
        val goalMetYesterdayHabitIds: Set<String> = emptySet(),
    ) : BlockEvent

    /** Starts a break from SOFT blocks, at most once per local day (I25). */
    data class StartPause(val durationMs: Long = Defaults.PAUSE_MS) : BlockEvent

    /** Ends the running break early (I25). */
    data object EndPause : BlockEvent

    /** Process cold start or BOOT_COMPLETED — rebuild state and re-apply the shield (I8). */
    data object BootOrColdStart : BlockEvent
}
