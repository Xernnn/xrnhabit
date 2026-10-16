package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class DesktopTest {

    private class TestClock(var mono: Long = 1_000_000) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = 1_800_000_000_000 + mono
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private object NoPersist : PersistPort {
        override fun loadWorld() = World()
        override fun save(world: World) = Unit
    }

    private fun foreground(clock: TestClock = TestClock()) =
        Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)))

    private fun Foreground.current(): Pair<String?, String?>? = sent

    @Test
    fun focusedTabSpeaksForTheForeground_unfocusedOneDoesNot() {
        val fg = foreground()
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(host = "www.youtube.com", focused = true))
        assertEquals("helium-browser" to "youtube.com", fg.current())

        // A second, visible but unfocused window reports in: it mustn't take over.
        fg.onHeartbeat(Foreground.Heartbeat(host = "docs.example.com", focused = false))
        assertEquals("helium-browser" to "youtube.com", fg.current())

        // The focused tab loses focus: nothing site-wise is in front any more.
        fg.onHeartbeat(Foreground.Heartbeat(host = "www.youtube.com", focused = false))
        assertEquals("helium-browser" to null, fg.current())
    }

    @Test
    fun switchingToAnotherAppDropsTheTab() {
        val fg = foreground()
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(host = "youtube.com", focused = true))
        fg.active = AppWindow("org.kde.dolphin", "dolphin", 11)
        fg.update()
        assertEquals("org.kde.dolphin" to null, fg.current())
    }

    @Test
    fun blockPageStandsInForTheBlockedAppAndLockMeansNothing() {
        val clock = TestClock()
        val fg = foreground(clock)
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(app = "steam", focused = true, fromBlockPage = true))
        assertEquals("steam" to null, fg.current())

        fg.locked = true
        fg.update()
        assertEquals(null to null, fg.current())

        fg.locked = false
        clock.mono += Foreground.FRESH_MS + 1 // the page went quiet
        fg.update()
        assertEquals("helium-browser" to null, fg.current())
    }

    @Test
    fun extensionMissingOnlyWhenABrowserIsInFront() {
        val clock = TestClock()
        val fg = foreground(clock)
        clock.mono += 60_000
        fg.active = AppWindow("org.kde.kate", "kate", 5)
        assertFalse(fg.extensionMissing())
        fg.active = AppWindow("helium-browser", "helium", 10)
        assertTrue(fg.extensionMissing())
        fg.onHeartbeat(Foreground.Heartbeat(host = "example.com", focused = true))
        assertFalse(fg.extensionMissing())
    }

    @Test
    fun configReadsPasswordFileAndDerivesUrls() {
        val dir = Files.createTempDirectory("xrnhabit").toFile()
        val pw = File(dir, "pw").apply { writeText("secret\n") }
        val props = File(dir, "desktop.properties").apply {
            writeText("server=https://example.org/\nuser=you\npasswordFile=${pw.path}\nport=40000\n")
        }
        val config = Config.load(props)
        assertEquals("secret", config.password)
        assertEquals("https://example.org/xrnhabit", config.habitUrl)
        assertEquals("https://example.org/xrnloops", config.loopUrl)
        assertEquals("http://127.0.0.1:40000", config.localUrl)
        assertTrue(config.syncConfigured)
        assertTrue(config.openBlockPage)
    }

    @Test
    fun windowsWithoutADesktopFileFallBackToTheirClass() {
        assertEquals("org.kde.kcalc", DesktopApps.resolve("org.kde.kcalc.desktop", "kcalc"))
        assertEquals(null, DesktopApps.resolve("", ""))
        assertTrue(DesktopApps.resolve("", "SomeGame")!!.isNotEmpty())
    }
}
