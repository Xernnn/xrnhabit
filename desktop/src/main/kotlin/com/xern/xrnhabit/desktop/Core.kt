package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.Effect
import com.xern.xrnhabit.domain.PersistPort
import com.xern.xrnhabit.domain.ShieldPlan
import com.xern.xrnhabit.domain.ShieldPort
import com.xern.xrnhabit.domain.Telemetry
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.domain.applyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.Properties

/**
 * `~/.config/xrnhabit/desktop.properties`:
 *
 *     server=https://your-server         # both sync services hang off it: /xrnhabit, /xrnloops
 *     user=you
 *     passwordFile=~/.config/xrnhabit/password   # a file holding just the password; or password=...
 *     port=47321                         # the local page and the browser extension's door
 *     # habitUrl=… / loopUrl=…           # only if the two services don't live at server/xrnhabit, server/xrnloops
 *     # openBlockPage=false              # don't open the block page when a blocked app is closed
 *
 * Without a server the daemon still enforces, just on its own.
 */
data class Config(
    val server: String,
    val user: String,
    val password: String,
    val port: Int,
    val dataDir: File,
    val habitUrl: String = "$server/xrnhabit",
    val loopUrl: String = "$server/xrnloops",
    val openBlockPage: Boolean = true,
) {
    val syncConfigured: Boolean get() = habitUrl.startsWith("http") && user.isNotEmpty() && password.isNotEmpty()
    val localUrl: String get() = "http://127.0.0.1:$port"

    companion object {
        const val DEFAULT_PORT = 47321
        private val home: String = System.getProperty("user.home")

        fun file(): File = System.getenv("XRNHABIT_CONFIG")?.let(::File)
            ?: File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "xrnhabit/desktop.properties")

        fun load(from: File = file()): Config {
            val props = Properties()
            from.takeIf { it.isFile }?.reader()?.use(props::load)
            fun path(p: String) = File(p.replaceFirst(Regex("^~(?=/|$)"), home))
            val password = props.getProperty("password")
                ?: props.getProperty("passwordFile")?.let { f -> path(f).takeIf { it.isFile }?.readText()?.trim() }
                ?: ""
            val dataHome = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            val server = props.getProperty("server", "").trim().trimEnd('/')
            return Config(
                server = server,
                user = props.getProperty("user", "").trim(),
                password = password,
                port = props.getProperty("port")?.toIntOrNull() ?: DEFAULT_PORT,
                dataDir = path(props.getProperty("dataDir") ?: "$dataHome/xrnhabit"),
                habitUrl = props.getProperty("habitUrl")?.trim()?.trimEnd('/') ?: "$server/xrnhabit",
                loopUrl = props.getProperty("loopUrl")?.trim()?.trimEnd('/') ?: "$server/xrnloops",
                openBlockPage = props.getProperty("openBlockPage")?.trim() != "false",
            )
        }
    }
}

/**
 * Wall time is the system clock; "monotonic" is CLOCK_BOOTTIME from `/proc/uptime`, which,
 * like Android's `elapsedRealtime`, keeps counting while the laptop sleeps, so a lid closed on
 * an unlock doesn't stretch it.
 */
class DesktopClock : Clock {
    private val uptime = File("/proc/uptime")

    override fun nowMonotonicMs(): Long = try {
        (uptime.readText().substringBefore(' ').toDouble() * 1000).toLong()
    } catch (_: Exception) {
        System.nanoTime() / 1_000_000
    }

    override fun nowWallMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The whole [World] as one JSON file, replaced atomically (written, synced, then moved over).
 * Saves that only move timer bookkeeping forward are held back for up to 30 s, as on the phone:
 * the 1 s tick would otherwise write the disk every second. An unreadable file is kept aside
 * rather than overwritten.
 */
class FilePersist(private val file: File, private val clock: Clock) : PersistPort {
    private var saved: World? = null
    private var savedAtMono = 0L
    private var pending: World? = null

    @Synchronized
    override fun loadWorld(): World {
        val world = if (!file.exists()) World() else try {
            json.decodeFromString(World.serializer(), file.readText())
        } catch (e: Exception) {
            val copy = File(file.parentFile, "world.unreadable-${System.currentTimeMillis()}.json")
            file.copyTo(copy, overwrite = true)
            System.err.println("world.json unreadable ($e); kept as ${copy.name}, starting empty")
            World()
        }
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        return world
    }

    @Synchronized
    override fun save(world: World) {
        val last = saved
        val bookkeepingOnly = last != null && world.withoutBookkeeping() == last.withoutBookkeeping()
        if (bookkeepingOnly && clock.nowMonotonicMs() - savedAtMono < 30_000) {
            pending = world
            return
        }
        write(world)
    }

    @Synchronized
    fun flush() {
        pending?.let(::write)
    }

