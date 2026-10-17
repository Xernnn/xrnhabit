package com.xern.xrnhabit.app.block

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.xern.xrnhabit.app.R
import com.xern.xrnhabit.app.ui.MainActivity
import com.xern.xrnhabit.domain.BlockEngine
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Clock
import com.xern.xrnhabit.domain.World
import java.util.Date

/**
 * The service's notifications: the ongoing status line that keeps it in the foreground (what's
 * open and until when, a break, a habit ready to cash in), and alerts for things worth knowing
 * right away — an unlock about to end, blocking that has stopped working.
 */
class Notifications(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastStatus: Status? = null

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    /** What the ongoing notification says. [endsInMs] drives a live countdown when set. */
    data class Status(val title: String, val text: String, val endsInMs: Long? = null)

    fun status(world: World, engine: BlockEngine, clock: Clock): Status {
        val pauseMs = engine.pauseRemainingMs(world, clock)
        if (pauseMs > 0) return Status("On a break", "Blocks come back at ${clockTime(pauseMs)}", pauseMs)

        val open = world.sessions.values
            .map { s -> s to s.durationMs - (clock.nowMonotonicMs() - s.startedAtMonotonic) }
            .filter { (_, left) -> left > 0 }
            .minByOrNull { (_, left) -> left }
        if (open != null) {
            val (session, left) = open
            val name = world.block(session.blockId)?.name ?: "Unlock"
            return Status("$name is open", "Locks again at ${clockTime(left)}", left)
        }

        world.blocks.firstOrNull { it.state == BlockState.EARNED }?.let { block ->
            return Status("${block.name}: habit done", "Open it from the block screen or the app")
        }

        val active = world.blocks.count { engine.isBlocking(world, it, clock) }
        val text = when (active) {
            0 -> if (world.blocks.isEmpty()) "No blocks yet" else "Nothing is blocked right now"
            1 -> "1 block active"
            else -> "$active blocks active"
        }
        return Status(context.getString(R.string.fgs_notification_title), text)
    }

    fun buildStatus(status: Status): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        status.endsInMs?.let { left ->
            // The chronometer runs on the device's own clock, so anchor it there.
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() + left)
        }
        return builder.build()
    }

    /** Re-posts the status notification only when what it says has changed. */
    fun updateStatus(status: Status) {
        val comparable = status.copy(endsInMs = status.endsInMs?.let { it / 10_000 })
        if (comparable == lastStatus) return
        lastStatus = comparable
        nm.notify(STATUS_ID, buildStatus(status))
    }

    fun alert(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(id, notification)
    }

    fun cancel(id: Int) = nm.cancel(id)

    private fun clockTime(fromNowMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + fromNowMs))

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val STATUS_ID = 1
        const val ALERT_ENDING = 10
        const val ALERT_TAMPER = 11
        const val ALERT_BLOCKING_OFF = 12
        const val ALERT_WEBSITES_OFF = 13
        const val ALERT_WORLD_UNREADABLE = 14
        const val ALERT_SYNC_OFF = 15
        private const val CHANNEL_STATUS = "enforcement"
        private const val CHANNEL_ALERTS = "alerts"
    }
}
