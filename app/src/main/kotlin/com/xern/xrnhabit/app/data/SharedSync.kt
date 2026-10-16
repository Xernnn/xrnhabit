package com.xern.xrnhabit.app.data

import android.content.Context
import android.os.PowerManager
import android.util.Base64
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.SharedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where xrnhabit-sync lives and how to log in. Sync is off until all three are set. */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val url: String get() = prefs.getString(KEY_URL, "").orEmpty().trim().trimEnd('/')
    val user: String get() = prefs.getString(KEY_USER, "").orEmpty().trim()
    val password: String get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
    val isConfigured: Boolean get() = url.startsWith("http") && user.isNotEmpty() && password.isNotEmpty()

    /** The newest event already sent to the server (wall ms); later ones go with the next round. */
    var eventsSentUntil: Long
        get() = prefs.getLong(KEY_EVENTS_SENT, 0)
        set(value) = prefs.edit { putLong(KEY_EVENTS_SENT, value) }

    fun save(url: String, user: String, password: String) = prefs.edit {
        putString(KEY_URL, url.trim())
        putString(KEY_USER, user.trim())
        putString(KEY_PASSWORD, password)
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_EVENTS_SENT = "events_sent_until"
    }
}

/** How the last round went, for the Settings screen. */
data class SharedSyncStatus(val atWallMs: Long, val ok: Boolean, val message: String)

/**
 * Keeps this phone's part of the [SharedState] in step with xrnhabit-sync on the home server
 * (`server/xrnhabit-sync.py`), so an unlock, a break or a lockdown started on the laptop holds
 * here too (contract §2.4).
 *
 * A round: read the server's copy → [BlockEvent.SharedStateMerged] (the engine merges three ways
 * against [Base], what this phone last agreed with the server, and adopts the result under the
 * store's mutex) → write the merged state back with If-Match; on 412 someone wrote first, so go
 * again. Right after adopting, the base becomes the server's copy: it is all in here now, so a
 * retry can't mistake the other device's changes for ours and undo them.
 *
 * Rounds run when the shared part of the world changes (allowance use only with the periodic
 * check, or every tick in a target would be a round), on service start and unlock, when the
 * shield appears, when the app opens, and every minute while the screen is on (a 304 when
 * nothing changed).
 */
