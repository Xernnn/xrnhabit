package com.xern.xrnhabit.app.block

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xern.xrnhabit.app.XrnhabitApp
import com.xern.xrnhabit.app.ui.MainActivity
import com.xern.xrnhabit.app.ui.formatRemaining
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode

/**
 * Quick Settings tile: starts every lockdown block that isn't already running, without opening
 * the app. While one runs the tile shows the time left; it can't stop a lockdown (I21). With no
 * lockdown block yet, it opens the app to make one.
 */
class LockdownTileService : TileService() {

    private val container get() = (application as XrnhabitApp).container

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val world = container.store.world.value
        val lockdowns = world.blocks.filter { it.mode == BlockMode.LOCKDOWN }
        if (lockdowns.isEmpty()) {
            openApp()
            return
        }
        BlockerForegroundService.start(this)
        lockdowns
            .filter { container.engine.lockdownRemainingMs(world, it.id, container.clock) == 0L }
            .forEach { container.store.dispatch(BlockEvent.StartLockdown(it.id)) }
        refresh(justStarted = true)
    }

    private fun refresh(justStarted: Boolean = false) {
        val tile = qsTile ?: return
        val world = container.store.world.value
        val lockdowns = world.blocks.filter { it.mode == BlockMode.LOCKDOWN }
        val left = lockdowns.maxOfOrNull { container.engine.lockdownRemainingMs(world, it.id, container.clock) } ?: 0L
        val running = left > 0 || (justStarted && lockdowns.isNotEmpty())
        tile.label = "Lock down"
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                lockdowns.isEmpty() -> "Set one up"
                left > 0 -> "${formatRemaining(left)} left"
                running -> "Started"
                else -> "Tap to start"
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // The Intent overload only fails on Android 14+, which takes the branch above.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
