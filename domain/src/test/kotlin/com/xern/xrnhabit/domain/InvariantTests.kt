package com.xern.xrnhabit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * One test per invariant in `docs/domain-contract.md` §3. If you change an invariant, change
 * the contract first, then this file, then the engine.
 */
class InvariantTests {

    private val engine = BlockEngine()

    private fun earn(world: World, clock: Clock, itemId: String = "c1-i0"): World =
        engine.reduce(world, BlockEvent.ToggleChecklistItem(itemId, true), clock).world

    @Test
    fun i01_creditExpiresAfterTtlOnWallClock() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)

        val earned = earn(world, clock)
        assertEquals(BlockState.EARNED, earned.block("c1")!!.state)

        clock.advance(Defaults.CREDIT_TTL_MS + 1)
        val out = engine.reduce(earned, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(out.world.credits.isEmpty())
    }

    @Test
    fun i02_unlockCountdownUsesMonotonicNotWall() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        var out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)
        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)

        // Wall jumps 10h forward but monotonic time did not advance: session must not end.
        clock.advanceWallOnly(10L * 60 * 60 * 1000)
        out = engine.reduce(out.world, BlockEvent.Tick, clock)

        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)
    }

    @Test
    fun i03_windingClockBackDuringUnlockEndsSession() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        var out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)

        clock.advanceMonoOnly(60_000)          // one real minute passed
        clock.advanceWallOnly(-60L * 60 * 1000) // user set the clock back an hour
        out = engine.reduce(out.world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(out.effects.any { it is Effect.TamperDetected })
    }

    @Test
    fun i04_readingTimerPausesWhileTargetIsNotForeground() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")

        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000) // 10s in target
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        clock.advance(10_000) // 10s away
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(10_000) // 10s back in target
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(20_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i05_shieldOnUnlessUnlocked() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)

        assertTrue(engine.shieldOn(world, "c1", clock))
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        val earned = earn(world, clock)
        assertTrue(engine.shieldOn(earned, "c1", clock))

        val unlocked = engine.reduce(earned, BlockEvent.Redeem("c1"), clock).world
        assertFalse(engine.shieldOn(unlocked, "c1", clock))
        assertFalse(engine.blockedPackages(unlocked, clock).contains("com.target"))
    }

    @Test
    fun i05_shieldStaysOnWhileTimerRuns() {
        val clock = FakeClock()
        var world = buildWorld(listOf(timerBlock("t1")), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(5_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertTrue(engine.blockedPackages(out.world, clock).contains("com.target"))
        assertNull("no shield change while merely running a timer", out.shieldPlan)
    }

    @Test
    fun i06_appUnlockedIfAnyCoveringBlockUnlocked() {
        val clock = FakeClock()
        val b1 = checklistBlock("c1", target = "com.x")
        val b2 = checklistBlock("c2", target = "com.x")
        val world = buildWorld(
            listOf(b1, b2),
            mapOf("c1" to items("c1", 1), "c2" to items("c2", 1)),
            clock,
        )

        var out = engine.reduce(earn(world, clock, "c2-i0"), BlockEvent.Redeem("c2"), clock)

        assertEquals(BlockState.UNLOCKED, out.world.block("c2")!!.state)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(engine.isAppUnlocked(out.world, "com.x"))
        assertFalse(engine.blockedPackages(out.world, clock).contains("com.x"))
    }

    @Test
    fun i07_persistHappensBeforeShield() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)
        val outcome = engine.reduce(earned, BlockEvent.Redeem("c1"), clock)
        assertNotNull("redeem must change the shield", outcome.shieldPlan)

        val log = mutableListOf<String>()
        applyOutcome(
            outcome,
            RecordingPersist(earned, log),
            RecordingShield(log),
            NoopTelemetry,
            clock.nowWallMs(),
        )

        assertEquals(listOf("persist", "shield"), log)
    }

    @Test
    fun i08_coldStartRebuildsExpiredCreditAndReappliesShield() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)

        // Persist, then "reboot": wall advanced past the TTL, monotonic clock reset.
        clock.advanceWallOnly(Defaults.CREDIT_TTL_MS + 60_000)
        clock.setMono(1234)
        val out = engine.reduce(earned, BlockEvent.BootOrColdStart, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertNotNull("boot always re-applies the shield", out.shieldPlan)
    }

    @Test
    fun i08_coldStartKeepsStillValidCredit() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)

        clock.advanceWallOnly(60_000) // one minute later
        clock.setMono(50)
        val out = engine.reduce(earned, BlockEvent.BootOrColdStart, clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
    }

    @Test
    fun i09_redeemRejectedWhenDailyCapReached() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 5L * 60_000)
        val world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
            .copy(dailyUnlockUsedMs = 58L * 60_000, dailyUsageEpochDay = clock.epochDay())

        val out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue(out.effects.any { it is Effect.RedeemRejected && it.reason == RejectReason.CAP_REACHED })
        assertEquals("the UI can say why before the tap", RejectReason.CAP_REACHED, engine.redeemRejection(earn(world, clock), "c1", clock))
    }

    @Test
    fun i10_returnToArmedResetsProgressAndChecklist() {
        val clock = FakeClock()
        val block = checklistBlock("c1", required = Defaults.ALL_ITEMS)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 3)), clock)

        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i1", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i2", true), clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
        assertEquals(3, world.items("c1").count { it.done })

        clock.advance(Defaults.CREDIT_TTL_MS + 1)
        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertEquals(0, out.world.items("c1").count { it.done })
        assertEquals(0L, out.world.progress["c1"]!!.accumulatedMs)
    }

    @Test
    fun i11_checklistThreshold() {
        val clock = FakeClock()
        data class Case(val size: Int, val required: Long, val done: Int, val expected: Boolean)
        val cases = listOf(
            Case(3, Defaults.ALL_ITEMS, 3, true),
            Case(3, Defaults.ALL_ITEMS, 2, false),
            Case(3, 2, 2, true),
            Case(3, 2, 1, false),
            Case(0, Defaults.ALL_ITEMS, 0, false),
            Case(0, 5, 0, false),
        )
        for (c in cases) {
            val block = checklistBlock("c1", required = c.required)
            val checklist = if (c.size > 0) mapOf("c1" to items("c1", c.size, done = c.done)) else emptyMap()
            val world = buildWorld(listOf(block), checklist, clock)
            assertEquals(
                "size=${c.size} required=${c.required} done=${c.done}",
                c.expected,
                engine.triggerMet(world, world.block("c1")!!),
            )
        }
    }

    @Test
    fun i12_toggleIsNoOpUnlessArmed() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)
        assertEquals(BlockState.EARNED, earned.block("c1")!!.state)

        val out = engine.reduce(earned, BlockEvent.ToggleChecklistItem("c1-i0", done = false), clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue("untick on a non-ARMED block is ignored", out.world.items("c1").single().done)
    }

    @Test
    fun i13_dayRolloverResetsArmedProgress() {
        val zone = ZoneId.of("UTC")
        val clock = FakeClock(
            mono = 0,
            wall = Instant.parse("2026-01-15T00:01:00Z").toEpochMilli(),
            zoneId = zone,
        )
        val block = timerBlock("t1")
        val yesterday = Instant.parse("2026-01-14T23:59:00Z").toEpochMilli()
        val world = World(
            blocks = listOf(block),
            progress = mapOf(
                "t1" to HabitProgress("t1", accumulatedMs = 123_456, windowStart = yesterday, lastUpdated = 0),
            ),
            dailyUsageEpochDay = clock.epochDay(),
        )

        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("t1")!!.state)
        assertEquals(0L, out.world.progress["t1"]!!.accumulatedMs)
        assertEquals(
            Instant.parse("2026-01-15T00:00:00Z").toEpochMilli(),
            out.world.progress["t1"]!!.windowStart,
        )
    }

    @Test
    fun i14_checklistTriggerWithNoItemsIsNeverMet() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1", required = Defaults.ALL_ITEMS)), emptyMap(), clock)

        assertFalse(engine.triggerMet(world, world.block("c1")!!))

        val strayToggle = engine.reduce(world, BlockEvent.ToggleChecklistItem("ghost", true), clock)
        assertEquals(BlockState.ARMED, strayToggle.world.block("c1")!!.state)

        val tick = engine.reduce(strayToggle.world, BlockEvent.Tick, clock)
        assertEquals(BlockState.ARMED, tick.world.block("c1")!!.state)
    }

    @Test
    fun i15_combinedAndRequiresBoth() {
        val clock = FakeClock()
        val block = combinedBlock("x1", requiredMs = 10_000, combineMode = CombineMode.AND)
        var world = buildWorld(listOf(block), mapOf("x1" to items("x1", 2)), clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("x1", running = true), clock).world

        // Checklist finished alone: not enough.
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i1", true), clock).world
        assertEquals(BlockState.ARMED, world.block("x1")!!.state)

        // Timer alone (checklist already done above, but still short of its own threshold too):
        // advance only the timer and confirm it is *also* insufficient on its own by checking
        // mid-way, then finish it — order of completion must not matter (I15).
        clock.advance(5_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, world.block("x1")!!.state)

        clock.advance(5_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, world.block("x1")!!.state)
    }

    @Test
    fun i15_combinedOrRequiresEither() {
        val clock = FakeClock()
        val block = combinedBlock("x1", requiredMs = 10_000, combineMode = CombineMode.OR)
        val world = buildWorld(listOf(block), mapOf("x1" to items("x1", 2)), clock, foregroundPkg = "com.target")

        // Checklist alone earns it — timer never started.
        var out = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i0", true), clock).world
        out = engine.reduce(out, BlockEvent.ToggleChecklistItem("x1-i1", true), clock).world
        assertEquals(BlockState.EARNED, out.block("x1")!!.state)

        // Independently: timer alone earns it — checklist never touched.
        var timerOnly = engine.reduce(world, BlockEvent.TimerControl("x1", running = true), clock).world
        clock.advance(10_000)
        timerOnly = engine.reduce(timerOnly, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, timerOnly.block("x1")!!.state)
        assertEquals(0, timerOnly.items("x1").count { it.done })
    }

    @Test
    fun i16_habitPackagesOverridesTargetForAccrual() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4, habitPackages = setOf("com.habit"))
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000) // sitting in the blocked target app — must NOT count
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.habit"), clock).world
        assertEquals(0L, world.progress["t1"]!!.accumulatedMs)

        clock.advance(10_000) // now in the designated habit app — counts
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        assertEquals(10_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i16_emptyHabitPackagesFallsBackToTarget() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4, habitPackages = emptySet())
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(10_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i17_multiTargetAccrualCountsAnyTarget() {
        val clock = FakeClock()
        val block = Block(
            id = "t1",
            name = "Multi",
            targetPackages = setOf("com.a", "com.b"),
            trigger = Trigger(TriggerType.TIMER, Long.MAX_VALUE / 4, TriggerUnit.MS),
        )
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.a")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        assertTrue(engine.blockedPackages(world, clock).containsAll(setOf("com.a", "com.b")))

        clock.advance(10_000) // in com.a
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.b"), clock).world
        clock.advance(10_000) // in com.b
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(20_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i18_siteTargetBlocksAndMatchesSubdomains() {
        val clock = FakeClock()
        val block = Block(
            id = "s1",
            name = "Sites",
            targetSites = setOf("youtube.com"),
            trigger = Trigger(TriggerType.TIMER, 30L * 60_000, TriggerUnit.MS),
        )
        val world = buildWorld(listOf(block), clock = clock)

        val plan = engine.shieldPlan(world, clock)
        assertEquals(setOf("youtube.com"), plan.blockedSites)
        assertTrue(plan.covers("com.browser", "youtube.com"))
        assertTrue(plan.covers("com.browser", "m.youtube.com"))
        assertFalse(plan.covers("com.browser", "notyoutube.com"))
        assertFalse(plan.covers("com.browser", null))
    }

    @Test
    fun i18_timerAccruesOnTargetSite() {
        val clock = FakeClock()
        val block = Block(
            id = "s1",
            name = "Sites",
            targetSites = setOf("youtube.com"),
            trigger = Trigger(TriggerType.TIMER, Long.MAX_VALUE / 4, TriggerUnit.MS),
        )
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.TimerControl("s1", running = true), clock).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.browser", "m.youtube.com"), clock).world

        clock.advance(10_000) // on the target site
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.browser", "example.com"), clock).world
        clock.advance(10_000) // same browser, different site — must not count
        world = engine.reduce(world, BlockEvent.ForegroundChanged(null), clock).world

        assertEquals(10_000L, world.progress["s1"]!!.accumulatedMs)
    }

    @Test
    fun i19_allowanceBlocksOnceUsedUp() {
        val clock = FakeClock()
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")

        clock.advance(9L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advance(2L * 60_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)
        assertTrue(out.shieldPlan!!.blockedPackages.contains("com.target"))
        assertEquals(10L * 60_000, out.world.progress["a1"]!!.accumulatedMs) // capped
        assertEquals(BlockState.ARMED, out.world.block("a1")!!.state)
    }

    @Test
    fun i19_allowanceResetsAtDayRollover() {
        val clock = FakeClock() // 12:00 UTC
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        clock.advance(10L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advance(12L * 60 * 60_000) // past midnight
        world = engine.reduce(world, BlockEvent.Tick, clock).world

        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))
        assertEquals(0L, world.progress["a1"]!!.accumulatedMs)
    }

    @Test
    fun i19_allowanceIgnoresTimeBehindShield() {
        val clock = FakeClock() // 12:00 UTC, inside 09:00–17:00
        val allowance = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        val schedule = modeBlock("s1", BlockMode.SCHEDULE, schedule = everyDay(9 * 60, 17 * 60))
        var world = buildWorld(listOf(allowance, schedule), clock = clock, foregroundPkg = "com.target")

        clock.advance(5L * 60_000) // in the app, but the schedule block has it shielded
        world = engine.reduce(world, BlockEvent.Tick, clock).world

        assertEquals(0L, world.progress["a1"]!!.accumulatedMs)
    }

    @Test
    fun i20_scheduleBlocksOnlyInsideWindow() {
        val clock = FakeClock() // Thursday 12:00 UTC
        val day = modeBlock("d1", BlockMode.SCHEDULE, targetPackages = setOf("com.day"), schedule = everyDay(9 * 60, 17 * 60))
        val night = modeBlock("n1", BlockMode.SCHEDULE, targetPackages = setOf("com.night"), schedule = everyDay(22 * 60, 6 * 60))
        val world = buildWorld(listOf(day, night), clock = clock)
        fun blocked() = engine.blockedPackages(world, clock)

        assertEquals(setOf("com.day"), blocked()) // 12:00
        clock.advance(5L * 60 * 60_000) // 17:00 — window end is exclusive
        assertEquals(emptySet<String>(), blocked())
        clock.advance(6L * 60 * 60_000) // 23:00
        assertEquals(setOf("com.night"), blocked())
        clock.advance(6L * 60 * 60_000) // 05:00 next day
        assertEquals(setOf("com.night"), blocked())
        clock.advance(3L * 60 * 60_000 + 59L * 60_000) // 08:59
        assertEquals(emptySet<String>(), blocked())
        clock.advance(60_000) // 09:00
        assertEquals(setOf("com.day"), blocked())
    }

    @Test
    fun i20_overnightWindowBelongsToItsStartDay() {
        val clock = FakeClock() // Thursday 2026-01-15 12:00 UTC
        val fridayNight = Schedule(setOf(5), TimeWindow(23 * 60, 7 * 60))
        val block = modeBlock("n1", BlockMode.SCHEDULE, schedule = fridayNight)
        val world = buildWorld(listOf(block), clock = clock)
        fun blocked() = engine.isBlocking(world, block, clock)

        clock.advance(14L * 60 * 60_000) // Fri 02:00: Thursday night, not in the schedule
        assertFalse(blocked())
        clock.advance(21L * 60 * 60_000 + 30L * 60_000) // Fri 23:30
        assertTrue(blocked())
        clock.advance(2L * 60 * 60_000 + 30L * 60_000) // Sat 02:00: still Friday night
        assertTrue(blocked())
        clock.advance(5L * 60 * 60_000) // Sat 07:00: window over
        assertFalse(blocked())
    }

    @Test
    fun i21_lockdownIgnoresWallClockAndEnds() {
        val clock = FakeClock()
        val block = modeBlock("l1", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(block), clock = clock)
        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))

        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world
        val started = world.lockdowns["l1"]!!
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advanceWallOnly(5L * 60 * 60_000) // user pushes the clock forward 5 h
        clock.advanceMonoOnly(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))
        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world
        assertEquals(started, world.lockdowns["l1"]) // no restart while running

        clock.advanceMonoOnly(59L * 60_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)
        assertFalse(out.shieldPlan!!.blockedPackages.contains("com.target"))
        assertTrue(out.world.lockdowns.isEmpty())
    }

    @Test
    fun i21_lockdownSurvivesRebootViaWallDelta() {
        val clock = FakeClock(mono = 50_000_000)
        val block = modeBlock("l1", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world

        clock.advance(10L * 60_000)
        clock.setMono(1_000) // reboot: monotonic restarts near zero
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world

        assertEquals(50L * 60_000, engine.lockdownRemainingMs(world, "l1", clock))
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))
    }

    @Test
    fun i22_habitUnlockDoesNotLiftStrictBlock() {
        val clock = FakeClock() // 12:00 UTC
        val habit = checklistBlock("h1", target = "com.x", state = BlockState.UNLOCKED)
        val schedule = modeBlock("s1", BlockMode.SCHEDULE, targetPackages = setOf("com.x"), schedule = everyDay(9 * 60, 17 * 60))
        val world = buildWorld(listOf(habit, schedule), clock = clock)

        assertTrue(engine.blockedPackages(world, clock).contains("com.x"))
        clock.advance(6L * 60 * 60_000) // 18:00 — schedule over, habit still UNLOCKED
        assertFalse(engine.blockedPackages(world, clock).contains("com.x"))
    }

    // ---- linked items: the external habit tracker (I23, I24) ------------------------------

    private fun sync(world: World, clock: Clock, vararg done: String): World =
        engine.reduce(world, BlockEvent.ExternalHabitsSynced(clock.epochDay(), done.toSet()), clock).world

    @Test
    fun i23_manualToggleIgnoredOnLinkedItem() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))), clock)

        val out = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-meditate", true), clock)

        assertFalse(out.world.items("c1").single().done)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
    }

    @Test
    fun i23_syncTicksLinkedItemAndEarns() {
        val clock = FakeClock()
        val world = buildWorld(
            listOf(checklistBlock("c1"), checklistBlock("c2")),
            mapOf(
                "c1" to listOf(linkedItem("c1", "meditate")),
                "c2" to items("c2", 1) + linkedItem("c2", "meditate", order = 1),
            ),
            clock,
        )

        val out = engine.reduce(world, BlockEvent.ExternalHabitsSynced(clock.epochDay(), setOf("meditate", "other")), clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue(out.effects.contains(Effect.HabitCompleted("c1")))
        assertTrue(out.world.items("c2").single { it.externalHabitId != null }.done)
        assertEquals("c2's plain item is still open", BlockState.ARMED, out.world.block("c2")!!.state)

        val earned = engine.reduce(out.world, BlockEvent.ToggleChecklistItem("c2-i0", true), clock).world
        assertEquals(BlockState.EARNED, earned.block("c2")!!.state)
    }

    @Test
    fun i23_syncUnticksWhileArmed() {
        val clock = FakeClock()
        val checklist = items("c1", 1) + linkedItem("c1", "meditate", order = 1)
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to checklist), clock)

        world = sync(world, clock, "meditate")
        assertTrue(world.items("c1").single { it.externalHabitId != null }.done)

        world = sync(world, clock) // unticked in the tracker
        val linked = world.items("c1").single { it.externalHabitId != null }
        assertFalse(linked.done)
        assertNull(linked.doneAtMonotonic)
    }

    @Test
    fun i23_syncForAnotherDayIsIgnored() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))), clock)

        val stale = BlockEvent.ExternalHabitsSynced(clock.epochDay() - 1, setOf("meditate"))
        val out = engine.reduce(world, stale, clock)

        assertFalse(out.world.items("c1").single().done)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
    }

    @Test
    fun i23_yesterdayItemCountsYesterdaysTick() {
        val clock = FakeClock()
        val item = linkedItem("c1", "journal", day = LinkedDay.YESTERDAY)
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(item)), clock)

        world = sync(world, clock, "journal") // done today only
        assertFalse(world.items("c1").single().done)
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)

        val yesterday = BlockEvent.ExternalHabitsSynced(clock.epochDay(), emptySet(), setOf("journal"))
        world = engine.reduce(world, yesterday, clock).world
        assertTrue(world.items("c1").single().done)
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
    }

    @Test
    fun i23_goalItemCountsGoalMetDays() {
        val clock = FakeClock()
        val item = linkedItem("c1", "gym").copy(countsGoal = true)
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(item)), clock)

        world = sync(world, clock, "gym") // ticked today, but the item asks about the goal
        assertFalse(world.items("c1").single().done)

        val goalMet = BlockEvent.ExternalHabitsSynced(clock.epochDay(), emptySet(), goalMetHabitIds = setOf("gym"))
        world = engine.reduce(world, goalMet, clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
    }

    @Test
    fun i23_syncIgnoredUnlessArmed() {
        val clock = FakeClock()
        val checklist = items("c1", 1) + linkedItem("c1", "meditate", order = 1)
        var world = buildWorld(listOf(checklistBlock("c1", required = 1)), mapOf("c1" to checklist), clock)
        world = earn(world, clock) // the plain item alone meets required = 1
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)

        world = sync(world, clock, "meditate")

        assertFalse(world.items("c1").single { it.externalHabitId != null }.done)
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
    }

    @Test
    fun i24_externalTickEarnsOncePerDay() {
        val clock = FakeClock()
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))), clock)

        world = sync(world, clock, "meditate")
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
        assertNull("not spent until redeemed", world.items("c1").single().spentEpochDay)

        world = engine.reduce(world, BlockEvent.Redeem("c1"), clock).world
        assertEquals(clock.epochDay(), world.items("c1").single().spentEpochDay)
        clock.advance(5L * 60_000) // the unlock runs out
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)

        world = sync(world, clock, "meditate") // same tick, still done in the tracker
        assertFalse(world.items("c1").single().done)
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)
    }

    @Test
    fun i24_expiredCreditDoesNotSpendTick() {
        val clock = FakeClock()
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))), clock)
        world = sync(world, clock, "meditate")
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)

        clock.advance(Defaults.CREDIT_TTL_MS) // never redeemed (I1)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)
        assertNull(world.items("c1").single().spentEpochDay)

        world = sync(world, clock, "meditate")
        assertEquals("the unused tick earns again", BlockState.EARNED, world.block("c1")!!.state)
    }

    @Test
    fun i24_nextDaySameHabitCanEarnAgain() {
        val clock = FakeClock() // 12:00 UTC
        var world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))), clock)
        world = sync(world, clock, "meditate")
        world = engine.reduce(world, BlockEvent.Redeem("c1"), clock).world
        clock.advance(5L * 60_000) // the unlock runs out
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        world = sync(world, clock, "meditate")
        assertEquals("spent for today", BlockState.ARMED, world.block("c1")!!.state)

        clock.advance(24L * 60 * 60_000) // tomorrow
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        world = sync(world, clock, "meditate")

        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
    }

    @Test
    fun i25_pauseLiftsSoftBlocksOnly() {
        val clock = FakeClock()
        val soft = checklistBlock("soft", target = "com.soft")
        val hard = checklistBlock("hard", target = "com.hard").copy(enforcementTier = EnforcementTier.HARDCORE)
        val lockdown = modeBlock("lock", BlockMode.LOCKDOWN, targetPackages = setOf("com.lock"), lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(soft, hard, lockdown), clock = clock)
        world = engine.reduce(world, BlockEvent.StartLockdown("lock"), clock).world

        val out = engine.reduce(world, BlockEvent.StartPause(), clock)

        assertEquals(setOf("com.hard", "com.lock"), out.shieldPlan!!.blockedPackages)
        assertFalse(engine.isBlocking(out.world, soft, clock))
        assertTrue(engine.isBlocking(out.world, hard, clock))
    }

    @Test
    fun i25_pauseEndsAndIsOncePerDay() {
        val clock = FakeClock()
        var world = buildWorld(listOf(checklistBlock("c1")), clock = clock)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertTrue(engine.blockedPackages(world, clock).isEmpty())

        clock.advanceWallOnly(-60L * 60_000) // winding the clock back doesn't stretch it
        clock.advanceMonoOnly(Defaults.PAUSE_MS)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertNull(world.pause)
        assertEquals(setOf("com.target"), engine.blockedPackages(world, clock))

        clock.advanceWallOnly(60L * 60_000)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertNull("already had today's break", world.pause)

        clock.advance(24L * 60 * 60_000)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertNotNull(world.pause)
        world = engine.reduce(world, BlockEvent.EndPause, clock).world
        assertEquals(setOf("com.target"), engine.blockedPackages(world, clock))
    }

    @Test
    fun i25_pauseSurvivesRebootViaWallDelta() {
        val clock = FakeClock(mono = 50_000_000)
        var world = buildWorld(listOf(checklistBlock("c1")), clock = clock)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world

        clock.advance(5L * 60_000)
        clock.setMono(1_000)
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world

        assertEquals(10L * 60_000, engine.pauseRemainingMs(world, clock))
    }

    // ---- several devices (contract §2.4) -----------------------------------------------

    /** A phone and a laptop on the same wall clock but different monotonic clocks. */
    private fun twoDevices(blocks: List<Block>, checklist: Map<String, List<ChecklistItem>> = emptyMap()): Triple<Device, Device, FakeServer> {
        val phoneClock = FakeClock(mono = 7_000_000)
        val laptopClock = FakeClock(mono = 300_000)
        val phone = Device("phone", buildWorld(blocks, checklist, phoneClock), phoneClock)
        val laptop = Device("laptop", World(), laptopClock) // a fresh install: everything comes from the server
        val server = FakeServer()
        phone.sync(server)
        laptop.sync(server)
        return Triple(phone, laptop, server)
    }

    @Test
    fun i19_allowanceCountsOtherDevices() {
        val block = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = setOf("com.target"), dailyAllowanceMs = 10L * 60_000)
            .copy(targetSites = setOf("youtube.com"))
        val (phone, laptop, server) = twoDevices(listOf(block))

        phone.on(BlockEvent.ForegroundChanged("com.target"))
        laptop.on(BlockEvent.ForegroundChanged("browser", "youtube.com"))
        advanceAll(7L * 60_000, phone)
        phone.on(BlockEvent.Tick)
        phone.sync(server)
        laptop.sync(server)
        assertFalse(engine.isBlocking(laptop.world, laptop.block("a1"), laptop.clock))

        advanceAll(4L * 60_000, laptop) // 7 on the phone + 4 here: over the 10-minute allowance
        laptop.on(BlockEvent.Tick)
        assertTrue(engine.isBlocking(laptop.world, laptop.block("a1"), laptop.clock))

        // Yesterday's use elsewhere doesn't count today.
        advanceAll(24L * 60 * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertEquals(0L, engine.allowanceUsedMs(laptop.world, laptop.block("a1"), laptop.clock))
    }

    @Test
    fun i26_creditUsedElsewhereIsNotResurrected() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.sync(server)
        laptop.sync(server)
        assertEquals(BlockState.EARNED, laptop.block("c1").state)

        laptop.on(BlockEvent.Redeem("c1"))
        laptop.sync(server)
        phone.sync(server) // the phone still has the credit, unchanged since it last synced

        assertEquals(BlockState.UNLOCKED, phone.block("c1").state)
        assertTrue(phone.world.credits.isEmpty())
        assertEquals(laptop.world.sessions["c1"]!!.startedAtWall, phone.world.sessions["c1"]!!.startedAtWall)
        assertFalse(engine.blockedPackages(phone.world, phone.clock).contains("com.target"))
    }

    @Test
    fun i26_offlineDoubleRedeemKeepsLaterSession() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.sync(server)
        laptop.sync(server)

        phone.on(BlockEvent.Redeem("c1")) // both redeem while offline, the laptop a minute later
        advanceAll(60_000, phone, laptop)
        laptop.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)

        val later = laptop.world.sessions["c1"]!!
        assertEquals("the laptop's, a minute later", laptop.clock.nowWallMs(), later.startedAtWall)
        assertEquals(later.startedAtWall, phone.world.sessions["c1"]!!.startedAtWall)
        assertTrue(phone.world.credits.isEmpty() && laptop.world.credits.isEmpty())
    }

    @Test
    fun i26_sessionEndedElsewhereEndsHere() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)
        assertEquals(BlockState.UNLOCKED, laptop.block("c1").state)

        phone.clock.advanceWallOnly(-60L * 60_000) // the phone's clock is wound back: tamper (I3)
        phone.clock.advanceMonoOnly(60_000)
        phone.on(BlockEvent.Tick)
        assertEquals(BlockState.ARMED, phone.block("c1").state)
        phone.sync(server)
        laptop.sync(server)

        assertEquals(BlockState.ARMED, laptop.block("c1").state)
        assertTrue(engine.blockedPackages(laptop.world, laptop.clock).contains("com.target"))
    }

    @Test
    fun i27_adoptedSessionRunsOutOnTime() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1", unlockMs = 5L * 60_000)), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)

        advanceAll(2L * 60_000, phone, laptop) // the laptop hears about it two minutes in
        laptop.sync(server)
        val tick = laptop.on(BlockEvent.Tick)
        assertEquals(BlockState.UNLOCKED, laptop.block("c1").state)
        assertFalse("no false tamper", tick.effects.any { it is Effect.TamperDetected })

        advanceAll(3L * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertEquals("ends with the phone's, not five minutes after hearing of it", BlockState.ARMED, laptop.block("c1").state)
    }

    @Test
    fun i27_adoptedUnlockSpendsLocalTick() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))))
        // The phone reads the tick and earns; the laptop's tracker read is behind, so it only
        // has the credit from the phone when it redeems, and spends no tick of its own.
        phone.on(BlockEvent.ExternalHabitsSynced(phone.clock.epochDay(), setOf("meditate")))
        phone.sync(server)
        laptop.sync(server)
        laptop.on(BlockEvent.Redeem("c1"))
        assertNull(laptop.world.items("c1").single().spentEpochDay)
        laptop.sync(server)

        phone.sync(server) // the phone adopts the unlock: its own tick is now spent (I24)
        assertEquals(BlockState.UNLOCKED, phone.block("c1").state)
        assertEquals(phone.clock.epochDay(), phone.world.items("c1").single().spentEpochDay)

        advanceAll(5L * 60_000, phone, laptop)
        phone.on(BlockEvent.Tick)
        phone.on(BlockEvent.ExternalHabitsSynced(phone.clock.epochDay(), setOf("meditate")))
        assertEquals("one tick, one unlock", BlockState.ARMED, phone.block("c1").state)
        phone.sync(server)
        laptop.sync(server)
        assertEquals(phone.clock.epochDay(), laptop.world.items("c1").single().spentEpochDay)
    }

    @Test
    fun i27_linkedDoneIsNeverShared() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to listOf(linkedItem("c1", "meditate"))))
        phone.on(BlockEvent.ExternalHabitsSynced(phone.clock.epochDay(), setOf("meditate")))
        assertTrue(phone.world.items("c1").single().done)
        phone.sync(server)
        laptop.sync(server)

        assertEquals(BlockState.EARNED, laptop.block("c1").state) // the credit is shared
        assertFalse("the tick is the laptop's own to read", laptop.world.items("c1").single().done)
        assertTrue(phone.world.items("c1").single().done)
    }

    @Test
    fun i28_sharedSessionCountsOnceTowardCap() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1", unlockMs = 5L * 60_000)), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)

        advanceAll(5L * 60_000, phone, laptop) // both see the same session run out
        val endedOnPhone = phone.on(BlockEvent.Tick).effects.filterIsInstance<Effect.UnlockConsumed>().single()
        val endedOnLaptop = laptop.on(BlockEvent.Tick).effects.filterIsInstance<Effect.UnlockConsumed>().single()
        // Each logs it, under the session's one start, so a history across devices counts it once too.
        assertEquals(endedOnPhone.startedAtWall, endedOnLaptop.startedAtWall)
        assertTrue(endedOnPhone.startedAtWall > 0)
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)

        assertEquals(5L * 60_000, engine.unlockUsedTodayMs(phone.world, phone.clock))
        assertEquals(5L * 60_000, engine.unlockUsedTodayMs(laptop.world, laptop.clock))
    }

    @Test
    fun i29_desktopAppTargetsActLikePackages() {
        val clock = FakeClock()
        val block = checklistBlock("c1").copy(targetPackages = emptySet(), targetDesktopApps = setOf("steam"))
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)

        assertTrue(engine.blockedPackages(world, clock).contains("steam"))
        val shown = engine.reduce(world, BlockEvent.ForegroundChanged("steam"), clock)
        assertTrue(shown.effects.contains(Effect.BlockedImpression("steam")))

        world = engine.reduce(shown.world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.Redeem("c1"), clock).world
        assertTrue(engine.isAppUnlocked(world, "steam"))
        assertFalse(engine.blockedPackages(world, clock).contains("steam"))

        assertTrue(BlockEdits.loosens(block, block.copy(targetDesktopApps = emptySet())))
        assertFalse(BlockEdits.loosens(block, block.copy(targetDesktopApps = setOf("steam", "discord"))))

        val allowance = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = emptySet(), dailyAllowanceMs = 60L * 60_000)
            .copy(targetDesktopApps = setOf("steam"))
        var w = buildWorld(listOf(allowance), clock = clock, foregroundPkg = "steam")
        clock.advance(3L * 60_000)
        w = engine.reduce(w, BlockEvent.Tick, clock).world
        assertEquals(3L * 60_000, engine.allowanceUsedMs(w, allowance, clock))
    }
}
