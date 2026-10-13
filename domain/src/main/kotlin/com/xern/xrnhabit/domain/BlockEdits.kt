package com.xern.xrnhabit.domain

/**
 * Which edits make a block easier to get past (contract §2.3). The app asks for the guard —
 * a wait, or the hardcore password — before applying one; edits that only make a block
 * stricter go through at once.
 */
object BlockEdits {

    /** Whether replacing [old] with [new] loosens the block in any way. */
    fun loosens(old: Block, new: Block): Boolean {
        val oldTimer = old.trigger.type == TriggerType.TIMER || old.trigger.type == TriggerType.COMBINED
        return !new.targetPackages.containsAll(old.targetPackages) ||
            !new.targetSites.containsAll(old.targetSites) ||
            (old.enabled && !new.enabled) ||
            (old.enforcementTier == EnforcementTier.HARDCORE && new.enforcementTier == EnforcementTier.SOFT) ||
            new.mode != old.mode ||
            new.trigger.type != old.trigger.type ||
            (oldTimer && new.trigger.requiredAmount < old.trigger.requiredAmount) ||
            (old.trigger.combineMode == CombineMode.AND && new.trigger.combineMode == CombineMode.OR) ||
            new.trigger.habitPackages != old.trigger.habitPackages ||
            new.reward.unlockDurationMs > old.reward.unlockDurationMs ||
            new.reward.creditTtlMs > old.reward.creditTtlMs ||
            new.dailyAllowanceMs > old.dailyAllowanceMs ||
            new.lockdownMs < old.lockdownMs ||
            schedulesLoosen(old.schedule, new.schedule)
    }

    /** Fewer days, or any change to the window, can free a moment that used to be blocked. */
    private fun schedulesLoosen(old: Schedule, new: Schedule): Boolean =
        !new.activeDays.containsAll(old.activeDays) || new.activeWindow != old.activeWindow

    /**
     * Whether a block's own settings may change freely right now. Only a lockdown that isn't
     * running is inert; everything else is either blocking or about to.
     */
    fun guardNeeded(engine: BlockEngine, world: World, block: Block, clock: Clock): Boolean =
        !(block.mode == BlockMode.LOCKDOWN && engine.lockdownRemainingMs(world, block.id, clock) == 0L)
}
