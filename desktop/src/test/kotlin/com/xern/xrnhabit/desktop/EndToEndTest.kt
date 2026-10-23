package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.desktop.BlockForms.Form
import com.xern.xrnhabit.desktop.BlockForms.LinkedHabit
import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * Two devices sharing blocks through the real servers: `server/xrnhabit-sync.py` and
 * `../xrnloops/server/xrnloops-sync.py`, started on free ports. Both devices are laptop
 * clients ([SharedSyncClient], [LoopClient]); the phone's `SharedSync` makes the same rounds
 * against the same server and merge, so this covers the path an unlock takes between them.
 *
 * Skipped when python3 with bcrypt, or the xrnloops checkout next to this repo, is missing.
 */
class EndToEndTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val habitServer = File(repo, "server/xrnhabit-sync.py")
    private val loopServer = File(repo, "../xrnloops/server/xrnloops-sync.py")
    private val dir = Files.createTempDirectory("xrnhabit-e2e").toFile()
    private val processes = mutableListOf<Process>()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var habitUrl: String
    private lateinit var loopUrl: String

    private class Device(val store: Store)

    private object NoPersist : PersistPort {
        override fun loadWorld() = World()
        override fun save(world: World) = Unit
    }

    @Before
    fun startServers() {
        val hasPython = runCatching {
            ProcessBuilder("python3", "-c", "import bcrypt").start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue("needs python3 with bcrypt", hasPython)
        assumeTrue("needs ../xrnloops", loopServer.isFile)
        val hash = ProcessBuilder("python3", "-c", "import bcrypt; print(bcrypt.hashpw(b'pw', bcrypt.gensalt(4)).decode())")
            .start().inputStream.bufferedReader().readText().trim()
        val users = File(dir, "users").apply { writeText("you:$hash\n") }
        habitUrl = start(habitServer, users, "habit")
        loopUrl = start(loopServer, users, "loop")
    }

    private fun start(script: File, users: File, name: String): String {
        val port = ServerSocket(0).use { it.localPort }
        val data = File(dir, name).apply { mkdirs() }
        processes += ProcessBuilder("python3", script.path, "--port", "$port", "--data", data.path, "--users", users.path)
            .redirectErrorStream(true).redirectOutput(File(dir, "$name.log")).start()
        val url = "http://127.0.0.1:$port"
        await("$name server up") { runCatching { (URI("$url/health").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false) }
        return url
    }

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
        processes.forEach { it.destroy() }
        dir.deleteRecursively()
    }

    /** The real clock, moved to [hour] o'clock local time today. */
    private class ShiftedClock(hour: Int) : Clock {
        private val shift = LocalDate.now().atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
        override fun nowMonotonicMs() = System.nanoTime() / 1_000_000
        override fun nowWallMs() = System.currentTimeMillis() + shift
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }

    private fun device(name: String, clock: Clock = DesktopClock()): Device {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val data = File(dir, name).apply { mkdirs() }
        val config = Config(server = "", user = "you", password = "pw", port = 0, dataDir = data, habitUrl = habitUrl, loopUrl = loopUrl)
        val telemetry = FileTelemetry(File(data, "telemetry.log"))
        val store = Store(BlockEngine(), NoPersist, clock, scope, telemetry)
        SharedSyncClient(config, store, telemetry, scope).start()
        LoopClient(config, store, scope).start()
        return Device(store)
    }

    private fun await(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(100)
        }
    }

    private fun postHabits(body: String) {
        val conn = URI("$loopUrl/api/changes").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("you:pw".toByteArray()))
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        check(conn.responseCode == 200) { "xrnloops-sync answered ${conn.responseCode}" }
    }

    @Test
    fun aBlockMadeOnOneDeviceIsEarnedOnBothAndAnUnlockOnOneOpensBoth() {
        val now = System.currentTimeMillis()
        postHabits(
            """{"habits":[{"uuid":"plank","mtime":$now,"deleted":false,"data":{"name":"Plank","freqNum":1,"freqDen":1,"type":0}}],"entries":[]}""",
        )
        val a = device("a")
        val b = device("b")

        // A makes a block, linked to the habit; B gets it.
        runBlocking {
            a.store.edit { w ->
                (BlockForms.create(w, Form(name = "Games", sites = listOf("example.com"), linked = listOf(LinkedHabit("plank", "Plank"))), a.store.clock) as BlockForms.Result.Ok).world
            }
        }
        val id = a.store.world.value.blocks.single().id
        await("B has the block") { b.store.world.value.block(id) != null }
        assertEquals(setOf("example.com"), b.store.world.value.block(id)!!.targetSites)

        // Ticked in the habits web app: both devices read it and earn.
        postHabits("""{"habits":[],"entries":[{"habit":"plank","date":"${LocalDate.now()}","value":2,"notes":"","mtime":${now + 1}}]}""")
        await("A earned") { a.store.world.value.block(id)?.state == BlockState.EARNED }
        await("B earned") { b.store.world.value.block(id)?.state == BlockState.EARNED }

        // B opens it; A follows, until the same moment, and the habit is spent on both (I24).
        runBlocking { b.store.dispatchAndGet(BlockEvent.Redeem(id)) }
        await("A unlocked") { a.store.world.value.block(id)?.state == BlockState.UNLOCKED }
        assertEquals(b.store.world.value.sessions[id]!!.startedAtWall, a.store.world.value.sessions[id]!!.startedAtWall)
        await("the tick spent on A") { a.store.world.value.items(id).single().spentEpochDay != null }

        // A deletes it; it's gone from B too.
        runBlocking { a.store.edit { w -> BlockForms.delete(w, id) } }
        await("B lost the block") { b.store.world.value.block(id) == null }
    }

    @Test
    fun withThePhonesDayStartingAt3AnOneAmTickCountsForTheDayBefore() {
        val now = System.currentTimeMillis()
        postHabits(
            """{"habits":[{"uuid":"plank","mtime":$now,"deleted":false,"data":{"name":"Plank","freqNum":1,"freqDen":1,"type":0}}],"entries":[]}""",
        )
        postHabits("""{"dayStartHour":3,"habits":[],"entries":[]}""") // what xrnloops sends with "Extend the day" on
        val laptop = device("laptop", ShiftedClock(hour = 1))
        runBlocking {
            laptop.store.edit { w ->
                (BlockForms.create(w, Form(name = "Games", sites = listOf("example.com"), linked = listOf(LinkedHabit("plank", "Plank"))), laptop.store.clock) as BlockForms.Result.Ok).world
            }
        }
        val id = laptop.store.world.value.blocks.single().id
        val yesterday = LocalDate.now().minusDays(1)

        // At 1 am the phone's "today" is still yesterday's date, and its tick lands there.
        postHabits("""{"habits":[],"entries":[{"habit":"plank","date":"$yesterday","value":2,"notes":"","mtime":${now + 1}}]}""")

        await("earned by the 1 am tick") { laptop.store.world.value.block(id)?.state == BlockState.EARNED }
        assertEquals(yesterday.toEpochDay(), laptop.store.world.value.items(id).single().tickEpochDay)
    }
}
