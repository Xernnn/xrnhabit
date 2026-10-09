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

    const val PATH_HABITS = "habits"
    const val PARAM_DATE = "date"
    const val COLUMN_UUID = "uuid"
    const val COLUMN_NAME = "name"
    const val COLUMN_POSITION = "position"
    const val COLUMN_ARCHIVED = "archived"
    const val COLUMN_COUNTABLE = "countable"
    const val COLUMN_DONE = "done"
}

/** One habit as the Loop fork reports it for a given day. */
data class LoopHabit(
    val uuid: String,
    val name: String,
    val archived: Boolean,
    /** False for an "at most" numerical habit, which Loop never counts as completed. */
    val countable: Boolean,
    val done: Boolean,
)

enum class LoopStatus { NOT_INSTALLED, NO_ACCESS, READY }

/**
 * Reads habit ticks from xrnloops and feeds them to the engine as
 * [BlockEvent.ExternalHabitsSynced] (contract I23/I24). Loop pushes
 * [LoopContract.ACTION_HABITS_CHANGED] after every change ([LoopChangedReceiver]); the service
 * also syncs on start, on unlock and at local midnight, and the UI on resume.
 */
class LoopHabits(
    private val context: Context,
    private val store: WorldStore,
    private val scope: CoroutineScope,
) {
    fun status(): LoopStatus = when {
        !isInstalled() -> LoopStatus.NOT_INSTALLED
        context.checkSelfPermission(LoopContract.PERMISSION) != PackageManager.PERMISSION_GRANTED -> LoopStatus.NO_ACCESS
        else -> LoopStatus.READY
    }

    fun launchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(LoopContract.LOOP_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Blocking IPC — call off the main thread. Null when Loop is missing, refuses us, or is
     * still starting up; callers must not read that as "nothing is done".
     */
    fun read(date: LocalDate): List<LoopHabit>? {
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
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(uuid) ?: continue
                        add(c.getInt(position) to LoopHabit(id, c.getString(name).orEmpty(), c.getInt(archived) == 1, c.getInt(countable) == 1, c.getInt(done) == 1))
                    }
                }.sortedBy { it.first }.map { it.second }
            }
        }.getOrNull()
    }

    /**
     * Re-reads today's ticks and dispatches them, if any checklist item is linked to Loop.
     * [force] skips that check — for right after linking, when the `store.edit` adding the item
     * may not have landed yet (it will have by the time the read returns and we dispatch).
     */
    fun sync(force: Boolean = false, onDone: () -> Unit = {}) {
        val anyLinked = store.world.value.checklist.values.any { items -> items.any { it.externalHabitId != null } }
        if (!anyLinked && !force) return onDone()
        scope.launch(Dispatchers.IO) {
            try {
                val day = store.clock.localDate()
                val habits = read(day) ?: return@launch
                val done = habits.filter { it.done }.mapTo(mutableSetOf()) { it.uuid }
                store.dispatch(BlockEvent.ExternalHabitsSynced(day.toEpochDay(), done))
            } finally {
                onDone()
            }
        }
    }

    private fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(LoopContract.LOOP_PACKAGE, 0) }.isSuccess
}
