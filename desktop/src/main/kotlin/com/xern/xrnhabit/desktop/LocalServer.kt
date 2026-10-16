package com.xern.xrnhabit.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockEdits
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Defaults
import com.xern.xrnhabit.domain.EnforcementTier
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The daemon's door on `127.0.0.1`: the local page (what's blocked, unlock, laptop setup), the
 * block page blocked apps and sites land on, and the browser extension's calls.
 *
 * Every write needs an `X-Xrnhabit` header. A web page elsewhere can't add one without a CORS
 * preflight, which this server never approves, so only our own page and the extension can
 * change anything; and a request must name this server as its Host (DNS rebinding). (It's a self-control tool, not a lock: anyone at the keyboard can stop it.)
 */
class LocalServer(
    private val config: Config,
    private val store: Store,
    private val foreground: Foreground,
    private val enforcer: Enforcer,
    private val shared: SharedSyncClient,
    private val loop: LoopClient,
) {
    private val guards = ConcurrentHashMap<String, Guard>()

    /** A loosening change waiting out its 60 s (contract §2.3); applied only if confirmed in time. */
    private class Guard(val startedAtMono: Long, val apply: suspend () -> Unit)

    fun start() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 32)
        server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "http").apply { isDaemon = true } }
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                send(ex, 500, """{"error":${json.encodeToString(String.serializer(), e.message ?: e.toString())}}""")
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        // DNS rebinding: a site that points its own name at 127.0.0.1 is same-origin with itself,
        // so it needs no preflight to add the header; but its requests carry its name as Host.
        val host = ex.requestHeaders.getFirst("Host").orEmpty()
        if (host != "127.0.0.1:${config.port}" && host != "localhost:${config.port}") return send(ex, 403, "wrong host")
        val path = ex.requestURI.path
        val query = parseQuery(ex.requestURI.rawQuery)
        if (ex.requestMethod == "OPTIONS") return send(ex, 403, "no") // never approve a cross-origin preflight
        if (ex.requestMethod == "POST" && ex.requestHeaders.getFirst("X-Xrnhabit") == null) return send(ex, 403, "missing X-Xrnhabit")
        when {
            ex.requestMethod == "GET" && path == "/api/state" -> send(ex, 200, json.encodeToString(UiState.serializer(), view()))
            ex.requestMethod == "GET" && path == "/api/habits" -> send(ex, 200, habits())
            ex.requestMethod == "POST" && path == "/api/block" -> send(ex, 200, saveBlock(body(ex)))
            ex.requestMethod == "POST" && path == "/api/block/delete" -> send(ex, 200, deleteBlock(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/item" -> send(ex, 200, item(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "GET" && path == "/api/apps" -> send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DesktopApps.App.serializer()), DesktopApps.all()))
            ex.requestMethod == "GET" && path == "/ext/check" -> {
                val host = query["host"].orEmpty()
                send(ex, 200, siteVerdict(host))
            }
            ex.requestMethod == "POST" && path == "/ext/heartbeat" -> {
                val beat = json.decodeFromString(Foreground.Heartbeat.serializer(), body(ex))
                foreground.onHeartbeat(beat.copy(fromBlockPage = false))
                send(ex, 200, siteVerdict(beat.host.orEmpty()))
            }
            ex.requestMethod == "POST" && path == "/api/heartbeat" -> {
                val beat = json.decodeFromString(Foreground.Heartbeat.serializer(), body(ex))
                foreground.onHeartbeat(beat.copy(fromBlockPage = true))
                send(ex, 200, "{}")
            }
            ex.requestMethod == "POST" && path == "/api/event" -> send(ex, 200, event(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/laptop" -> send(ex, 200, laptopTargets(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/confirm" -> send(ex, 200, confirm(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/sync" -> {
                shared.request()
                send(ex, 200, "{}")
            }
            ex.requestMethod == "GET" -> static(ex, path)
            else -> send(ex, 404, "not found")
        }
    }

    // ---- extension -----------------------------------------------------------------------

    private fun siteVerdict(host: String): String {
        val blocked = host.isNotEmpty() && enforcer.isSiteBlocked(host)
        val site = Sites.normalize(host) ?: host
        return """{"blocked":$blocked,"page":${json.encodeToString(String.serializer(), "${config.localUrl}/block?site=$site")}}"""
    }

    // ---- actions -------------------------------------------------------------------------

    private fun event(body: JsonObject): String {
        val blockId = body["blockId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val event = when (body["type"]?.jsonPrimitive?.contentOrNull) {
            "redeem" -> BlockEvent.Redeem(blockId)
            "lockdown" -> BlockEvent.StartLockdown(blockId)
            "timer" -> BlockEvent.TimerControl(blockId, body["running"]!!.jsonPrimitive.boolean)
            "toggle" -> BlockEvent.ToggleChecklistItem(body["itemId"]!!.jsonPrimitive.content, body["done"]!!.jsonPrimitive.boolean)
            "reset" -> BlockEvent.UserResetEarned(blockId)
            "endPause" -> BlockEvent.EndPause
            // A break lifts blocks: it waits like any loosening (contract §2.3, I25).
            "pause" -> return guarded { store.dispatchAndGet(BlockEvent.StartPause()) }
            else -> return """{"error":"unknown event"}"""
        }
        runBlocking { store.dispatchAndGet(event) }
        return "{}"
    }

    /** Which laptop apps and sites a block covers. Taking any away waits 60 s; a hardcore block only loosens on the phone. */
    private fun laptopTargets(body: JsonObject): String {
        val id = body["blockId"]!!.jsonPrimitive.content
        val apps = body["desktopApps"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.isNotBlank() }.toSet()
        val sites = body["sites"]!!.jsonArray.mapNotNull { Sites.normalize(it.jsonPrimitive.content) }.toSet()
        val old = store.world.value.block(id) ?: return error("That block is gone.")
        val new = old.copy(targetDesktopApps = apps, targetSites = sites)
        return loosening(old, new) { store.edit { w -> w.copy(blocks = w.blocks.map { b -> if (b.id == id) b.copy(targetDesktopApps = apps, targetSites = sites) else b }) } }
    }

    /**
     * Applies a change to [old] at once, unless it loosens the block ([new] null: always): then it
     * waits out the guard (contract §2.3), and a hardcore block only loosens on the phone.
     */
    private fun loosening(old: Block, new: Block?, apply: suspend () -> Unit): String {
        val loosens = new == null || BlockEdits.loosens(old, new)
        if (!loosens || !BlockEdits.guardNeeded(store.engine, store.world.value, old, store.clock)) {
            runBlocking { apply() }
            return "{}"
        }
        if (old.enforcementTier == EnforcementTier.HARDCORE) return error("This is a hardcore block: loosen it on the phone, with its password.")
        return guarded(apply)
    }

    private fun error(text: String) = """{"error":${json.encodeToString(String.serializer(), text)}}"""

    /** The habits a checklist item can be linked to, from xrnloops-sync. */
    private fun habits(): String = try {
        val list = loop.habits().filter { it["archived"]?.jsonPrimitive?.booleanOrNull != true }
            .map { buildJsonObject { put("uuid", it["uuid"]!!.jsonPrimitive.content); put("name", it["name"]!!.jsonPrimitive.content) } }
        JsonArray(list).toString()
    } catch (e: Exception) {
        error("Can't read your habits: ${describe(e)}")
    }

    /** Creates a block, or edits one ([BlockForms]); an edit that loosens it waits. */
    private fun saveBlock(raw: String): String {
        val form = try {
            json.decodeFromString(BlockForms.Form.serializer(), raw)
        } catch (_: Exception) {
            return error("That form didn't make sense.")
        }
        val world = store.world.value
        if (form.id == null) {
            (BlockForms.create(world, form, store.clock) as? BlockForms.Result.Invalid)?.let { return error(it.reason) }
            // Built again inside the edit, against the world as it is by then.
            runBlocking { store.edit { w -> (BlockForms.create(w, form, store.clock) as? BlockForms.Result.Ok)?.world ?: w } }
            if (form.linked.isNotEmpty()) loop.kick()
            return "{}"
        }
        val old = world.block(form.id) ?: return error("That block is gone.")
        val new = when (val r = BlockForms.edit(world, form)) {
            is BlockForms.Result.Invalid -> return error(r.reason)
            is BlockForms.Result.Ok -> r.block
        }
        return loosening(old, new) { store.edit { w -> (BlockForms.edit(w, form) as? BlockForms.Result.Ok)?.world ?: w } }
    }

    /** Deleting a block always loosens it. */
    private fun deleteBlock(body: JsonObject): String {
        val id = body["blockId"]!!.jsonPrimitive.content
        val old = store.world.value.block(id) ?: return "{}"
        return loosening(old, null) { store.edit { w -> BlockForms.delete(w, id) } }
    }

    /** A checklist item added, linked to a habit (both at once), or removed (that waits). */
    private fun item(body: JsonObject): String {
        val blockId = body["blockId"]!!.jsonPrimitive.content
        val old = store.world.value.block(blockId) ?: return error("That block is gone.")
        if (old.mode != BlockMode.HABIT || old.trigger.type == TriggerType.TIMER) return error("This block doesn't unlock with tasks.")
        return when (body["op"]?.jsonPrimitive?.contentOrNull) {
            "add" -> {
                val text = body["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return error("Write the task first.")
                runBlocking { store.edit { w -> BlockForms.addItem(w, blockId, text) } }
                "{}"
            }
            "link" -> {
                val habit = json.decodeFromJsonElement(BlockForms.LinkedHabit.serializer(), body["habit"]!!)
                runBlocking { store.edit { w -> BlockForms.linkHabit(w, blockId, habit) } }
                loop.kick()
                "{}"
            }
            "remove" -> {
                val itemId = body["itemId"]!!.jsonPrimitive.content
                loosening(old, null) { store.edit { w -> BlockForms.removeItem(w, itemId) } }
            }
            else -> error("unknown change")
        }
    }

    private fun guarded(apply: suspend () -> Unit): String {
        val token = UUID.randomUUID().toString()
        guards[token] = Guard(store.clock.nowMonotonicMs(), apply)
        return """{"token":"$token","waitMs":$GUARD_MS}"""
    }

    private fun confirm(body: JsonObject): String {
        val token = body["token"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"no token"}"""
        if (body["cancel"]?.jsonPrimitive?.booleanOrNull == true) {
            guards.remove(token)
            return "{}"
        }
        val guard = guards[token] ?: return """{"error":"That wait was cancelled. Start again."}"""
        val waited = store.clock.nowMonotonicMs() - guard.startedAtMono
        if (waited < GUARD_MS) return """{"error":"Not yet: ${(GUARD_MS - waited) / 1000} s to go."}"""
        guards.remove(token)
        if (waited > GUARD_MS + 5 * 60_000) return """{"error":"That wait ran out. Start again."}"""
        runBlocking { guard.apply() }
        return "{}"
    }

    // ---- view ----------------------------------------------------------------------------

    @Serializable
    data class UiItem(val id: String, val text: String, val done: Boolean, val linked: Boolean, val yesterday: Boolean)

    @Serializable
    data class UiBlock(
        val id: String,
        val name: String,
        val mode: String,
        val hardcore: Boolean,
        val enabled: Boolean,
        val state: String,
        val blocking: Boolean,
        val phoneApps: Int,
        val sites: List<String>,
        val desktopApps: List<String>,
        val trigger: String,
        val requiredMs: Long,
        val requiredItems: Long,
        val combineOr: Boolean,
        val unlockMs: Long,
        val creditExpiresAtWall: Long?,
        val openUntilWall: Long?,
        val lockdownRemainingMs: Long,
        val lockdownMs: Long,
        val allowanceMs: Long,
        val allowanceUsedMs: Long,
        val timerMs: Long,
        val timerRunning: Boolean,
        val items: List<UiItem>,
        val redeemRejection: String?,
        val scheduleText: String?,
        val scheduleDays: List<Int>,
        val scheduleStart: Int?,
        val scheduleEnd: Int?,
    )

    @Serializable
    data class UiState(
        val nowWall: Long,
        val blocks: List<UiBlock>,
        val pauseRemainingMs: Long,
        val canPause: Boolean,
        val unlockUsedMs: Long,
        val dailyCapMs: Long,
        val sync: SyncStatus?,
        val loop: SyncStatus?,
        val syncConfigured: Boolean,
        val habitsUrl: String?,
        val kwinSeenAgoMs: Long?,
        val extensionMissing: Boolean,
        val blockedApps: List<String>,
        val blockedSites: List<String>,
        val appNames: Map<String, String>,
    )

    private fun view(): UiState {
        val world = store.world.value
        val engine = store.engine
        val clock = store.clock
        val blocks = world.blocks.map { b -> uiBlock(world, b) }
        val appIds = world.blocks.flatMap { it.targetDesktopApps }.toSet()
        return UiState(
            nowWall = clock.nowWallMs(),
            blocks = blocks,
            pauseRemainingMs = engine.pauseRemainingMs(world, clock),
            canPause = engine.canPause(world, clock),
            unlockUsedMs = engine.unlockUsedTodayMs(world, clock),
            dailyCapMs = world.settings.dailyCapMs,
            sync = shared.status.value,
            loop = loop.status.value,
            syncConfigured = config.syncConfigured,
            habitsUrl = if (config.syncConfigured) "${config.loopUrl}/" else null,
            kwinSeenAgoMs = enforcer.kwinSeenAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            extensionMissing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() },
            blockedApps = world.appliedShield.blockedPackages.filter { it in appIds },
            blockedSites = world.appliedShield.blockedSites.toList(),
            appNames = appIds.associateWith(DesktopApps::name),
        )
    }

    private fun uiBlock(world: World, b: Block): UiBlock {
        val engine = store.engine
        val clock = store.clock
        val credit = world.credits[b.id]
        val session = world.sessions[b.id]
        val progress = world.progress[b.id]
        val w = b.schedule.activeWindow
        val days = b.schedule.activeDays
        val scheduleText = when {
            w == null && days.size == 7 -> null
            w == null -> "On ${dayNames(days)}"
            else -> "%02d:%02d–%02d:%02d%s".format(
                w.startMinuteOfDay / 60, w.startMinuteOfDay % 60, w.endMinuteOfDay / 60, w.endMinuteOfDay % 60,
                if (days.size == 7) "" else ", ${dayNames(days)}",
            )
        }
        return UiBlock(
            id = b.id,
            name = b.name,
            mode = b.mode.name,
            hardcore = b.enforcementTier == EnforcementTier.HARDCORE,
            enabled = b.enabled,
            state = b.state.name,
            blocking = engine.isBlocking(world, b, clock),
            phoneApps = b.targetPackages.size,
            sites = b.targetSites.sorted(),
            desktopApps = b.targetDesktopApps.sorted(),
            trigger = b.trigger.type.name,
            requiredMs = if (b.trigger.type == TriggerType.CHECKLIST) 0 else b.trigger.requiredAmount,
            requiredItems = if (b.trigger.type == TriggerType.CHECKLIST) b.trigger.requiredAmount else Defaults.ALL_ITEMS,
            combineOr = b.trigger.combineMode.name == "OR",
            unlockMs = b.reward.unlockDurationMs,
            creditExpiresAtWall = credit?.expiresAtWall,
            openUntilWall = session?.let { it.startedAtWall + it.durationMs },
            lockdownRemainingMs = engine.lockdownRemainingMs(world, b.id, clock),
            lockdownMs = b.lockdownMs,
            allowanceMs = b.dailyAllowanceMs,
            allowanceUsedMs = if (b.mode == BlockMode.ALLOWANCE) engine.allowanceUsedMs(world, b, clock) else 0,
            timerMs = progress?.accumulatedMs ?: 0,
            timerRunning = progress?.timerRunning == true,
            items = world.items(b.id).map { UiItem(it.id, it.text, it.done, it.externalHabitId != null, it.linkedDay.name == "YESTERDAY") },
            redeemRejection = if (b.state == BlockState.EARNED) engine.redeemRejection(world, b.id, clock)?.name else null,
            scheduleText = scheduleText,
            scheduleDays = days.sorted(),
            scheduleStart = w?.startMinuteOfDay,
            scheduleEnd = w?.endMinuteOfDay,
        )
    }

    private fun dayNames(days: Set<Int>) = days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }

    // ---- plumbing ------------------------------------------------------------------------

    private fun static(ex: HttpExchange, path: String) {
        val name = when (path) {
            "/", "/block" -> "index.html"
            else -> path.removePrefix("/")
        }
        if (name.contains("..") || name.contains('/')) return send(ex, 404, "not found")
        val bytes = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: return send(ex, 404, "not found")
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun body(ex: HttpExchange): String = ex.requestBody.use { it.readBytes().decodeToString() }

    private fun send(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", if (text.startsWith("{") || text.startsWith("[")) "application/json" else "text/plain; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    companion object {
        const val GUARD_MS = 60_000L
    }
}
