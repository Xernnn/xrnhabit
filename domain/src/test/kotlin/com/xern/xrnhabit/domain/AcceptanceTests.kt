package com.xern.xrnhabit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end runs of the acceptance criteria from the brainstorm §1, driven purely through
 * the engine. These are the "core loop is done" checks, minus the on-device parts.
 */
class AcceptanceTests {

    private val engine = BlockEngine()

    /** Acceptance 1–5 + 9 with a to-do list habit. */
    @Test
    fun checklistHabit_blockEarnRedeemRelock() {
        val clock = FakeClock()
        val block = checklistBlock("c1", target = "com.insta", required = Defaults.ALL_ITEMS, unlockMs = 5L * 60_000)
        val world = buildWorld(listOf(block), mapOf("c1" to items("c1", 2)), clock)

        // Open the target app -> shielded, and an impression is recorded for Insights.
        var out = engine.reduce(world, BlockEvent.ForegroundChanged("com.insta"), clock)
        assertTrue(engine.blockedPackages(out.world, clock).contains("com.insta"))
        assertTrue(out.effects.any { it is Effect.BlockedImpression })

        // Work the to-do list. Partial completion stays ARMED.
        out = engine.reduce(out.world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        out = engine.reduce(out.world, BlockEvent.ToggleChecklistItem("c1-i1", true), clock)
        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue(out.effects.any { it is Effect.HabitCompleted })

        // Redeem -> unlocked, shield lifts.
        out = engine.reduce(out.world, BlockEvent.Redeem("c1"), clock)
        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)
        assertEquals(emptySet<String>(), out.shieldPlan!!.blockedPackages)

        // 5 minutes later the shield re-applies itself with no further user action.
        clock.advance(5L * 60_000)
        out = engine.reduce(out.world, BlockEvent.Tick, clock)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(engine.blockedPackages(out.world, clock).contains("com.insta"))
        assertEquals(0, out.world.items("c1").count { it.done })
    }

    /** Acceptance 6 + 8: reboot mid-habit, and no accrual while the target app is backgrounded. */
    @Test
    fun readingTimer_survivesRebootAndDoesNotAccrueWhileAway() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.reddit", requiredMs = 30L * 60_000, unlockMs = 5L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.reddit")

        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(20L * 60_000) // read 20 min in the target
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(20L * 60_000, world.progress["t1"]!!.accumulatedMs)

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.launcher"), clock).world
        clock.advance(10L * 60_000) // 10 min away
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals("no accrual while the target is backgrounded", 20L * 60_000, world.progress["t1"]!!.accumulatedMs)

        // Reboot: monotonic clock resets.
        clock.setMono(500)
        val boot = engine.reduce(world, BlockEvent.BootOrColdStart, clock)
        assertEquals(BlockState.ARMED, boot.world.block("t1")!!.state)
        assertEquals("progress survives reboot", 20L * 60_000, boot.world.progress["t1"]!!.accumulatedMs)
        assertNotNull(boot.shieldPlan)

        // Finish the last 10 minutes.
        world = engine.reduce(boot.world, BlockEvent.ForegroundChanged("com.reddit"), clock).world
        clock.advance(10L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, world.block("t1")!!.state)
    }

    /** Acceptance 7: winding the clock back during an unlock ends the session immediately. */
    @Test
    fun unlockSession_cannotBeExtendedByClockChange() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1", unlockMs = 5L * 60_000)), mapOf("c1" to items("c1", 1)), clock)
        var out = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock)
        out = engine.reduce(out.world, BlockEvent.Redeem("c1"), clock)
        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)

        clock.advanceMonoOnly(90_000)
        clock.advanceWallOnly(-45L * 60_000)
        out = engine.reduce(out.world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertFalse(engine.blockedPackages(out.world, clock).isEmpty())
        assertTrue(out.effects.any { it is Effect.TamperDetected })
    }
}
