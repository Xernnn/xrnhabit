package com.xern.xrnhabit.domain

import java.time.Instant

/**
 * The state machine from `docs/domain-contract.md` §2. Pure and deterministic: [reduce] takes
 * the current [World], one [BlockEvent] and a [Clock], and returns an [Outcome]. It performs
 * no I/O and never mutates its inputs.
 *
 * Every invariant I1..I24 in the contract has a matching test in
 * `domain/src/test/.../InvariantTests.kt` (`i01_*` .. `i24_*`).
 */
class BlockEngine {

    fun reduce(world: World, event: BlockEvent, clock: Clock): Outcome {
        val effects = mutableListOf<Effect>()

        var w = when (event) {
            is BlockEvent.BootOrColdStart -> rebuild(world, clock, effects)
            is BlockEvent.ForegroundChanged -> onForegroundChanged(world, event, clock, effects)
            is BlockEvent.Tick -> accrueAndCheckTimers(world, clock, effects)
            is BlockEvent.ToggleChecklistItem -> onToggleItem(world, event, clock, effects)
            is BlockEvent.Redeem -> onRedeem(world, event.blockId, clock, effects)
            is BlockEvent.UserResetEarned -> onUserReset(world, event.blockId, clock)
            is BlockEvent.TimerControl -> onTimerControl(world, event, clock)
            is BlockEvent.StartLockdown -> world.withLockdownStarted(event.blockId, clock) // I21
            is BlockEvent.ExternalHabitsSynced -> onExternalHabitsSynced(world, event, clock, effects)
        }

        // Housekeeping sweep after every event: day rollover, credit expiry (I1), tamper (I3),
        // unlock time-up + 30s warning (G3), lockdown end (I21). Keeping this out of the
        // per-event handlers means a plain Tick and a Redeem both re-evaluate expiry the same way.
        w = sweep(w, clock, effects)

        val desired = shieldPlan(w, clock)
        val plan = if (desired != world.appliedShield || event is BlockEvent.BootOrColdStart) desired else null
        return Outcome(w.copy(appliedShield = desired), plan, effects)
    }

    // ---- shield projection -------------------------------------------------------------

    fun shieldPlan(world: World, clock: Clock): ShieldPlan =
        ShieldPlan(blockedPackages(world, clock), blockedSites(world, clock))

    fun blockedPackages(world: World, clock: Clock): Set<String> {
        val allTargets = world.blocks.flatMapTo(mutableSetOf()) { it.targetPackages }
        return allTargets.filterTo(mutableSetOf()) { pkg ->
            targetBlocked(world, world.blocks.filter { pkg in it.targetPackages }, clock)
        }
    }

    /** I18: target domains currently blocked, evaluated per configured domain like packages. */
    fun blockedSites(world: World, clock: Clock): Set<String> {
        val allTargets = world.blocks.flatMapTo(mutableSetOf()) { it.targetSites }
        return allTargets.filterTo(mutableSetOf()) { site ->
            targetBlocked(world, world.blocks.filter { site in it.targetSites }, clock)
        }
    }

    /**
     * I22: any engaged ALLOWANCE / SCHEDULE / LOCKDOWN block wins outright; otherwise the HABIT
     * rule (I6) — free iff *any* covering HABIT block is UNLOCKED.
     */
    private fun targetBlocked(world: World, covering: List<Block>, clock: Clock): Boolean {
        if (covering.any { it.mode != BlockMode.HABIT && isBlocking(world, it, clock) }) return true
        val habit = covering.filter { it.mode == BlockMode.HABIT }
        if (habit.any { it.state == BlockState.UNLOCKED }) return false
        return habit.any { it.enabled && scheduleActive(it, clock) }
    }

    fun isAppUnlocked(world: World, pkg: String): Boolean =
        world.blocks.any { pkg in it.targetPackages && it.state == BlockState.UNLOCKED }

    /** I5: the shield is on for a block in every state except UNLOCKED. */
    fun shieldOn(world: World, blockId: String, clock: Clock): Boolean {
        val b = world.block(blockId) ?: return false
        return isBlocking(world, b, clock)
    }

    /** Whether this one block, on its own, is blocking its targets right now (contract §2.2). */
    fun isBlocking(world: World, block: Block, clock: Clock): Boolean {
        if (!block.enabled) return false
        return when (block.mode) {
            BlockMode.HABIT -> block.state != BlockState.UNLOCKED && scheduleActive(block, clock)
            BlockMode.ALLOWANCE ->
                scheduleActive(block, clock) && allowanceUsedMs(world, block, clock) >= block.dailyAllowanceMs
            BlockMode.SCHEDULE -> scheduleActive(block, clock)
            BlockMode.LOCKDOWN -> lockdownRemainingMs(world, block.id, clock) > 0
        }
    }

