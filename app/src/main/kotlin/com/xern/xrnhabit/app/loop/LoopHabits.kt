package com.xern.xrnhabit.app.loop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.xern.xrnhabit.app.data.WorldStore
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.localDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Copy of `org.isoron.uhabits.xrnhabit.XrnhabitContract` in xrnloops, our Loop fork (`../xrnloops`).
 * The two apps only share these strings; change both together.
 */
object LoopContract {
    const val LOOP_PACKAGE = "com.xern.xrnloops"
    const val AUTHORITY = "$LOOP_PACKAGE.provider"

    /** Signature permission both apps declare: only apps signed with the same key can talk. */
    const val PERMISSION = "com.xern.xrnhabit.permission.HABITS"
    const val ACTION_HABITS_CHANGED = "com.xern.xrnhabit.action.HABITS_CHANGED"

    /** Opens one habit's page in xrnloops, where today or yesterday can be ticked. */
    const val ACTION_OPEN_HABIT = "com.xern.xrnhabit.action.OPEN_HABIT"
    const val EXTRA_HABIT_UUID = "uuid"

    const val PATH_HABITS = "habits"

    /** `call(AUTHORITY, METHOD_SYNC)`: xrnloops syncs with the home server first (a few seconds at most). */
    const val METHOD_SYNC = "sync"

    /** In [METHOD_SYNC]'s result: sync is set up in xrnloops, and this attempt worked. */
    const val KEY_CONFIGURED = "configured"
    const val KEY_SYNCED = "synced"
    const val PARAM_DATE = "date"
    const val COLUMN_UUID = "uuid"
    const val COLUMN_NAME = "name"
    const val COLUMN_POSITION = "position"
    const val COLUMN_ARCHIVED = "archived"
    const val COLUMN_COUNTABLE = "countable"
    const val COLUMN_DONE = "done"

    /** 1 when the habit's goal is met that day, counting days Loop fills in itself ("3 times a week"). */
    const val COLUMN_GOAL_MET = "goal_met"

    /** 1 for an every-day habit, where "goal met" and "done" mean the same. */
    const val COLUMN_DAILY = "daily"
}

/** One habit as the Loop fork reports it for a given day. */
data class LoopHabit(
    val uuid: String,
    val name: String,
    val archived: Boolean,
    /** False for an "at most" numerical habit, which Loop never counts as completed. */
    val countable: Boolean,
    val done: Boolean,
    /** Done, or covered by a met goal of a habit that isn't every day (see [LoopContract.COLUMN_GOAL_MET]). */
    val goalMet: Boolean = done,
    val daily: Boolean = true,
)

/** How the last read of xrnloops went, for the Settings screen. */
data class LoopSync(val atWallMs: Long, val ok: Boolean)

enum class LoopStatus { NOT_INSTALLED, NO_ACCESS, READY }

/**
 * Reads habit ticks from xrnloops and feeds them to the engine as
 * [BlockEvent.ExternalHabitsSynced] (contract I23/I24). Loop pushes
 * [LoopContract.ACTION_HABITS_CHANGED] after every change ([LoopChangedReceiver]); the service
 * also syncs on start, on unlock and at local midnight, the shield when it appears over a block
 * with a linked item, and the UI on resume.
 */
