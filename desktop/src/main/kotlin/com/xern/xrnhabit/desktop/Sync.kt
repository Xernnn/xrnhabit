package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.SharedState
import com.xern.xrnhabit.domain.epochDay
import com.xern.xrnhabit.domain.localDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** How the last attempt went, for the local page. */
@Serializable
data class SyncStatus(val atWallMs: Long, val ok: Boolean, val message: String)

class AuthException : IOException("the server refused the user name or password")

/** Basic-auth JSON over HTTPS to the two sync services. Blocking: call on [Dispatchers.IO]. */
class Http(user: String, password: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    fun send(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(), timeoutS: Long = 20): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutS))
            .header("Authorization", auth)
            .header("X-Device", "laptop")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 401) throw AuthException()
        return response
    }
}

/**
 * The laptop's side of the shared state (contract §2.4), the same rounds as the phone's
 * `SharedSync`: read, [BlockEvent.SharedStateMerged], write back with If-Match. The server is
 * long-polled, so an unlock on the phone shows up here within a second or two.
 */
class SharedSyncClient(
    private val config: Config,
    private val store: Store,
    private val telemetry: FileTelemetry,
    private val scope: CoroutineScope,
) {
    private val http = Http(config.user, config.password)
    private val baseFile = File(config.dataDir, "shared-base.json")
    private val eventsSentFile = File(config.dataDir, "events-sent")
    private var telemetrySizeSent = -1L
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow<SyncStatus?>(null)
    val status: StateFlow<SyncStatus?> = _status.asStateFlow()

    @Serializable
    private data class Base(val etag: String, val state: SharedState)

    fun start() {
        if (!config.syncConfigured) return
        scope.launch(Dispatchers.IO) {
            for (r in requests) {
                try {
                    _status.value = SyncStatus(System.currentTimeMillis(), true, round())
                    runCatching { sendEvents() } // best effort: what doesn't go now goes next round
                } catch (e: Exception) {
                    _status.value = SyncStatus(System.currentTimeMillis(), false, describe(e))
                    delay(5_000)
                    requests.trySend(Unit)
                }
            }
        }
        // Local changes: everything but allowance use goes out at once; that goes with the minute.
        scope.launch {
            store.world
                .map { SharedState.of(it).copy(allowance = emptyMap()) }
                .distinctUntilChanged()
                .collect { shared -> if (loadBase()?.state?.copy(allowance = emptyMap()) != shared) request() }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                if (loadBase()?.state != SharedState.of(store.world.value)) request()
            }
        }
        // Remote changes: a long-poll that answers as soon as another device writes.
        scope.launch(Dispatchers.IO) {
            request()
            while (true) {
                try {
                    val etag = loadBase()?.etag
                    if (etag == null) {
                        delay(2_000)
                        continue
                    }
                    val r = http.send("${config.habitUrl}/state?wait=50", headers = mapOf("If-None-Match" to etag), timeoutS = 65)
                    if (r.statusCode() == 200) {
                        request()
                        delay(1_000) // let the round store the new base before asking again
                    }
                } catch (_: Exception) {
                    // A dropped long-poll isn't a failure by itself, and a later 304 wouldn't
                    // clear one: a round says whether the server is really out of reach.
                    delay(10_000)
                    request()
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private suspend fun round(): String {
        repeat(4) {
            val base = loadBase()
            val local = SharedState.of(store.world.value)
            val cheap = base != null && base.state == local
            val headers = if (cheap) mapOf("If-None-Match" to base!!.etag) else emptyMap()
            val got = withContext(Dispatchers.IO) { http.send("${config.habitUrl}/state", headers = headers) }
            if (got.statusCode() == 304) return "Up to date"
            check(got.statusCode() == 200) { "server answered ${got.statusCode()}" }
            val etag = got.headers().firstValue("ETag").orElse("")
            val remote = json.decodeFromString(SharedState.serializer(), got.body())
            val world = store.dispatchAndGet(BlockEvent.SharedStateMerged(base?.state, remote))
            saveBase(Base(etag, remote)) // it's all in here now; a retry mustn't undo the other side
            val merged = SharedState.of(world)
            if (merged == remote) return "Up to date"
            val put = withContext(Dispatchers.IO) {
                http.send("${config.habitUrl}/state", "PUT", json.encodeToString(SharedState.serializer(), merged), mapOf("If-Match" to etag))
            }
            if (put.statusCode() == 412) return@repeat
            check(put.statusCode() == 200) { "server answered ${put.statusCode()}" }
            saveBase(Base(put.headers().firstValue("ETag").orElse(""), merged))
            return "Sent"
        }
        throw IOException("the server kept changing; trying again")
    }

    /** The laptop's event log newer than what the server has, for the weekly review. */
    private fun sendEvents() {
        val size = telemetry.size()
        if (size == telemetrySizeSent) return
        val since = eventsSentFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0
        val fresh = telemetry.eventsAfter(since)
        if (fresh.isNotEmpty()) {
            val body = JsonObject(mapOf("events" to JsonArray(fresh))).toString()
            val r = http.send("${config.habitUrl}/events", "POST", body)
            check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
            eventsSentFile.writeText(fresh.maxOf { it["at"]!!.jsonPrimitive.long }.toString())
        }
        telemetrySizeSent = size
    }

    private fun loadBase(): Base? = try {
        json.decodeFromString(Base.serializer(), baseFile.readText())
    } catch (_: Exception) {
        null
    }

    private fun saveBase(base: Base) {
        baseFile.parentFile.mkdirs()
        val tmp = File(baseFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Base.serializer(), base))
        tmp.renameTo(baseFile)
    }
}

/**
 * Habit ticks for linked checklist items (I23), from xrnloops-sync's `/api/status` rather than
 * the phone's content provider: today and yesterday, the same four sets `LoopHabits` builds on
 * the phone. Re-read when the habit feed moves (a long-poll), at local midnight, and when an
 * item gets linked. Nothing is sent unless both days were read.
 */
class LoopClient(
    private val config: Config,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val http = Http(config.user, config.password)
    private val _status = MutableStateFlow<SyncStatus?>(null)
    val status: StateFlow<SyncStatus?> = _status.asStateFlow()
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Read the habits again now: an item was just linked. */
    fun kick() {
        kicks.trySend(Unit)
    }

    private fun anyLinked() = store.world.value.checklist.values.any { items -> items.any { it.externalHabitId != null } }

    fun start() {
        if (!config.syncConfigured) return
        scope.launch {
            store.world.map { anyLinked() }.distinctUntilChanged().collect { if (it) kicks.trySend(Unit) }
        }
        scope.launch(Dispatchers.IO) {
            var seq = -1L
            var day = store.clock.epochDay()
            while (true) {
                try {
                    if (!anyLinked()) {
                        kicks.receive()
                        continue
                    }
                    seq = read()
                    day = store.clock.epochDay()
                    // Wait for a change in the feed, a new day, or a kick (the long-poll ends every 50 s).
                    while (store.clock.epochDay() == day && kicks.tryReceive().isFailure) {
                        val r = http.send("${config.loopUrl}/api/changes?since=$seq&wait=50", timeoutS = 65)
                        check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
                        val page = json.parseToJsonElement(r.body()).jsonObject
                        if (page["habits"]!!.jsonArray.isNotEmpty() || page["entries"]!!.jsonArray.isNotEmpty()) break
                        seq = page["seq"]!!.jsonPrimitive.long
                    }
                } catch (e: Exception) {
                    _status.value = SyncStatus(System.currentTimeMillis(), false, describe(e))
                    delay(10_000)
                }
            }
        }
    }

    /** Reads both days and dispatches them; returns the feed position to wait from. */
    private suspend fun read(): Long {
        val today = store.clock.localDate()
        val (seq, todays) = status(today.toString())
        val (_, yesterdays) = status(today.minusDays(1).toString())
        fun ids(habits: List<JsonObject>, key: String) =
            habits.filter { it[key]!!.jsonPrimitive.boolean }.map { it["uuid"]!!.jsonPrimitive.content }.toSet()
        store.dispatchAndGet(
            BlockEvent.ExternalHabitsSynced(
                epochDay = today.toEpochDay(),
                doneHabitIds = ids(todays, "done"),
                doneYesterdayHabitIds = ids(yesterdays, "done"),
                goalMetHabitIds = ids(todays, "goal_met"),
                goalMetYesterdayHabitIds = ids(yesterdays, "goal_met"),
            ),
        )
        _status.value = SyncStatus(System.currentTimeMillis(), true, "Read ${todays.size} habits")
        return seq
    }

    private fun status(date: String): Pair<Long, List<JsonObject>> {
        val r = http.send("${config.loopUrl}/api/status?date=$date")
        check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
        val body = json.parseToJsonElement(r.body()).jsonObject
        val habits = body["habits"]!!.jsonArray.map { it.jsonObject }.filter { it["countable"]?.jsonPrimitive?.boolean != false }
        return body["seq"]!!.jsonPrimitive.long to habits
    }

    /** All habits, for linking checklist items on the local page. */
    fun habits(): List<JsonObject> = status(store.clock.localDate().toString()).second
}

fun describe(e: Exception): String = when (e) {
    is AuthException -> "Wrong user name or password"
    is java.net.ConnectException, is java.net.http.HttpTimeoutException -> "Can't reach the server"
    is IOException -> "Can't reach the server (${e.message ?: e.javaClass.simpleName})"
    else -> "Failed: ${e.message ?: e}"
}
