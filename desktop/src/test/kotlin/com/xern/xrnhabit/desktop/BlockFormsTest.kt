package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.desktop.BlockForms.Form
import com.xern.xrnhabit.desktop.BlockForms.LinkedHabit
import com.xern.xrnhabit.desktop.BlockForms.Result
import com.xern.xrnhabit.domain.BlockEdits
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.EnforcementTier
import com.xern.xrnhabit.domain.LinkedDay
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BlockFormsTest {

    private val clock = object : Clock {
        override fun nowMonotonicMs() = 5_000_000L
        override fun nowWallMs() = 1_800_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private fun ok(r: Result) = (r as? Result.Ok) ?: error("expected Ok, got $r")

    private fun reason(r: Result) = (r as? Result.Invalid)?.reason ?: error("expected Invalid, got $r")

    @Test
    fun aHabitBlockGetsItsTasksAndLinkedHabits() {
        val r = ok(
            BlockForms.create(
                World(),
                Form(
                    name = " Games ",
                    desktopApps = listOf("steam"),
                    sites = listOf("https://www.Reddit.com/r/x"),
                    items = listOf("Stretch", " "),
                    linked = listOf(LinkedHabit("u1", "Plank", yesterday = true, goal = true)),
                ),
                clock,
            ),
        )
        val b = r.block
        assertEquals("Games", b.name)
        assertEquals(setOf("steam"), b.targetDesktopApps)
        assertEquals(setOf("reddit.com"), b.targetSites)
        assertEquals(TriggerType.CHECKLIST, b.trigger.type)
        assertEquals(EnforcementTier.SOFT, b.enforcementTier)
        val items = r.world.items(b.id)
        assertEquals(listOf("Stretch", "Plank"), items.map { it.text })
        assertEquals("u1", items[1].externalHabitId)
        assertEquals(LinkedDay.YESTERDAY, items[1].linkedDay)
        assertTrue(items[1].countsGoal)
    }

    @Test
    fun theFormSaysWhatsMissing_inThePhonesWords() {
        assertEquals("Pick a laptop app or add a website to block", reason(BlockForms.create(World(), Form(name = "x", items = listOf("a")), clock)))
        assertEquals("Give the block a name", reason(BlockForms.create(World(), Form(sites = listOf("a.com"), items = listOf("a")), clock)))
        assertEquals("Add at least one task", reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com")), clock)))
        assertEquals(
            "An unlock can't be longer than your daily unlock limit (60 min)",
            reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), items = listOf("a"), unlockMin = 90), clock)),
        )
        assertEquals(
            "Start and end time can't be the same",
            reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), mode = BlockMode.SCHEDULE, startMin = 600, endMin = 600), clock)),
        )
    }

    @Test
    fun aLockdownStartsWhenMade_andATimerAndTasksCombine() {
        val lockdown = ok(BlockForms.create(World(), Form(name = "Focus", sites = listOf("a.com"), mode = BlockMode.LOCKDOWN, lockdownMin = 45), clock))
        assertEquals(45 * 60_000L, lockdown.world.lockdowns[lockdown.block.id]?.durationMs)

        val both = ok(BlockForms.create(World(), Form(name = "Both", sites = listOf("a.com"), timer = true, timerMin = 20, combineOr = true, items = listOf("a")), clock))
        assertEquals(TriggerType.COMBINED, both.block.trigger.type)
        assertEquals(CombineMode.OR, both.block.trigger.combineMode)
        assertEquals(20 * 60_000L, both.block.trigger.requiredAmount)
    }

    @Test
    fun anEditKeepsKindMethodPhoneAppsAndState_andTakingASiteAwayLoosens() {
        val made = ok(BlockForms.create(World(), Form(name = "Games", sites = listOf("a.com", "b.com"), items = listOf("a")), clock))
        val world = made.world.copy(
            blocks = made.world.blocks.map { it.copy(targetPackages = setOf("com.game"), state = BlockState.EARNED) },
        )
        val edited = ok(BlockForms.edit(world, Form(id = made.block.id, name = "Games", mode = BlockMode.LOCKDOWN, timer = true, sites = listOf("a.com"))))
        assertEquals(BlockMode.HABIT, edited.block.mode)
        assertEquals(TriggerType.CHECKLIST, edited.block.trigger.type)
        assertEquals(setOf("com.game"), edited.block.targetPackages)
        assertEquals(BlockState.EARNED, edited.block.state)
        assertTrue(BlockEdits.loosens(world.block(made.block.id)!!, edited.block))
        assertFalse(BlockEdits.loosens(world.block(made.block.id)!!, edited.block.copy(targetSites = setOf("a.com", "b.com", "c.com"))))
    }

    @Test
    fun itemsComeAndGo_andDeletingClearsEverything() {
        val made = ok(BlockForms.create(World(), Form(name = "G", sites = listOf("a.com"), items = listOf("a")), clock))
        var w = BlockForms.addItem(made.world, made.block.id, " Read ")
        w = BlockForms.linkHabit(w, made.block.id, LinkedHabit("u1", "Plank"))
        w = BlockForms.linkHabit(w, made.block.id, LinkedHabit("u1", "Plank")) // once only
        assertEquals(listOf("a", "Read", "Plank"), w.items(made.block.id).map { it.text })
        w = BlockForms.removeItem(w, w.items(made.block.id)[0].id)
        assertEquals(listOf(0, 1), w.items(made.block.id).map { it.order })
        w = BlockForms.delete(w, made.block.id)
        assertTrue(w.blocks.isEmpty())
        assertNotNull(w.checklist)
        assertTrue(made.block.id !in w.checklist)
    }
}
