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
     * A full snapshot from the external habit tracker: the habits done on local day [epochDay].
     * Drives linked checklist items (I23, I24). Never sent for a failed read.
     */
    data class ExternalHabitsSynced(val epochDay: Long, val doneHabitIds: Set<String>) : BlockEvent

    /** Process cold start or BOOT_COMPLETED — rebuild state and re-apply the shield (I8). */
    data object BootOrColdStart : BlockEvent
}