    /** I19: today's used allowance; a progress window from an earlier day counts as zero. */
    fun allowanceUsedMs(world: World, block: Block, clock: Clock): Long {
        val p = world.progress[block.id] ?: return 0
        return if (dayRolled(p, clock)) 0 else p.accumulatedMs
    }

    /** I21: monotonic countdown, so moving the wall clock can't shorten it. */
    fun lockdownRemainingMs(world: World, blockId: String, clock: Clock): Long {
        val l = world.lockdowns[blockId] ?: return 0
        return (l.durationMs - (clock.nowMonotonicMs() - l.startedAtMonotonic)).coerceAtLeast(0)
    }

    /** I18: in a target app, or on a target site (or a subdomain of one). */
    private fun inTargets(block: Block, pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in block.targetPackages) ||
            (site != null && block.targetSites.any { Sites.matches(site, it) })

    // ---- trigger evaluation ----------------------------------------------------------

    /** I11 / I14 / I15. */
    fun triggerMet(world: World, block: Block): Boolean {
        val p = world.progress[block.id]
        return when (block.trigger.type) {
            TriggerType.TIMER -> timerMet(p, block)
            TriggerType.COUNT -> (p?.count ?: 0) >= block.trigger.requiredAmount
            TriggerType.CHECKLIST -> checklistThresholdMet(world, block)
            TriggerType.COMBINED -> {
                val timer = timerMet(p, block)
                val checklist = checklistAllDone(world, block)
                when (block.trigger.combineMode) { // I15
                    CombineMode.AND -> timer && checklist
                    CombineMode.OR -> timer || checklist
                }
            }
        }
    }

    private fun timerMet(progress: HabitProgress?, block: Block): Boolean =
        (progress?.accumulatedMs ?: 0) >= block.trigger.requiredAmount

    private fun checklistThresholdMet(world: World, block: Block): Boolean {
        val items = world.items(block.id)
        if (items.isEmpty()) return false // I14
        val need =
            if (block.trigger.requiredAmount == Defaults.ALL_ITEMS) items.size.toLong()
            else block.trigger.requiredAmount
        return items.count { it.done } >= need
    }

    /** COMBINED's checklist half always means "every item" — no partial threshold (I15). */
    private fun checklistAllDone(world: World, block: Block): Boolean {
        val items = world.items(block.id)
        return items.isNotEmpty() && items.all { it.done } // I14-equivalent: empty never satisfies
    }

    // ---- event handlers ------------------------------------------------------------

    private fun onForegroundChanged(
        world: World,
        event: BlockEvent.ForegroundChanged,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        // Accrue time for the *previous* foreground app/site up to now, then switch.
        var w = accrueTimers(world, clock)
        val pkg = event.pkg
        if (pkg != null && pkg in blockedPackages(w, clock)) {
            effects += Effect.BlockedImpression(pkg)
        }
        w = w.copy(foregroundPkg = pkg, foregroundSite = event.site)
        return checkTimerTriggers(w, clock, effects)
    }

    private fun accrueAndCheckTimers(world: World, clock: Clock, effects: MutableList<Effect>): World {
        val w = accrueTimers(world, clock)
        return checkTimerTriggers(w, clock, effects)
    }

