package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.ChecklistItem
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.Defaults
import com.xern.xrnhabit.domain.LinkedDay
import com.xern.xrnhabit.domain.Reward
import com.xern.xrnhabit.domain.Schedule
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.TimeWindow
import com.xern.xrnhabit.domain.Trigger
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.TriggerUnit
import com.xern.xrnhabit.domain.World
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The local page's block form: the same rules as the phone's create screen (`CreateBlockScreen`),
 * so a block made on the laptop is one the phone would have made. The laptop can't list phone
 * apps, so a new block targets laptop apps and websites; phone apps are added on the phone. Every
 * new block is SOFT: hardcore needs the phone's Device Admin and password.
 *
 * An edit keeps the block's kind and how it unlocks, as on the phone; the checklist is edited
 * item by item ([addItem], [linkHabit], [removeItem]).
 */
object BlockForms {

    @Serializable
    data class LinkedHabit(val uuid: String, val name: String, val yesterday: Boolean = false, val goal: Boolean = false)

    @Serializable
    data class Form(
        val id: String? = null,
        val name: String = "",
        val mode: BlockMode = BlockMode.HABIT,
        val desktopApps: List<String> = emptyList(),
        val sites: List<String> = emptyList(),
        val checklist: Boolean = true,
        val timer: Boolean = false,
        val combineOr: Boolean = false,
        val timerMin: Long = 30,
        val unlockMin: Long = Defaults.DEFAULT_UNLOCK_MS / 60_000,
        val items: List<String> = emptyList(),
        val linked: List<LinkedHabit> = emptyList(),
        val allowanceMin: Long = 30,
        val lockdownMin: Long = 60,
        val days: Set<Int> = (1..5).toSet(),
        val startMin: Int = 9 * 60,
        val endMin: Int = 17 * 60,
    )

    /** Either the changed world or why not, in the phone's words. */
    sealed interface Result {
        data class Ok(val world: World, val block: Block) : Result
        data class Invalid(val reason: String) : Result
    }

