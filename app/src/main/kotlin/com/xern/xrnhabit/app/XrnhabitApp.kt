package com.xern.xrnhabit.app

import android.app.Application
import com.xern.xrnhabit.app.block.BlockerForegroundService
import com.xern.xrnhabit.app.block.ServiceWatchdogWorker
import com.xern.xrnhabit.app.data.AppContainer
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Entry point. Manual DI (a 3-screen app does not need Hilt — brainstorm §4.6).
 */
class XrnhabitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The engine's core loop lives in the service so it survives the UI being gone.
        BlockerForegroundService.start(this)
        scheduleWatchdog()
    }

    /** Phase B survival backstop (brainstorm §4.4) — see [ServiceWatchdogWorker]. */
    private fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ServiceWatchdogWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