    /**
     * Advances every live progress marker to now, crediting the time since the last marker to
     * whatever was foreground over that span.
     *
     * HABIT timers (I4): only while running and the counted app/site is foreground. "Counted" is
     * [Trigger.habitPackages] when set, else the block's own targets (I16, I18) — a set test,
     * so any of several targets counts (I17).
     *
     * ALLOWANCE (I19): while a target is foreground *and not shielded* — time spent looking at
     * the block screen isn't use — capped at the allowance.
     */
    private fun accrueTimers(world: World, clock: Clock): World {
        val nowMono = clock.nowMonotonicMs()
        val fgPkg = world.foregroundPkg
        val fgSite = world.foregroundSite

        var progress = world.progress
        for (b in world.blocks) {
            if (b.mode == BlockMode.ALLOWANCE && b.id !in progress) {
                progress = progress + (b.id to freshProgress(b.id, clock))
            }
        }
        if (progress.isEmpty()) return world

        val fgShielded = shieldPlan(world, clock).covers(fgPkg, fgSite)
        progress = progress.mapValues { (blockId, p) ->
            val block = world.block(blockId) ?: return@mapValues p
            val delta = (nowMono - p.lastUpdated).coerceAtLeast(0)
            val credit = when (block.mode) {
                BlockMode.ALLOWANCE -> !fgShielded && inTargets(block, fgPkg, fgSite)
                BlockMode.HABIT -> {
                    val hasTimer = block.trigger.type == TriggerType.TIMER || block.trigger.type == TriggerType.COMBINED
                    val counted = if (block.trigger.habitPackages.isNotEmpty()) {
                        fgPkg != null && fgPkg in block.trigger.habitPackages // I16
                    } else {
                        inTargets(block, fgPkg, fgSite)
                    }
                    hasTimer && p.timerRunning && block.state == BlockState.ARMED && counted
                }
                BlockMode.SCHEDULE, BlockMode.LOCKDOWN -> false
            }
            if (!credit) return@mapValues p.copy(lastUpdated = nowMono)
            val accumulated = p.accumulatedMs + delta
            p.copy(
                accumulatedMs = if (block.mode == BlockMode.ALLOWANCE) accumulated.coerceAtMost(block.dailyAllowanceMs) else accumulated,
                lastUpdated = nowMono,
            )
        }
        return world.copy(progress = progress)
    }

    private fun checkTimerTriggers(world: World, clock: Clock, effects: MutableList<Effect>): World {
        var w = world
        w.blocks
            .filter {
                it.mode == BlockMode.HABIT && it.state == BlockState.ARMED &&
                    (it.trigger.type == TriggerType.TIMER || it.trigger.type == TriggerType.COMBINED)
            }
            .forEach { b -> if (triggerMet(w, b)) w = armedToEarned(w, b, clock, effects) }
        return w
    }

    private fun onToggleItem(
        world: World,
        event: BlockEvent.ToggleChecklistItem,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val owner = world.checklist.entries.firstOrNull { (_, items) ->
            items.any { it.id == event.itemId }
        }?.key ?: return world
        val block = world.block(owner) ?: return world
        if (block.mode != BlockMode.HABIT || block.state != BlockState.ARMED) return world // I12, §2.2
        if (world.items(owner).any { it.id == event.itemId && it.externalHabitId != null }) return world // I23

        val updated = world.items(owner).map { item ->
            if (item.id != event.itemId) item
            else item.copy(
                done = event.done,
                doneAtMonotonic = if (event.done) clock.nowMonotonicMs() else null,
            )
        }
        var w = world.copy(checklist = world.checklist + (owner to updated))
        if (hasChecklist(block) && triggerMet(w, block)) {
            w = armedToEarned(w, block, clock, effects)
        }
        return w
    }

    /**
     * I23: linked items mirror the external tracker — ticked iff that habit is done today and
     * its tick hasn't already paid for a credit today (I24). Only ARMED HABIT blocks listen, as
     * with a manual toggle (I12). A snapshot for any other day raced midnight and is dropped.
     */
    private fun onExternalHabitsSynced(
        world: World,
        event: BlockEvent.ExternalHabitsSynced,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val today = clock.epochDay()
        if (event.epochDay != today) return world
        var w = world
        for (block in world.blocks) {
            if (block.mode != BlockMode.HABIT || block.state != BlockState.ARMED) continue
            val items = w.items(block.id)
            if (items.none { it.externalHabitId != null }) continue
            val updated = items.map { item ->
                val habitId = item.externalHabitId ?: return@map item
                val done = habitId in event.doneHabitIds && item.spentEpochDay != today
                when {
                    done == item.done -> item
                    done -> item.copy(done = true, doneAtMonotonic = clock.nowMonotonicMs())
                    else -> item.copy(done = false, doneAtMonotonic = null)
                }
            }
            w = w.copy(checklist = w.checklist + (block.id to updated))
            if (hasChecklist(block) && triggerMet(w, block)) {
                w = armedToEarned(w, block, clock, effects)
            }
        }
        return w
    }

    private fun hasChecklist(block: Block): Boolean =
        block.trigger.type == TriggerType.CHECKLIST || block.trigger.type == TriggerType.COMBINED

