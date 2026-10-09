package com.xern.xrnhabit.app.block

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Phase B watchdog (brainstorm §4.4): if [BlockerForegroundService] died for any reason — an
 * OEM battery killer, a crash, a swipe from recents that [BlockerForegroundService.onTaskRemoved]
 * didn't catch in time — this brings it back. Starting an already-running service is a harmless
 * no-op (`onCreate` only runs once per process; `onStartCommand` just returns `START_STICKY`).
 * WorkManager's periodic floor is 15 minutes, so this is the slow backstop; `onTaskRemoved`
 * covers the common "swiped away" case immediately instead of waiting for this to fire.
 */
class ServiceWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        BlockerForegroundService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "service-watchdog"
    }
}
