package com.xern.xrnhabit.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xern.xrnhabit.app.block.OverlayShieldPort
import com.xern.xrnhabit.app.ui.theme.XrnhabitTheme
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.LinkedDay
import com.xern.xrnhabit.domain.RejectReason
import com.xern.xrnhabit.domain.TriggerType
import kotlinx.coroutines.delay

private const val HOLD_MS = 1200
private const val SETTLE_MS = 5_000L

/**
 * The block screen. Full-bleed, one job: it does not go away until the habit is done and the
 * unlock is redeemed (or the user leaves the app). Checklist items are hold-to-complete so the
 * list can't be tapped through on autopilot, and the Open button is inert for a few seconds
 * after the habit is met.
 */
@Composable
fun BlockOverlayContent(
    ui: OverlayShieldPort.OverlayUi?,
    onToggleItem: (String, Boolean) -> Unit,
    onSetTimer: (String, Boolean) -> Unit,
    onRedeem: (String) -> Unit,
    onLeave: () -> Unit,
    /** Opens a habit (by uuid) in our Loop build; null when it isn't installed. */
    onOpenLoop: ((String) -> Unit)? = null,
) {
    XrnhabitTheme {
        val block = ui?.block
        // Daily limit / schedule / lockdown: no habit to do, just say when it lets go (contract §2.2).
        val strict = block != null && block.mode != BlockMode.HABIT
        val state = if (strict) BlockState.ARMED else block?.state ?: BlockState.ARMED
        val style = statusStyleFor(state)
        val subject = ui?.subject ?: "This app"
        rememberTicker() // keeps a lockdown countdown live

        // Surface, not a bare Column: it sets LocalContentColor so headline Text with no
        // explicit colour isn't left as the default black on our dark ground.
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 64.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(style.container, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        state == BlockState.EARNED -> Icons.Filled.Bolt
                        block?.mode == BlockMode.ALLOWANCE -> Icons.Filled.HourglassBottom
                        block?.mode == BlockMode.SCHEDULE -> Icons.Filled.Schedule
                        else -> Icons.Filled.Lock
                    },
                    contentDescription = null,
                    tint = style.content,
                    modifier = Modifier.size(30.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = when (state) {
                    BlockState.EARNED -> "Habit done"
                    else -> "$subject is blocked"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            if (strict) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strictStatus(block!!, ui!!),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))

            when {
                block == null || strict -> Unit

                state == BlockState.EARNED -> EarnedActions(
                    blockId = block.id,
                    unlockMin = block.reward.unlockDurationMs / 60_000,
                    ui = ui,
                    onRedeem = onRedeem,
                )

                block.trigger.type == TriggerType.CHECKLIST -> {
                    ChecklistItemsSection(ui, style.content, onToggleItem, onOpenLoop)
                }

                block.trigger.type == TriggerType.TIMER -> TimerSection(block, ui, style.content, onSetTimer)

                block.trigger.type == TriggerType.COMBINED -> {
                    ChecklistItemsSection(ui, style.content, onToggleItem, onOpenLoop)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        if (block.trigger.combineMode == CombineMode.AND) "And the timer" else "Or the timer",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TimerSection(block, ui, style.content, onSetTimer)
                }
            }

            Spacer(Modifier.height(if (strict) 8.dp else 32.dp))
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui?.isSite == true) "Leave this site" else "Go to home screen")
            }
        }
        }
    }
}

/** When a daily limit / schedule / lockdown lets go — status, not instructions. */
private fun strictStatus(block: Block, ui: OverlayShieldPort.OverlayUi): String {
    val freeAt = ui.freeAtWall
    return when (block.mode) {
        BlockMode.ALLOWANCE -> "${formatDuration(ui.allowanceMs ?: block.dailyAllowanceMs)} used · opens at midnight"
        BlockMode.SCHEDULE -> if (freeAt != null) "Until ${formatClockTime(freeAt)}" else ""
        BlockMode.LOCKDOWN -> ui.freeInMs?.let { "${formatRemaining(it)} left" }.orEmpty()
        BlockMode.HABIT -> ""
    }
}