    private fun onRedeem(
        world: World,
        blockId: String,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val block = world.block(blockId) ?: return world
        val credit = world.credits[blockId]
        if (block.state != BlockState.EARNED || credit == null) {
            effects += Effect.RedeemRejected(blockId, RejectReason.NOT_EARNED)
            return world
        }
        val epochDay = clock.epochDay()
        val dailyUsed = if (epochDay != world.dailyUsageEpochDay) 0L else world.dailyUnlockUsedMs
        if (dailyUsed + credit.amountMs > world.settings.dailyCapMs) { // I9
            effects += Effect.RedeemRejected(blockId, RejectReason.CAP_REACHED)
            return world
        }
        val session = UnlockSession(
            blockId = blockId,
            startedAtMonotonic = clock.nowMonotonicMs(),
            startedAtWall = clock.nowWallMs(),
            durationMs = credit.amountMs,
        )
        return world.copy(
            blocks = world.blocks.replace(blockId) { it.copy(state = BlockState.UNLOCKED) },
            credits = world.credits - blockId,
            sessions = world.sessions + (blockId to session),
            dailyUnlockUsedMs = dailyUsed,
            dailyUsageEpochDay = epochDay,
        )
    }

    private fun onUserReset(world: World, blockId: String, clock: Clock): World {
        val block = world.block(blockId) ?: return world
        if (block.state != BlockState.EARNED) return world
        return returnToArmed(world, block, clock)
    }

    private fun onTimerControl(world: World, event: BlockEvent.TimerControl, clock: Clock): World {
        if (world.block(event.blockId)?.let { it.mode != BlockMode.HABIT } == true) return world
        val existing = world.progress[event.blockId]
            ?: freshProgress(event.blockId, clock)
        return world.copy(
            progress = world.progress + (event.blockId to existing.copy(
                timerRunning = event.running,
                lastUpdated = clock.nowMonotonicMs(),
            )),
        )
    }

    // ---- transitions ------------------------------------------------------------------

    private fun armedToEarned(
        world: World,
        block: Block,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        val credit = EarnedCredit(
            blockId = block.id,
            earnedAtMonotonic = nowMono,
            earnedAtWall = nowWall,
            expiresAtWall = nowWall + block.reward.creditTtlMs, // I1 (wall clock, survives reboot)
            amountMs = block.reward.unlockDurationMs,
        )
        effects += Effect.HabitCompleted(block.id)
        // I24: each external tick that is done now has paid for this credit.
        val items = world.items(block.id)
        val checklist = if (items.none { it.externalHabitId != null && it.done }) {
            world.checklist
        } else {
            val today = clock.epochDay()
            world.checklist + (block.id to items.map { item ->
                if (item.externalHabitId != null && item.done) item.copy(spentEpochDay = today) else item
            })
        }
        return world.copy(
            blocks = world.blocks.replace(block.id) { it.copy(state = BlockState.EARNED) },
            credits = world.credits + (block.id to credit),
            checklist = checklist,
        )
    }

    /** Every path back to ARMED resets the habit window and the checklist (I10). */
    private fun returnToArmed(world: World, block: Block, clock: Clock): World =
        world.copy(
            blocks = world.blocks.replace(block.id) { it.copy(state = BlockState.ARMED) },
            credits = world.credits - block.id,
            sessions = world.sessions - block.id,
            progress = world.progress + (block.id to freshProgress(block.id, clock)),
            checklist = world.checklist + (block.id to resetItems(world.items(block.id))),
        )

    // ---- housekeeping sweep --------------------------------------------------------