class LoopHabits(
    private val context: Context,
    private val store: WorldStore,
    private val scope: CoroutineScope,
) {
    private val _lastSync = MutableStateFlow<LoopSync?>(null)

    /** The last sync attempt; null until one has run (nothing linked yet, or just started). */
    val lastSync: StateFlow<LoopSync?> = _lastSync.asStateFlow()

    /** When xrnloops' own sync with the home server started failing, for an alert; null while it works. */
    private val _serverFailingSince = MutableStateFlow<Long?>(null)
    val serverFailingSince: StateFlow<Long?> = _serverFailingSince.asStateFlow()

    fun status(): LoopStatus = when {
        !isInstalled() -> LoopStatus.NOT_INSTALLED
        context.checkSelfPermission(LoopContract.PERMISSION) != PackageManager.PERMISSION_GRANTED -> LoopStatus.NO_ACCESS
        else -> LoopStatus.READY
    }

    fun launchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(LoopContract.LOOP_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [uuid]'s page in xrnloops (falls back to its main screen on an older build). */
    fun openHabitIntent(uuid: String): Intent? {
        val intent = Intent(LoopContract.ACTION_OPEN_HABIT)
            .setPackage(LoopContract.LOOP_PACKAGE)
            .putExtra(LoopContract.EXTRA_HABIT_UUID, uuid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        val resolvable = context.packageManager.resolveActivity(intent, 0) != null
        return if (resolvable) intent else launchIntent()
    }

    /**
     * Blocking IPC — call off the main thread. Null when Loop is missing, refuses us, or is
     * still starting up; callers must not read that as "nothing is done". A Loop process woken
     * by this very query answers null until its app has finished starting, so that is retried.
     */
    fun read(date: LocalDate): List<LoopHabit>? {
        if (status() != LoopStatus.READY) return null
        repeat(READ_ATTEMPTS) { attempt ->
            readOnce(date)?.let { return it }
            if (attempt < READ_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * Asks xrnloops to sync with the home server before we read (it waits up to ~5 s). Older
     * builds, or one with sync off, answer null or `synced = false`; the read goes ahead anyway.
     */
    private fun pullFromServer() {
        if (status() != LoopStatus.READY) return
        val uri = Uri.Builder().scheme("content").authority(LoopContract.AUTHORITY).build()
        val result = runCatching { context.contentResolver.call(uri, LoopContract.METHOD_SYNC, null, null) }.getOrNull()
        val configured = result?.getBoolean(LoopContract.KEY_CONFIGURED) == true
        val synced = result?.getBoolean(LoopContract.KEY_SYNCED) == true
        _serverFailingSince.value = if (!configured || synced) null else _serverFailingSince.value ?: System.currentTimeMillis()
    }

    private fun readOnce(date: LocalDate): List<LoopHabit>? {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(LoopContract.AUTHORITY)
            .appendPath(LoopContract.PATH_HABITS)
            .appendQueryParameter(LoopContract.PARAM_DATE, date.toString())
            .build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val uuid = c.getColumnIndexOrThrow(LoopContract.COLUMN_UUID)
                val name = c.getColumnIndexOrThrow(LoopContract.COLUMN_NAME)
                val position = c.getColumnIndexOrThrow(LoopContract.COLUMN_POSITION)
                val archived = c.getColumnIndexOrThrow(LoopContract.COLUMN_ARCHIVED)
                val countable = c.getColumnIndexOrThrow(LoopContract.COLUMN_COUNTABLE)
                val done = c.getColumnIndexOrThrow(LoopContract.COLUMN_DONE)
                // Newer columns: an older xrnloops build simply doesn't have them.
                val goalMet = c.getColumnIndex(LoopContract.COLUMN_GOAL_MET)
                val daily = c.getColumnIndex(LoopContract.COLUMN_DAILY)
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(uuid) ?: continue
                        val isDone = c.getInt(done) == 1
                        val habit = LoopHabit(
                            uuid = id,
                            name = c.getString(name).orEmpty(),
                            archived = c.getInt(archived) == 1,
                            countable = c.getInt(countable) == 1,
                            done = isDone,
                            goalMet = if (goalMet >= 0) c.getInt(goalMet) == 1 else isDone,
                            daily = daily < 0 || c.getInt(daily) == 1,
                        )
                        add(c.getInt(position) to habit)
                    }
                }.sortedBy { it.first }.map { it.second }
            }
        }.getOrNull()
    }

    /**
     * Re-reads today's and yesterday's ticks and dispatches them, if any checklist item is
     * linked to Loop. [force] skips that check — for right after linking, when the `store.edit`
     * adding the item may not have landed yet (it will have by the time the read returns and we
     * dispatch). [fromServer] first has xrnloops sync with the home server, so a habit ticked
     * on the laptop counts here too; skip it when xrnloops itself just reported a change.
     */
    fun sync(force: Boolean = false, fromServer: Boolean = true, onDone: () -> Unit = {}) {
        val anyLinked = store.world.value.checklist.values.any { items -> items.any { it.externalHabitId != null } }
        if (!anyLinked && !force) return onDone()
        scope.launch(Dispatchers.IO) {
            try {
                if (fromServer) pullFromServer()
                val day = store.clock.localDate()
                val today = read(day)
                val yesterday = today?.let { read(day.minusDays(1)) }
                _lastSync.value = LoopSync(System.currentTimeMillis(), ok = yesterday != null)
                if (today == null || yesterday == null) return@launch
                store.dispatch(
                    BlockEvent.ExternalHabitsSynced(
                        epochDay = day.toEpochDay(),
                        doneHabitIds = today.ids { it.done },
                        doneYesterdayHabitIds = yesterday.ids { it.done },
                        goalMetHabitIds = today.ids { it.goalMet },
                        goalMetYesterdayHabitIds = yesterday.ids { it.goalMet },
                    ),
                )
            } finally {
                onDone()
            }
        }
    }

    private fun List<LoopHabit>.ids(predicate: (LoopHabit) -> Boolean): Set<String> =
        filter(predicate).mapTo(mutableSetOf()) { it.uuid }

    private fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(LoopContract.LOOP_PACKAGE, 0) }.isSuccess

    private companion object {
        const val READ_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L
    }
}
