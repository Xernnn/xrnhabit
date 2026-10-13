package com.xern.xrnhabit.app.block

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xern.xrnhabit.app.XrnhabitApp
import com.xern.xrnhabit.app.data.AppPermission
import com.xern.xrnhabit.app.data.isGranted
import com.xern.xrnhabit.app.web.SiteSightings
import com.xern.xrnhabit.app.web.WebsiteTracking
import com.xern.xrnhabit.app.web.tracking
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Effect
import com.xern.xrnhabit.domain.RejectReason
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.domain.epochDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Owns the Phase A core loop: detect the foreground app -> feed the engine -> (re)render the
 * block overlay. Runs as a `specialUse` foreground service so it can hold the overlay and be
 * restarted after death (survival layer, brainstorm §4.4 — the watchdog/boot pieces are
 * Phase B). Also keeps the status notification current and raises alerts (see [Notifications]).
 */
class BlockerForegroundService : LifecycleService() {

    /** What the user is looking at: an app, plus the site when that app is a browser. */
    private data class Foreground(val pkg: String?, val site: String?)

    private val foreground = MutableStateFlow(Foreground(null, null))
    private val awake = MutableStateFlow(true)
    private lateinit var overlay: OverlayShieldPort
    private lateinit var settingsGuard: SettingsGuardOverlay
    private lateinit var notifications: Notifications

    /** The unlock session already warned about, so the 30 s warning fires once per unlock. */
    private var warnedSession: Pair<String, Long>? = null
    private var lastHealth: String? = null