    fun create(world: World, form: Form, clock: Clock): Result {
        val id = UUID.randomUUID().toString()
        val apps = form.desktopApps.filter { it.isNotBlank() }.toSet()
        val sites = form.sites.mapNotNull(Sites::normalize).toSet()
        val items = form.items.map { it.trim() }.filter { it.isNotEmpty() }
        problem(form, hasTargets = apps.isNotEmpty() || sites.isNotEmpty(), capMs = world.settings.dailyCapMs, creating = true, hasItems = items.isNotEmpty() || form.linked.isNotEmpty())
            ?.let { return Result.Invalid(it) }
        val habit = form.mode == BlockMode.HABIT
        val block = Block(
            id = id,
            name = form.name.trim(),
            targetSites = sites,
            targetDesktopApps = apps,
            trigger = if (habit) trigger(form.checklist, form.timer, form.timerMin, form.combineOr) else Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS),
            reward = Reward(unlockDurationMs = form.unlockMin * 60_000),
            schedule = if (form.mode == BlockMode.SCHEDULE) Schedule(form.days, TimeWindow(form.startMin, form.endMin)) else Schedule.ALWAYS,
            state = BlockState.ARMED,
            mode = form.mode,
            dailyAllowanceMs = if (form.mode == BlockMode.ALLOWANCE) form.allowanceMin * 60_000 else 0,
            lockdownMs = if (form.mode == BlockMode.LOCKDOWN) form.lockdownMin * 60_000 else 0,
        )
        val plain = if (habit && form.checklist) items.mapIndexed { i, text -> ChecklistItem(id = "$id-i$i", blockId = id, text = text, order = i) } else emptyList()
        val linked = if (habit && form.checklist) {
            form.linked.distinctBy { it.uuid }.mapIndexed { i, h -> linkedItem(id, h, plain.size + i) }
        } else {
            emptyList()
        }
        val all = plain + linked
        val next = world.copy(
            blocks = world.blocks + block,
            checklist = if (all.isEmpty()) world.checklist else world.checklist + (id to all),
        ).withLockdownStarted(id, clock) // "for a while" starts now, as on the phone
        return Result.Ok(next, block)
    }

    /** The edited block, keeping its kind, unlock method, phone apps and state. */
    fun edit(world: World, form: Form): Result {
        val old = world.blocks.firstOrNull { it.id == form.id } ?: return Result.Invalid("That block is gone.")
        val apps = form.desktopApps.filter { it.isNotBlank() }.toSet()
        val sites = form.sites.mapNotNull(Sites::normalize).toSet()
        val hadTimer = old.trigger.type == TriggerType.TIMER || old.trigger.type == TriggerType.COMBINED
        val kept = form.copy(mode = old.mode, checklist = old.trigger.type != TriggerType.TIMER, timer = hadTimer)
        problem(kept, hasTargets = old.targetPackages.isNotEmpty() || apps.isNotEmpty() || sites.isNotEmpty(), capMs = world.settings.dailyCapMs, creating = false, hasItems = true)
            ?.let { return Result.Invalid(it) }
        val block = old.copy(
            name = form.name.trim(),
            targetSites = sites,
            targetDesktopApps = apps,
            trigger = if (old.mode == BlockMode.HABIT && hadTimer) {
                old.trigger.copy(requiredAmount = form.timerMin * 60_000, combineMode = if (form.combineOr) CombineMode.OR else CombineMode.AND)
            } else {
                old.trigger
            },
            reward = old.reward.copy(unlockDurationMs = form.unlockMin * 60_000),
            dailyAllowanceMs = if (old.mode == BlockMode.ALLOWANCE) form.allowanceMin * 60_000 else old.dailyAllowanceMs,
            lockdownMs = if (old.mode == BlockMode.LOCKDOWN) form.lockdownMin * 60_000 else old.lockdownMs,
            schedule = if (old.mode == BlockMode.SCHEDULE) Schedule(form.days, TimeWindow(form.startMin, form.endMin)) else old.schedule,
        )
        return Result.Ok(world.copy(blocks = world.blocks.map { if (it.id == old.id) block else it }), block)
    }

    fun addItem(world: World, blockId: String, text: String): World {
        val existing = world.items(blockId)
        val item = ChecklistItem(id = "$blockId-${UUID.randomUUID()}", blockId = blockId, text = text.trim(), order = existing.size)
        return world.copy(checklist = world.checklist + (blockId to existing + item))
    }

    fun linkHabit(world: World, blockId: String, habit: LinkedHabit): World {
        val existing = world.items(blockId)
        if (existing.any { it.externalHabitId == habit.uuid }) return world
        return world.copy(checklist = world.checklist + (blockId to existing + linkedItem(blockId, habit, existing.size)))
    }

    fun removeItem(world: World, itemId: String): World {
        val entry = world.checklist.entries.firstOrNull { (_, items) -> items.any { it.id == itemId } } ?: return world
        val updated = entry.value.filterNot { it.id == itemId }.mapIndexed { i, item -> item.copy(order = i) }
        return world.copy(checklist = world.checklist + (entry.key to updated))
    }

    fun delete(world: World, blockId: String): World = world.copy(
        blocks = world.blocks.filterNot { it.id == blockId },
        progress = world.progress - blockId,
        credits = world.credits - blockId,
        sessions = world.sessions - blockId,
        lockdowns = world.lockdowns - blockId,
        checklist = world.checklist - blockId,
    )

    private fun trigger(checklist: Boolean, timer: Boolean, timerMin: Long, or: Boolean): Trigger {
        val ms = timerMin * 60_000
        return when {
            checklist && timer -> Trigger(TriggerType.COMBINED, ms, TriggerUnit.MS, if (or) CombineMode.OR else CombineMode.AND)
            timer -> Trigger(TriggerType.TIMER, ms, TriggerUnit.MS)
            else -> Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS)
        }
    }

    /** A checklist item only the habit tracker can tick (contract I23); the habit's name is its text. */
    private fun linkedItem(blockId: String, habit: LinkedHabit, order: Int) = ChecklistItem(
        id = "$blockId-${UUID.randomUUID()}",
        blockId = blockId,
        text = habit.name,
        order = order,
        externalHabitId = habit.uuid,
        linkedDay = if (habit.yesterday) LinkedDay.YESTERDAY else LinkedDay.TODAY,
        countsGoal = habit.goal,
    )

    /** The phone's hints (`CreateBlockScreen`), first one that applies. */
    private fun problem(form: Form, hasTargets: Boolean, capMs: Long, creating: Boolean, hasItems: Boolean): String? {
        val capMin = capMs / 60_000
        return when {
            !hasTargets -> "Pick a laptop app or add a website to block"
            form.name.isBlank() -> "Give the block a name"
            form.mode == BlockMode.ALLOWANCE && form.allowanceMin <= 0 -> "Set how many minutes a day"
            form.mode == BlockMode.SCHEDULE && form.days.isEmpty() -> "Pick at least one day"
            form.mode == BlockMode.SCHEDULE && (form.days.any { it !in 1..7 } || form.startMin !in 0 until 1440 || form.endMin !in 0 until 1440) -> "Those times don't make sense"
            form.mode == BlockMode.SCHEDULE && form.startMin == form.endMin -> "Start and end time can't be the same"
            form.mode == BlockMode.LOCKDOWN && form.lockdownMin <= 0 -> "Set how long to block"
            form.mode != BlockMode.HABIT -> null
            !form.checklist && !form.timer -> "Pick at least one way to unlock it"
            form.checklist && creating && !hasItems -> "Add at least one task"
            form.timer && form.timerMin <= 0 -> "Set the timer minutes"
            form.unlockMin <= 0 -> "Set how many minutes an unlock lasts"
            form.unlockMin > capMin -> "An unlock can't be longer than your daily unlock limit ($capMin min)"
            else -> null
        }
    }
}