@Composable
private fun ChecklistItemsSection(
    ui: OverlayShieldPort.OverlayUi,
    accentContent: androidx.compose.ui.graphics.Color,
    onToggleItem: (String, Boolean) -> Unit,
    onOpenLoop: ((String) -> Unit)?,
) {
    ui.items.sortedBy { it.order }.forEach { item ->
        val habitId = item.externalHabitId
        if (habitId != null) {
            LoopItemRow(
                text = item.text,
                done = item.done,
                used = item.tickSpent(),
                yesterday = item.linkedDay == LinkedDay.YESTERDAY,
                accentContent = accentContent,
                onOpenLoop = onOpenLoop?.let { open -> { open(habitId) } },
            )
        } else {
            HoldToCheck(
                text = item.text,
                done = item.done,
                accentContent = accentContent,
                onToggle = { onToggleItem(item.id, it) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    val done = ui.items.count { it.done }
    Text(
        "$done of ${ui.items.size} done",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    MeterBar(if (ui.items.isEmpty()) 0f else done.toFloat() / ui.items.size, color = accentContent)
}

/**
 * Time counted toward a TIMER habit, and the switch for it. Says where the time counts: in the
 * habit app(s) when the block names any, otherwise right here while this screen is up.
 */
@Composable
private fun TimerSection(
    block: Block,
    ui: OverlayShieldPort.OverlayUi,
    accentContent: androidx.compose.ui.graphics.Color,
    onSetTimer: (String, Boolean) -> Unit,
) {
    val required = block.trigger.requiredAmount
    Text(
        "${formatDuration(ui.timerMs.coerceAtMost(required))} of ${formatDuration(required)}",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(6.dp))
    MeterBar(if (required <= 0) 1f else (ui.timerMs.toFloat() / required).coerceIn(0f, 1f), color = accentContent)
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            ui.habitAppLabel != null -> "Counts while you use ${ui.habitAppLabel}"
            else -> "Counts while you wait on this screen"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    if (ui.timerRunning) {
        OutlinedButton(onClick = { onSetTimer(block.id, false) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Pause, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pause timer")
        }
    } else {
        Button(onClick = { onSetTimer(block.id, true) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start timer")
        }
    }
}

@Composable
private fun EarnedActions(blockId: String, unlockMin: Long, ui: OverlayShieldPort.OverlayUi, onRedeem: (String) -> Unit) {
    if (ui.redeemRejection == RejectReason.CAP_REACHED) {
        Text(
            capReachedText(ui.unlockUsedTodayMs, ui.dailyCapMs, unlockMin * 60_000),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var shownAt by remember(blockId) { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember(blockId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(blockId) {
        shownAt = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val remainMs = (SETTLE_MS - (now - shownAt)).coerceAtLeast(0L)
    val ready = remainMs <= 0L
    Button(
        onClick = { onRedeem(blockId) },
        enabled = ready,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (ready) "Open for $unlockMin min" else "Steady… ${remainMs / 1000 + 1}")
    }
}

/**
 * A checklist row linked to a Loop habit (I23). It can't be checked here — only a tick in Loop
 * counts — so tapping it opens Loop instead. [yesterday]: the item counts yesterday's tick.
 * [used]: it is ticked, but that tick already opened this block (I24), so it doesn't count again.
 */
@Composable
private fun LoopItemRow(
    text: String,
    done: Boolean,
    used: Boolean,
    yesterday: Boolean,
    accentContent: androidx.compose.ui.graphics.Color,
    onOpenLoop: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (!done && !used && onOpenLoop != null) Modifier.clickable(onClick = onOpenLoop) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (done) 1f else 0f },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = accentContent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Icon(
                if (done || used) Icons.Filled.Check else Icons.Filled.Link,
                contentDescription = null,
                tint = if (done) accentContent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    done && yesterday -> "Done in xrnloops yesterday"
                    done -> "Done in xrnloops"
                    used && yesterday -> "Done yesterday · that tick already opened this block"
                    used -> "Done · that tick already opened this block today"
                    yesterday && onOpenLoop != null -> "Needs yesterday's tick in xrnloops · tap to open"
                    yesterday -> "Needs yesterday's tick in xrnloops"
                    onOpenLoop != null -> "Tick it in xrnloops · tap to open"
                    else -> "Tick it in xrnloops"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A checklist row that only toggles after a sustained press (~[HOLD_MS] ms). Releasing early
 * springs the ring back to empty. Prevents the "tap tap tap, done" speed-run.
 */
@Composable
private fun HoldToCheck(
    text: String,
    done: Boolean,
    accentContent: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = if (holding) tween(HOLD_MS, easing = LinearEasing) else tween(160),
        label = "hold",
    )
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 0.999f) {
            holding = false
            onToggle(!done)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(done) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (done) 1f else progress },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = accentContent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = accentContent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
