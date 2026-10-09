package com.xern.xrnhabit.app.block

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xern.xrnhabit.app.XrnhabitApp
import com.xern.xrnhabit.app.R
import com.xern.xrnhabit.app.web.SiteSightings
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.domain.epochDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the Phase A core loop: detect the foreground app -> feed the engine -> (re)render the
 * block overlay. Runs as a `specialUse` foreground service so it can hold the overlay and be
 * restarted after death (survival layer, brainstorm §4.4 — the watchdog/boot pieces are
 * Phase B).
 */
class BlockerForegroundService : LifecycleService() {

    /** What the user is looking at: an app, plus the site when that app is a browser. */
    private data class Foreground(val pkg: String?, val site: String?)

    private val foreground = MutableStateFlow(Foreground(null, null))
    private val awake = MutableStateFlow(true)
    private lateinit var overlay: OverlayShieldPort
    private lateinit var settingsGuard: SettingsGuardOverlay

    /**
     * Screen off counts as "nothing in use". UsageStats keeps reporting the last app while the
     * phone is locked, which would let timers run and daily limits drain in a pocket overnight.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> awake.value = false
                Intent.ACTION_USER_PRESENT -> {
                    awake.value = true
                    // Cheap safety net for a missed Loop push: re-read before the user can reach a blocked app.
                    (application as XrnhabitApp).container.loop.sync()
                }
                Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) awake.value = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as XrnhabitApp).container

        overlay = OverlayShieldPort(
            context = this,
            store = container.store,
            loop = container.loop,
            onEvent = container.store::dispatch,
        )
        container.store.shield = overlay
        settingsGuard = SettingsGuardOverlay(this, container.hardcoreGuard)

        startAsForeground()

        awake.value = getSystemService(PowerManager::class.java).isInteractive && !keyguardLocked()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val detector: ForegroundAppDetector = UsageStatsDetector(this)

        // 1. foreground app (+ browser site) -> engine and the site-time log
        lifecycleScope.launch {
            combine(detector.foregroundPackage, SiteSightings.current, awake) { pkg, sighting, isAwake ->
                if (!isAwake) {
                    Foreground(null, null)
                } else {
                    Foreground(pkg, sighting?.takeIf { it.browserPackage == pkg }?.host)
                }
            }.distinctUntilChanged().collect { fg ->
                foreground.value = fg
                container.siteUsage.onSite(fg.site)
                container.store.dispatch(BlockEvent.ForegroundChanged(fg.pkg, fg.site))
            }
        }

        // 2. cold-start rebuild (I8), then catch up on Loop ticks made while we were down (I23)
        container.store.dispatch(BlockEvent.BootOrColdStart)
        container.loop.sync()

        // 3. ~1s tick while anything time-based is live; book website time every 30 s; re-read
        //    Loop at local midnight so yesterday's ticks don't linger (I23)
        lifecycleScope.launch {
            var seconds = 0
            var loopDay = container.clock.epochDay()
            while (true) {
                if (needsTicking(container.store.world.value)) {
                    container.store.dispatch(BlockEvent.Tick)
                }
                if (++seconds % 30 == 0) container.siteUsage.flush()
                val today = container.clock.epochDay()
                if (today != loopDay) {
                    loopDay = today
                    container.loop.sync()
                }
                delay(1_000)
            }
        }

        // 4. re-render the overlay (and the hardcore Settings guard) whenever the world or the
        //    foreground changes
        lifecycleScope.launch {
            combine(container.store.world, foreground) { world, fg -> world to fg }
                .collect { (world, fg) ->
                    overlay.render(world, fg.pkg, fg.site)
                    settingsGuard.render(fg.pkg, world)
                }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        (application as XrnhabitApp).container.siteUsage.onSite(null)
        super.onDestroy()
    }

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Some OEMs kill the whole process when the task is swiped from recents despite
     * `START_STICKY`. Ask WorkManager to bring the service back up immediately rather than
     * waiting for [ServiceWatchdogWorker]'s next periodic tick (brainstorm §4.4).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val request = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>().build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    /** Daily limits, schedules and lockdowns change what's blocked by time alone, so they always tick. */
    private fun needsTicking(world: World): Boolean =
        world.sessions.isNotEmpty() ||
            world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT } ||
            world.progress.values.any { it.timerRunning }

    // Lint false positive (sideload variant only): the merged manifest does declare
    // foregroundServiceType="specialUse" on this service.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Enforcement", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "enforcement"
        private const val NOTIF_ID = 1

        /**
         * Android 12+ refuses foreground-service starts from a process that came up in the
         * background (a watchdog job, the system binding the website reader) by throwing
         * `ForegroundServiceStartNotAllowedException`, an [IllegalStateException]. Called from
         * `Application.onCreate`, that used to kill the process before anything ran. Now the
         * start is simply skipped; the next allowed caller (opening the app, boot, the website
         * reader connecting) brings the service up.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, BlockerForegroundService::class.java)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                false
            }
        }
    }
}