    private fun sweep(world: World, clock: Clock, effects: MutableList<Effect>): World {
        var w = world
        val today = clock.epochDay()
        if (today != w.dailyUsageEpochDay) {
            w = w.copy(dailyUnlockUsedMs = 0, dailyUsageEpochDay = today)
        }

        // I21: drop lockdowns that have run out (or whose block is gone / no longer LOCKDOWN).
        val liveLockdowns = w.lockdowns.filter { (id, _) ->
            w.block(id)?.mode == BlockMode.LOCKDOWN && lockdownRemainingMs(w, id, clock) > 0
        }
        if (liveLockdowns.size != w.lockdowns.size) w = w.copy(lockdowns = liveLockdowns)

        for (block in w.blocks) {
            w = when (block.state) {
                BlockState.ARMED -> {
                    if (dayRolled(w.progress[block.id], clock)) returnToArmed(w, block, clock) else w
                }

                BlockState.EARNED -> {
                    val credit = w.credits[block.id]
                    val expiredByWall = credit != null && clock.nowWallMs() >= credit.expiresAtWall
                    val clockWoundBack = credit != null && run {
                        val monoElapsed = clock.nowMonotonicMs() - credit.earnedAtMonotonic
                        val wallElapsed = clock.nowWallMs() - credit.earnedAtWall
                        wallElapsed < monoElapsed - w.settings.skewToleranceMs
                    }
                    if (expiredByWall || clockWoundBack || dayRolled(w.progress[block.id], clock)) {
                        returnToArmed(w, block, clock)
                    } else {
                        w
                    }
                }

                BlockState.UNLOCKED -> {
                    val s = w.sessions[block.id]
                    if (s == null) {
                        returnToArmed(w, block, clock)
                    } else {
                        val monoElapsed = clock.nowMonotonicMs() - s.startedAtMonotonic
                        val wallElapsed = clock.nowWallMs() - s.startedAtWall
                        val tamper = wallElapsed < monoElapsed - w.settings.skewToleranceMs // I2/I3
                        val timeUp = monoElapsed >= s.durationMs
                        if (tamper || timeUp) {
                            val consumed = monoElapsed.coerceIn(0, s.durationMs)
                            effects += Effect.UnlockConsumed(block.id, consumed)
                            if (tamper) effects += Effect.TamperDetected(block.id)
                            val reset = returnToArmed(w, block, clock)
                            reset.copy(
                                dailyUnlockUsedMs = reset.dailyUnlockUsedMs + consumed,
                                dailyUsageEpochDay = today,
                            )
                        } else {
                            val remaining = s.durationMs - monoElapsed
                            if (remaining in 1..w.settings.unlockWarningMs) {
                                effects += Effect.UnlockEndingSoon(block.id, remaining)
                            }
                            w
                        }
                    }
                }
            }
        }
        return w
    }

    // ---- rebuild (I8) ---------------------------------------------------------------

    private fun rebuild(world: World, clock: Clock, effects: MutableList<Effect>): World {
        // Monotonic time resets across reboot, so rebase live monotonic references from the
        // wall-clock delta before letting the normal sweep logic take over.
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        val credits = world.credits.mapValues { (_, c) ->
            val wallElapsed = (nowWall - c.earnedAtWall).coerceAtLeast(0)
            c.copy(earnedAtMonotonic = nowMono - wallElapsed)
        }
        val sessions = world.sessions.mapValues { (_, s) ->
            val wallElapsed = (nowWall - s.startedAtWall).coerceAtLeast(0)
            s.copy(startedAtMonotonic = nowMono - wallElapsed)
        }
        val lockdowns = world.lockdowns.mapValues { (_, l) ->
            val wallElapsed = (nowWall - l.startedAtWall).coerceAtLeast(0)
            l.copy(startedAtMonotonic = nowMono - wallElapsed)
        }
        // Timer accrual markers are monotonic and meaningless after a reboot; re-anchor them.
        val progress = world.progress.mapValues { (_, p) -> p.copy(lastUpdated = nowMono) }
        return sweep(
            world.copy(credits = credits, sessions = sessions, lockdowns = lockdowns, progress = progress),
            clock,
            effects,
        )
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun freshProgress(blockId: String, clock: Clock) = HabitProgress(
        blockId = blockId,
        accumulatedMs = 0,
        count = 0,
        windowStart = clock.startOfLocalDayMs(),
        lastUpdated = clock.nowMonotonicMs(),
        timerRunning = false,
    )

    private fun resetItems(items: List<ChecklistItem>): List<ChecklistItem> =
        items.map { it.copy(done = false, doneAtMonotonic = null) }

    private fun dayRolled(progress: HabitProgress?, clock: Clock): Boolean {
        progress ?: return false
        val started = Instant.ofEpochMilli(progress.windowStart).atZone(clock.zone()).toLocalDate()
        return started != clock.localDate()
    }

    private fun scheduleActive(block: Block, clock: Clock): Boolean {
        val s = block.schedule
        val zdt = Instant.ofEpochMilli(clock.nowWallMs()).atZone(clock.zone())
        if (zdt.dayOfWeek.value !in s.activeDays) return false
        val w = s.activeWindow ?: return true
        val minute = zdt.hour * 60 + zdt.minute
        return if (w.startMinuteOfDay <= w.endMinuteOfDay) {
            minute in w.startMinuteOfDay until w.endMinuteOfDay
        } else { // window wraps midnight
            minute >= w.startMinuteOfDay || minute < w.endMinuteOfDay
        }
    }
}

private inline fun List<Block>.replace(id: String, transform: (Block) -> Block): List<Block> =
    map { if (it.id == id) transform(it) else it }
