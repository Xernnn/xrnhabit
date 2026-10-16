package com.xern.xrnhabit.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The projection and the merge table of contract §2.4 (I26). Scenarios across devices are in `InvariantTests`. */
class SharedStateTest {

    private val t = BASE_WALL

    private fun credit(id: String, earnedAt: Long) = EarnedCredit(id, 0, earnedAt, earnedAt + Defaults.CREDIT_TTL_MS, 5L * 60_000)
    private fun session(id: String, start: Long, ms: Long = 5L * 60_000) = UnlockSession(id, 0, start, ms)
    private fun lockdown(id: String, start: Long, ms: Long) = Lockdown(id, 0, start, ms)

    private fun merge(base: SharedState?, local: SharedState, remote: SharedState) = SharedState.merge(base, local, remote)

    @Test
    fun projection_dropsMonotonicStampsAndLinkedTicks() {
        val clock = FakeClock(mono = 123_456)
        val block = checklistBlock("c1")
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1) + linkedItem("c1", "meditate", order = 1)), clock)
        val engine = BlockEngine()
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.ExternalHabitsSynced(clock.epochDay(), setOf("meditate")), clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)

        val shared = SharedState.of(world)

        assertEquals(BlockState.ARMED, shared.blocks.getValue("c1").state)
        assertEquals(0L, shared.credits.getValue("c1").earnedAtMonotonic)
        assertEquals(world.credits.getValue("c1").earnedAtWall, shared.credits.getValue("c1").earnedAtWall)
        val manual = shared.items.getValue("c1-i0")
        assertTrue(manual.done)
        assertNull(manual.doneAtMonotonic)
        assertFalse("a linked tick is each device's own", shared.items.getValue("c1-meditate").done)
    }

    @Test
    fun oneSidedChangesWinIncludingDeletes() {
        val base = SharedState(credits = mapOf("c1" to credit("c1", t)), lockdowns = mapOf("l1" to lockdown("l1", t, 60_000)))
        val local = base.copy(credits = emptyMap()) // spent here
        val remote = base.copy(sessions = mapOf("c2" to session("c2", t))) // something else happened there
        val merged = merge(base, local, remote)
        assertTrue(merged.credits.isEmpty())
        assertEquals(setOf("c2"), merged.sessions.keys)
        assertEquals(base.lockdowns, merged.lockdowns)
    }

    @Test
    fun firstSyncHasNoBaseSoNothingIsDeleted() {
        val local = SharedState(credits = mapOf("c1" to credit("c1", t)))
        val remote = SharedState(sessions = mapOf("c2" to session("c2", t)))
        val merged = merge(null, local, remote)
        assertEquals(setOf("c1"), merged.credits.keys)
        assertEquals(setOf("c2"), merged.sessions.keys)
    }

    @Test
    fun sessions_laterEndWins_andNewBeatsEnded() {
        val base = SharedState()
        val early = session("c1", t)
        val late = session("c1", t + 60_000)
        assertEquals(late, merge(base, SharedState(sessions = mapOf("c1" to early)), SharedState(sessions = mapOf("c1" to late))).sessions["c1"])
        assertEquals(late, merge(base, SharedState(sessions = mapOf("c1" to late)), SharedState(sessions = mapOf("c1" to early))).sessions["c1"])

        val ended = SharedState(sessions = mapOf("c1" to early))
        val replaced = merge(ended, SharedState(), SharedState(sessions = mapOf("c1" to late)))
        assertEquals(late, replaced.sessions["c1"])
    }

    @Test
    fun credits_earlierWins_andSpentBeatsChanged() {
        val first = credit("c1", t)
        val second = credit("c1", t + 1_000)
        assertEquals(first, merge(null, SharedState(credits = mapOf("c1" to second)), SharedState(credits = mapOf("c1" to first))).credits["c1"])
        val base = SharedState(credits = mapOf("c1" to first))
        assertTrue(merge(base, SharedState(), SharedState(credits = mapOf("c1" to second))).credits.isEmpty())
    }

    @Test
    fun lockdowns_laterEndWins() {
        val short = lockdown("l1", t, 30L * 60_000)
        val long = lockdown("l1", t, 60L * 60_000)
        assertEquals(long, merge(null, SharedState(lockdowns = mapOf("l1" to short)), SharedState(lockdowns = mapOf("l1" to long))).lockdowns["l1"])
    }

    @Test
    fun pause_firstBreakOfTheDayStands() {
        val first = Pause(0, t, Defaults.PAUSE_MS)
        val second = Pause(0, t + 60_000, Defaults.PAUSE_MS)
        val merged = merge(SharedState(), SharedState(pause = second, lastPauseEpochDay = 5), SharedState(pause = first, lastPauseEpochDay = 4))
        assertEquals(first, merged.pause)
        assertEquals(5L, merged.lastPauseEpochDay)
    }

    @Test
    fun blocks_laterEditWins_andEditBeatsDelete() {
        val block = checklistBlock("c1")
        val base = SharedState(blocks = mapOf("c1" to block))
        val renamedHere = block.copy(name = "here", editedAtWall = t + 2)
        val renamedThere = block.copy(name = "there", editedAtWall = t + 1)
        assertEquals("here", merge(base, base.copy(blocks = mapOf("c1" to renamedHere)), base.copy(blocks = mapOf("c1" to renamedThere))).blocks.getValue("c1").name)

        val deletedHere = SharedState()
        assertEquals("there", merge(base, deletedHere, base.copy(blocks = mapOf("c1" to renamedThere))).blocks.getValue("c1").name)
        assertTrue("deleted, untouched elsewhere: gone", merge(base, deletedHere, base).blocks.isEmpty())
    }

    @Test
    fun blocks_keepThisDevicesOrder() {
        val a = checklistBlock("a")
        val b = checklistBlock("b")
        val c = checklistBlock("c")
        val merged = merge(null, SharedState(blocks = linkedMapOf("b" to b, "a" to a)), SharedState(blocks = linkedMapOf("a" to a, "c" to c)))
        assertEquals(listOf("b", "a", "c"), merged.blocks.keys.toList())
    }

    @Test
    fun items_configFromTheSideThatChangedIt_tickOred_spentMaxed() {
        val item = items("c1", 1).single()
        val base = SharedState(items = mapOf(item.id to item))
        val here = item.copy(done = true)
        val there = item.copy(text = "renamed", spentEpochDay = 9)
        val merged = merge(base, SharedState(items = mapOf(item.id to here)), SharedState(items = mapOf(item.id to there))).items.getValue(item.id)
        assertEquals("renamed", merged.text)
        assertTrue(merged.done)
        assertEquals(9L, merged.spentEpochDay)

        val renamedHere = item.copy(text = "mine", spentEpochDay = 3)
        val tickedThere = item.copy(done = true)
        val merged2 = merge(base, SharedState(items = mapOf(item.id to renamedHere)), SharedState(items = mapOf(item.id to tickedThere))).items.getValue(item.id)
        assertEquals("mine", merged2.text)
        assertTrue(merged2.done)
        assertEquals(3L, merged2.spentEpochDay)
    }

    @Test
    fun settings_stricterValueWinsPerField() {
        val base = SharedState(settings = Settings())
        val here = base.copy(settings = Settings(dailyCapMs = 90L * 60_000, unlockWarningMs = 10_000))
        val there = base.copy(settings = Settings(dailyCapMs = 45L * 60_000, unlockWarningMs = 60_000))
        val merged = merge(base, here, there).settings
        assertEquals(45L * 60_000, merged.dailyCapMs)
        assertEquals(60_000L, merged.unlockWarningMs)
        assertEquals(Defaults.SKEW_TOLERANCE_MS, merged.skewToleranceMs)
    }

    @Test
    fun unlockLog_unionOnTheSameDay_newerDayWins() {
        val here = SharedState(unlockEpochDay = 10, unlockLog = mapOf("a@1" to 100L, "b@2" to 50L))
        val there = SharedState(unlockEpochDay = 10, unlockLog = mapOf("a@1" to 100L, "c@3" to 70L))
        assertEquals(mapOf("a@1" to 100L, "b@2" to 50L, "c@3" to 70L), merge(null, here, there).unlockLog)

        val tomorrow = SharedState(unlockEpochDay = 11, unlockLog = mapOf("d@4" to 10L))
        val merged = merge(null, here, tomorrow)
        assertEquals(11L, merged.unlockEpochDay)
        assertEquals(mapOf("d@4" to 10L), merged.unlockLog)
    }

    @Test
    fun allowance_perDevice_newerDayThenMore() {
        val here = SharedState(allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 5), "laptop" to AllowanceUse(t, 9))))
        val there = SharedState(allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 7), "laptop" to AllowanceUse(t - 86_400_000, 30))))
        val merged = merge(null, here, there).allowance.getValue("a1")
        assertEquals(AllowanceUse(t, 7), merged["phone"])
        assertEquals(AllowanceUse(t, 9), merged["laptop"])
    }

    @Test
    fun roundTripsThroughJson() {
        val json = Json { encodeDefaults = true }
        val state = SharedState(
            blocks = mapOf("c1" to checklistBlock("c1").copy(targetDesktopApps = setOf("steam"), editedAtWall = t)),
            items = mapOf("c1-i0" to items("c1", 1).single()),
            credits = mapOf("c1" to credit("c1", t)),
            sessions = mapOf("c2" to session("c2", t)),
            lockdowns = mapOf("l1" to lockdown("l1", t, 60_000)),
            pause = Pause(0, t, Defaults.PAUSE_MS),
            lastPauseEpochDay = 3,
            unlockEpochDay = 3,
            unlockLog = mapOf("c2@$t" to 1_000L),
            allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 5))),
        )
        val decoded = json.decodeFromString(SharedState.serializer(), json.encodeToString(SharedState.serializer(), state))
        assertEquals(state, decoded)
        // Unknown fields from a newer device must not break an older one.
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(SharedState(), lenient.decodeFromString(SharedState.serializer(), """{"somethingNew":1}"""))
    }
}