    private fun write(world: World) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(json.encodeToString(World.serializer(), world).encodeToByteArray()))
            ch.force(true)
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("can't replace $file", e)
        }
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        pending = null
    }

    private fun World.withoutBookkeeping(): World = copy(
        progress = progress.mapValues { (_, p) -> p.copy(accumulatedMs = 0, lastUpdated = 0) },
        foregroundPkg = null,
        foregroundSite = null,
    )
}

object NoTelemetry : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = Unit
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = Unit
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) = Unit
}

/**
 * The laptop's event log, in the phone's format (`LocalTelemetry`): a blocked app opened, a
 * habit done, an unlock used, one `;`-separated line each. [SharedSyncClient] sends new lines
 * to the server for the weekly review in the habits web app.
 */
class FileTelemetry(private val file: File) : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = append("blocked;$pkg;$atWallMs")
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = append("habit;$blockId;$atWallMs")
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) =
        append("unlock;$blockId;$ms;$atWallMs;$startedAtWall")

    @Synchronized
    private fun append(line: String) {
        runCatching {
            file.parentFile.mkdirs()
            file.appendText(line + "\n")
        }
    }

    /** The log's size in bytes, a cheap "anything new?" check. */
    fun size(): Long = file.length()

    /** Events after [sinceMs] as the server takes them (`POST /events`), oldest first. */
    @Synchronized
    fun eventsAfter(sinceMs: Long): List<JsonObject> = lines().mapNotNull { line ->
        val f = line.split(';')
        when (f.firstOrNull()) {
            "blocked" -> f.getOrNull(2)?.toLongOrNull()?.let { at ->
                buildJsonObject {
                    put("t", "blocked")
                    put("at", at)
                    put("target", f[1])
                    put("name", DesktopApps.name(f[1]))
                }
            }
            "habit" -> f.getOrNull(2)?.toLongOrNull()?.let { at ->
                buildJsonObject {
                    put("t", "habit")
                    put("at", at)
                    put("block", f[1])
                }
            }
            "unlock" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                val start = f.getOrNull(4)?.toLongOrNull() ?: 0
                if (ms == null || at == null) {
                    null
                } else {
                    buildJsonObject {
                        put("t", "unlock")
                        put("at", at)
                        put("block", f[1])
                        put("ms", ms)
                        if (start > 0) put("start", start)
                    }
                }
            }
            else -> null
        }
    }.filter { it["at"]!!.jsonPrimitive.long > sinceMs }

    /** Drops lines older than [keepDays] (the server keeps the same 120). */
    @Synchronized
    fun prune(nowWallMs: Long, keepDays: Int = 120) {
        val cutoff = nowWallMs - keepDays * 86_400_000L
        val lines = lines()
        val kept = lines.filter { line -> eventTime(line)?.let { it >= cutoff } != false }
        if (kept.size == lines.size) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(kept.joinToString("") { it + "\n" })
        tmp.renameTo(file)
    }

    private fun lines(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())

    /** When a line's event happened: the third field, or the fourth for an unlock. */
    private fun eventTime(line: String): Long? {
        val f = line.split(';')
        return (if (f.firstOrNull() == "unlock") f.getOrNull(3) else f.getOrNull(2))?.toLongOrNull()
    }
}

/**
 * Serializes every change to the [World] through one mutex, as the phone's `WorldStore` does,
 * so persist-before-shield (I7) holds whoever calls: the tick, D-Bus, the local page, sync.
 */
class Store(
    val engine: BlockEngine,
    private val persist: PersistPort,
    val clock: Clock,
    private val scope: CoroutineScope,
    private val telemetry: Telemetry = NoTelemetry,
) {
    @Volatile
    var shield: ShieldPort = object : ShieldPort {
        override fun apply(plan: ShieldPlan) = Unit
    }

    private val _world = MutableStateFlow(persist.loadWorld())
    val world: StateFlow<World> = _world.asStateFlow()
    private val mutex = Mutex()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 32)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: BlockEvent) {
        scope.launch { dispatchAndGet(event) }
    }

    suspend fun dispatchAndGet(event: BlockEvent): World = mutex.withLock {
        val outcome = engine.reduce(_world.value, event, clock)
        applyOutcome(outcome, persist, shield, telemetry, clock.nowWallMs())
        _world.value = outcome.world
        outcome.effects.forEach { _effects.tryEmit(it) }
        outcome.world
    }

    /** A structural edit; a block whose settings change is stamped, so the later edit wins (contract §2.4). */
    suspend fun edit(transform: (World) -> World): World = mutex.withLock {
        val before = _world.value
        val now = clock.nowWallMs()
        val old = before.blocks.associateBy { it.id }
        val edited = transform(before).let { w ->
            w.copy(blocks = w.blocks.map { b ->
                val was = old[b.id]
                if (was == null || was.copy(state = b.state, editedAtWall = b.editedAtWall) != b) b.copy(editedAtWall = now) else b
            })
        }
        val plan = engine.shieldPlan(edited, clock)
        val next = edited.copy(appliedShield = plan)
        persist.save(next)
        shield.apply(plan)
        _world.value = next
        next
    }
}
