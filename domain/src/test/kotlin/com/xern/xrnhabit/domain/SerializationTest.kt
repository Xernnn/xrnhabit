package com.xern.xrnhabit.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app persists the whole [World] as one JSON document; make sure it round-trips. */
class SerializationTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun world_roundTripsThroughJson() {
        val clock = FakeClock()
        val engine = BlockEngine()
        val start = buildWorld(
            listOf(
                checklistBlock("c1", target = "com.insta", required = 2L),
                timerBlock("t1", target = "com.reddit"),
            ),
            mapOf("c1" to items("c1", 3, done = 1)),
            clock,
        )
        // Move it through a few transitions so the harder-to-serialize members are populated.
        var out = engine.reduce(start, BlockEvent.ToggleChecklistItem("c1-i0", true), clock)
        out = engine.reduce(out.world, BlockEvent.ToggleChecklistItem("c1-i1", true), clock)
        out = engine.reduce(out.world, BlockEvent.Redeem("c1"), clock)

        val encoded = json.encodeToString(World.serializer(), out.world)
        val decoded = json.decodeFromString(World.serializer(), encoded)

        assertEquals(out.world, decoded)
    }

    /** A `world.json` written before linked items existed must still load (I23 fields default). */
    @Test
    fun checklistItem_withoutLinkFieldsDecodes() {
        val old = """{"id":"c1-i0","blockId":"c1","text":"Read","order":0,"done":true,"doneAtMonotonic":5}"""

        val item = Json.decodeFromString(ChecklistItem.serializer(), old)

        assertEquals(ChecklistItem("c1-i0", "c1", "Read", 0, done = true, doneAtMonotonic = 5), item)
    }
}