class SharedSync(
    private val context: Context,
    private val store: WorldStore,
    private val telemetry: LocalTelemetry,
    private val scope: CoroutineScope,
) {
    val settings = SyncSettings(context)

    private val baseFile = AtomicFile(File(context.filesDir, "shared-base.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var pending: Job? = null
    private var telemetrySizeSent = -1L

    private val _status = MutableStateFlow<SharedSyncStatus?>(null)
    val status: StateFlow<SharedSyncStatus?> = _status.asStateFlow()

    /** When rounds started failing, for the "not syncing" alert; null while they work. */
    private val _failingSince = MutableStateFlow<Long?>(null)
    val failingSince: StateFlow<Long?> = _failingSince.asStateFlow()

    @Serializable
    private data class Base(val etag: String, val state: SharedState)

    fun start() {
        scope.launch {
            store.world
                .map { SharedState.of(it).copy(allowance = emptyMap()) }
                .distinctUntilChanged()
                .collect { shared ->
                    if (loadBase()?.state?.copy(allowance = emptyMap()) != shared) request(CHANGE_DEBOUNCE_MS)
                }
        }
        scope.launch {
            val power = context.getSystemService(PowerManager::class.java)
            while (true) {
                delay(PERIOD_MS)
                if (power.isInteractive) request(0)
            }
        }
    }

    /**
     * A round after [delayMs], replacing one still waiting. A round already under way finishes:
     * stopped between adopting the server's copy and saving it as the base, the next round
     * would take the other device's deletions for changes of ours and undo them.
     */
    @Synchronized
    fun request(delayMs: Long = 0): Job? {
        if (!settings.isConfigured) return null
        pending?.cancel()
        return scope.launch {
            delay(delayMs)
            withContext(NonCancellable) { syncNow() }
        }.also { pending = it }
    }

    /** Runs a round now; true if it worked. */
    suspend fun syncNow(): Boolean {
        if (!settings.isConfigured) return false
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                try {
                    val message = round()
                    _status.value = SharedSyncStatus(now, true, message)
                    _failingSince.value = null
                    runCatching { sendEvents() } // best effort: what doesn't go now goes next round
                    true
                } catch (e: Exception) {
                    _status.value = SharedSyncStatus(now, false, describe(e))
                    if (_failingSince.value == null) _failingSince.value = now
                    false
                }
            }
        }
    }

    /**
     * While a block screen is up: a long-poll that answers as soon as another device writes, so
     * an unlock redeemed on the laptop lifts the shield within a second or two instead of at the
     * next minute check. Runs until cancelled; the request in flight then finishes on its own
     * (within [WATCH_WAIT_S]).
     */
    suspend fun watch() {
        while (currentCoroutineContext().isActive) {
            val etag = loadBase()?.etag
            if (!settings.isConfigured || etag == null) {
                delay(WATCH_RETRY_MS)
                continue
            }
            val changed = try {
                withContext(Dispatchers.IO) {
                    Api(settings.url, settings.user, settings.password).get(etag, waitS = WATCH_WAIT_S) != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(WATCH_RETRY_MS) // the next round reports what's wrong
                false
            }
            // Through request(): a round must not be cut short (see there). Waiting for it
            // also moves the base on, so the next long-poll doesn't answer at once.
            if (changed) {
                request(0)?.join()
                if (_status.value?.ok == false) delay(WATCH_RETRY_MS)
            }
        }
    }

    private suspend fun round(): String {
        val api = Api(settings.url, settings.user, settings.password)
        repeat(MAX_TRIES) {
            val base = loadBase()
            val local = SharedState.of(store.world.value)
            // Nothing of ours to send: a conditional read, usually a bodyless 304.
            val cheap = base != null && base.state == local
            val remote = api.get(if (cheap) base!!.etag else null) ?: return "Up to date"
            val world = store.dispatchAndGet(BlockEvent.SharedStateMerged(base?.state, remote.state))
            saveBase(Base(remote.etag, remote.state))
            val merged = SharedState.of(world)
            if (merged == remote.state) return "Up to date"
            val etag = api.put(merged, remote.etag) ?: return@repeat // 412: someone wrote first
            saveBase(Base(etag, merged))
            return "Sent"
        }
        throw IOException("the server kept changing; will try again")
    }

    /**
     * This phone's event log newer than what the server has (blocked, habit done, unlock used),
     * for the weekly review in the habits web app. Apps go with their names, which the server
     * can't look up.
     */
    private fun sendEvents() {
        val size = telemetry.size()
        if (size == telemetrySizeSent) return
        val since = settings.eventsSentUntil
        val fresh = telemetry.events().filter { it.atWallMs > since }
        if (fresh.isNotEmpty()) {
            val names = HashMap<String, String>() // the first upload carries the whole log
            val events = JsonArray(
                fresh.map { e ->
                    buildJsonObject {
                        put("at", e.atWallMs)
                        when (e) {
                            is TelemetryEvent.Blocked -> {
                                put("t", "blocked")
                                put("target", e.pkg)
                                put("name", names.getOrPut(e.pkg) { InstalledApps.labelFor(context, e.pkg) })
                            }
                            is TelemetryEvent.HabitDone -> {
                                put("t", "habit")
                                put("block", e.blockId)
                            }
                            is TelemetryEvent.UnlockUsed -> {
                                put("t", "unlock")
                                put("block", e.blockId)
                                put("ms", e.ms)
                            }
                        }
                    }
                },
            )
            Api(settings.url, settings.user, settings.password).postEvents(JsonObject(mapOf("events" to events)).toString())
            settings.eventsSentUntil = fresh.maxOf { it.atWallMs }
        }
        telemetrySizeSent = size
    }

    private fun loadBase(): Base? = try {
        json.decodeFromString(Base.serializer(), baseFile.readFully().decodeToString())
    } catch (_: FileNotFoundException) {
        null
    } catch (_: Exception) {
        null // unreadable: the next round merges without one, which only skips deletions once
    }

    private fun saveBase(base: Base) {
        val out = baseFile.startWrite()
        try {
            out.write(json.encodeToString(Base.serializer(), base).encodeToByteArray())
            baseFile.finishWrite(out)
        } catch (e: Throwable) {
            baseFile.failWrite(out)
            throw e
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is AuthException -> "Wrong user name or password"
        is IOException -> "Can't reach the server (${e.message})"
        else -> "Failed: $e"
    }

    private class AuthException : IOException("login refused")

    private class Remote(val etag: String, val state: SharedState)

    /** xrnhabit-sync's `/state`. Blocking; called on [Dispatchers.IO]. */
    private inner class Api(private val baseUrl: String, user: String, password: String) {
        private val auth = "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

        /**
         * The server's copy, or null when it still is [ifNoneMatch]. With [waitS], the server
         * holds a 304 back for up to that long, answering as soon as the copy changes.
         */
        fun get(ifNoneMatch: String?, waitS: Int = 0): Remote? {
            val conn = open("GET", if (waitS > 0) "?wait=$waitS" else "")
            if (waitS > 0) conn.readTimeout = (waitS + 15) * 1000
            try {
                ifNoneMatch?.let { conn.setRequestProperty("If-None-Match", it) }
                val code = conn.responseCode
                check(conn, code)
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                return Remote(conn.getHeaderField("ETag").orEmpty(), json.decodeFromString(SharedState.serializer(), text))
            } finally {
                conn.disconnect()
            }
        }

        /** The new ETag, or null if the server's copy moved on since [ifMatch] (412). */
        fun put(state: SharedState, ifMatch: String): String? {
            val conn = open("PUT")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("If-Match", ifMatch)
                conn.outputStream.use { it.write(json.encodeToString(SharedState.serializer(), state).encodeToByteArray()) }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_PRECON_FAILED) return null
                check(conn, code)
                return conn.getHeaderField("ETag").orEmpty()
            } finally {
                conn.disconnect()
            }
        }

        /** POST /events: our part of the review log. */
        fun postEvents(body: String) {
            val conn = open("POST", path = "/events")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.encodeToByteArray()) }
                check(conn, conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }

        private fun open(method: String, query: String = "", path: String = "/state"): HttpURLConnection =
            (URL("$baseUrl$path$query").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", auth)
                setRequestProperty("X-Device", "phone")
            }

        private fun check(conn: HttpURLConnection, code: Int) {
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
            if (code != HttpURLConnection.HTTP_NOT_MODIFIED && code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("server answered $code ${text.take(120)}")
            }
        }
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MS = 1_000L
        const val PERIOD_MS = 60_000L
        const val MAX_TRIES = 4
        const val TIMEOUT_MS = 15_000
        const val WATCH_WAIT_S = 25
        const val WATCH_RETRY_MS = 5_000L
    }
}