    /**
     * Screen off counts as "nothing in use". UsageStats keeps reporting the last app while the
     * phone is locked, which would let timers run and daily limits drain in a pocket overnight.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val container = (application as XrnhabitApp).container
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    awake.value = false
                    container.persist.flush()
                }
                Intent.ACTION_USER_PRESENT -> {
                    awake.value = true
                    // Cheap safety net for a missed Loop push: re-read before the user can reach a blocked app.
                    container.loop.sync()
                }
                Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) awake.value = true
                // A hand-set clock change: book it before anything reads the time again.
                Intent.ACTION_TIME_CHANGED -> {
                    container.clockGuard.check()
                    container.store.dispatch(BlockEvent.Tick)
                }
                Intent.ACTION_TIMEZONE_CHANGED -> container.store.dispatch(BlockEvent.Tick)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val container = (application as XrnhabitApp).container
        notifications = Notifications(this).also { it.createChannels() }

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
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val detector: ForegroundAppDetector = UsageStatsDetector(this)

        // 1. foreground app (+ browser site) -> engine and the site-time log. The detector only
        //    polls while the screen is on and unlocked.
        lifecycleScope.launch {
            val detected = awake.flatMapLatest { isAwake -> if (isAwake) detector.foregroundPackage else flowOf(null) }
            combine(detected, SiteSightings.current, awake) { pkg, sighting, isAwake ->
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
        lifecycleScope.launch(Dispatchers.IO) { container.telemetry.prune(container.clock.nowWallMs()) }
        container.unreadableWorldCopy?.let { copy ->
            notifications.alert(
                Notifications.ALERT_WORLD_UNREADABLE,
                "Couldn't read your blocks",
                "xrnhabit started empty. The unreadable file was kept as ${copy.name}.",
            )
        }

        // 3. ~1s tick while anything time-based is live and the screen is on (nothing is shown
        //    while it's off, and the first event after waking catches up); book website time
        //    every 30 s; re-read Loop at local midnight; check that blocking still works.
        lifecycleScope.launch {
            var seconds = 0
            var loopDay = container.clock.epochDay()
            while (true) {
                if (!awake.value) awake.first { it }
                if (needsTicking(container.store.world.value)) {
                    container.store.dispatch(BlockEvent.Tick)
                }
                if (seconds % 30 == 0) {
                    container.siteUsage.flush()
                    container.clockGuard.check()
                    checkHealth(container.store.world.value)
                }
                seconds++
                val today = container.clock.epochDay()
                if (today != loopDay) {
                    loopDay = today
                    container.loop.sync()
                }
                delay(1_000)
            }
        }

        // 4. re-render the overlay (and the hardcore Settings guard) whenever the world or the
        //    foreground changes; keep the status notification in step
        lifecycleScope.launch {
            combine(container.store.world, foreground) { world, fg -> world to fg }
                .collect { (world, fg) ->
                    overlay.render(world, fg.pkg, fg.site)
                    settingsGuard.render(fg.pkg, world)
                    notifications.updateStatus(notifications.status(world, container.engine, container.clock))
                }
        }

        // 5. engine effects that deserve the user's attention right now
        lifecycleScope.launch {
            container.store.effects.collect { effect -> onEffect(effect, container.store.world.value) }
        }
    }

    private fun onEffect(effect: Effect, world: World) {
        when (effect) {
            is Effect.UnlockEndingSoon -> {
                val session = world.sessions[effect.blockId] ?: return
                val key = effect.blockId to session.startedAtWall
                if (warnedSession == key) return
                warnedSession = key
                val name = world.block(effect.blockId)?.name ?: "Your unlock"
                notifications.alert(Notifications.ALERT_ENDING, "$name locks in ${(effect.remainingMs + 999) / 1000} s", "Finish up.")
            }
            is Effect.TamperDetected -> notifications.alert(
                Notifications.ALERT_TAMPER,
                "Unlock ended early",
                "The phone's clock was turned back, so the unlock was closed.",
            )
            is Effect.RedeemRejected -> if (effect.reason == RejectReason.CAP_REACHED) {
                Toast.makeText(this, "Today's unlock time is used up", Toast.LENGTH_LONG).show()
            }
            else -> Unit
        }
    }

    /**
     * Turning off "Usage access" or "Draw over other apps" silently stops all blocking (and MIUI
     * resets the overlay one on every reinstall), so say so while it lasts.
     */
    private fun checkHealth(world: World) {
        val container = (application as XrnhabitApp).container
        val missing = if (world.blocks.isEmpty()) {
            emptyList()
        } else {
            listOf(AppPermission.USAGE_ACCESS, AppPermission.OVERLAY).filterNot { it.isGranted(this) }
        }
        val sitesOff = container.websites.isSupported() &&
            world.blocks.any { it.targetSites.isNotEmpty() } &&
            container.websites.tracking(this) != WebsiteTracking.ON
        val health = "${missing.joinToString()}|$sitesOff"
        if (health == lastHealth) return
        lastHealth = health

        if (missing.isEmpty()) {
            notifications.cancel(Notifications.ALERT_BLOCKING_OFF)
        } else {
            notifications.alert(
                Notifications.ALERT_BLOCKING_OFF,
                "Blocking is off",
                "Turn on ${missing.joinToString(" and ") { "\"${it.label}\"" }} for xrnhabit. Tap to fix.",
            )
        }
        if (sitesOff) {
            notifications.alert(
                Notifications.ALERT_WEBSITES_OFF,
                "Website blocking isn't running",
                "Turn xrnhabit's website reader back on in Accessibility settings. Tap to fix.",
            )
        } else {
            notifications.cancel(Notifications.ALERT_WEBSITES_OFF)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        val container = (application as XrnhabitApp).container
        container.siteUsage.onSite(null)
        container.persist.flush()
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

    /** Daily limits, schedules, lockdowns and breaks change what's blocked by time alone, so they always tick. */
    private fun needsTicking(world: World): Boolean =
        world.sessions.isNotEmpty() ||
            world.pause != null ||
            world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT } ||
            world.progress.values.any { it.timerRunning }

    // Lint false positive (sideload variant only): the merged manifest does declare
    // foregroundServiceType="specialUse" on this service.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val container = (application as XrnhabitApp).container
        val notification = notifications.buildStatus(
            notifications.status(container.store.world.value, container.engine, container.clock),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.STATUS_ID, notification)
        }
    }

    companion object {
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
