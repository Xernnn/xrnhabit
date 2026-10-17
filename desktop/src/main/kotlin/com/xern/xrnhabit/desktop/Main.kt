package com.xern.xrnhabit.desktop

import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Effect
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * xrnhabit on the laptop: the same engine as the phone, enforcing the same shared blocks on
 * desktop apps (KWin) and websites (the browser extension). See `desktop/README.md`.
 */
fun main() = runBlocking {
    val config = Config.load()
    config.dataDir.mkdirs()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = DesktopClock()
    val persist = FilePersist(File(config.dataDir, "world.json"), clock)
    val telemetry = FileTelemetry(File(config.dataDir, "telemetry.log")).also { it.prune(clock.nowWallMs()) }
    val store = Store(BlockEngine(), persist, clock, scope, telemetry)
    if (store.world.value.deviceId.isEmpty()) {
        store.edit { it.copy(deviceId = "laptop-" + UUID.randomUUID().toString().take(8)) }
    }

    val notifier = Notifier(config)
    val enforcer = Enforcer(store, notifier, config)
    val foreground = Foreground(store)
    enforcer.foreground = foreground
    store.shield = enforcer
    val shared = SharedSyncClient(config, store, telemetry, scope)
    val loop = LoopClient(config, store, scope)

    if (KWin.export(enforcer)) KWin.reloadScript() // our script lists the open windows again
    LocalServer(config, store, foreground, enforcer, shared, loop).start()
    store.dispatch(BlockEvent.BootOrColdStart) // I8, and the first shield plan
    shared.start()
    loop.start()
    Runtime.getRuntime().addShutdownHook(Thread { persist.flush() })

    // Engine effects -> desktop notifications.
    scope.launch {
        store.effects.collect { effect ->
            val world = store.world.value
            fun name(id: String) = world.block(id)?.name ?: "A block"
            when (effect) {
                is Effect.UnlockEndingSoon -> notifier.notify("${name(effect.blockId)} locks again soon", "In ${effect.remainingMs / 1000} seconds.")
                is Effect.TamperDetected -> notifier.notify("Unlock ended", "The clock was changed, so ${name(effect.blockId)} is blocked again.", urgent = true)
                is Effect.RedeemRejected -> notifier.notify("Can't open ${name(effect.blockId)}", "Today's unlock time is used up.")
                else -> Unit
            }
        }
    }

    System.err.println("xrnhabit-desktop on ${config.localUrl}, data in ${config.dataDir}" + if (config.syncConfigured) ", syncing with ${config.habitUrl} and ${config.loopUrl}" else ", not syncing")

    // The tick: ~1 s while anything time-based is live; screen lock means nothing in use; a gap
    // (the laptop slept) re-anchors timers rather than crediting the sleep to whatever was in front.
    var lastMono = clock.nowMonotonicMs()
    var seconds = 0
    var extensionAlerted = false
    var syncFailingSince: Long? = null
    var syncAlerted = false
    while (true) {
        val now = clock.nowMonotonicMs()
        if (now - lastMono > 10_000) store.dispatch(BlockEvent.BootOrColdStart)
        lastMono = now
        if (seconds % 3 == 0) {
            val locked = withContext(Dispatchers.IO) { KWin.screenLocked() }
            if (locked != foreground.locked) {
                foreground.locked = locked
                if (locked) persist.flush()
            }
        }
        foreground.update()
        val world = store.world.value
        if (needsTicking(world)) store.dispatch(BlockEvent.Tick)
        if (seconds % 30 == 0) {
            val missing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() }
            if (missing && !extensionAlerted) {
                notifier.notify("Website blocking is off", "The xrnhabit extension isn't answering in this browser. Turn it on in the extensions page.", urgent = true)
            }
            extensionAlerted = missing

            // Sync failing for an hour: the phone's unlocks and ticks stop arriving, silently.
            val failing = listOfNotNull(shared.status.value, loop.status.value).firstOrNull { !it.ok }
            val wall = System.currentTimeMillis()
            syncFailingSince = if (failing == null) null else syncFailingSince ?: wall
            val stuck = syncFailingSince?.let { wall - it >= SYNC_ALERT_MS } == true
            if (stuck && !syncAlerted) {
                notifier.notify(
                    "Not syncing with your home server",
                    "Nothing has synced for over an hour (${failing?.message}), so unlocks and ticks from the phone aren't reaching this laptop.",
                    urgent = true,
                )
            }
            syncAlerted = stuck
        }
        seconds++
        delay(1_000)
    }
}

/** How long sync may fail before the "not syncing" notification. */
private const val SYNC_ALERT_MS = 60 * 60 * 1000L

private fun needsTicking(world: World): Boolean =
    world.sessions.isNotEmpty() ||
        world.pause != null ||
        world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT } ||
        world.progress.values.any { it.timerRunning }
