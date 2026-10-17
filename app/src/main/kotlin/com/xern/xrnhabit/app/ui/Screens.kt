@file:OptIn(ExperimentalMaterial3Api::class)

package com.xern.xrnhabit.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnhabit.app.data.AppPermission
import com.xern.xrnhabit.app.data.BlocksBackups
import com.xern.xrnhabit.app.data.ClockGuard
import com.xern.xrnhabit.app.data.InstalledApps
import com.xern.xrnhabit.app.data.OemAutostart
import com.xern.xrnhabit.app.data.SharedSync
import com.xern.xrnhabit.app.data.WorldStore
import com.xern.xrnhabit.app.data.isGranted
import com.xern.xrnhabit.app.data.settingsIntent
import com.xern.xrnhabit.app.hardcore.HardcoreGuard
import com.xern.xrnhabit.app.loop.LoopHabit
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.loop.LoopStatus
import com.xern.xrnhabit.app.web.WebsiteMonitor
import com.xern.xrnhabit.app.web.WebsiteTracking
import com.xern.xrnhabit.app.web.tracking
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockEdits
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.ChecklistItem
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.Defaults
import com.xern.xrnhabit.domain.EnforcementTier
import com.xern.xrnhabit.domain.LinkedDay
import com.xern.xrnhabit.domain.RejectReason
import com.xern.xrnhabit.domain.Reward
import com.xern.xrnhabit.domain.Schedule
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.TimeWindow
import com.xern.xrnhabit.domain.Trigger
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.TriggerUnit
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

// =====================================================================================
//  Blocks
// =====================================================================================

@Composable
fun BlocksScreen(
    world: World,
    store: WorldStore,
    guard: HardcoreGuard,
    onOpenBlock: (String) -> Unit,
    onCreateBlock: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appLabels = remember { InstalledApps.launchable(context).associate { it.packageName to it.label } }
    val resume = rememberResumeSignal()
    val blockingBroken = remember(resume) {
        !AppPermission.USAGE_ACCESS.isGranted(context) || !AppPermission.OVERLAY.isGranted(context)
    }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var breakUsed by remember { mutableStateOf(false) }
    val pauseLeft = store.engine.pauseRemainingMs(world, store.clock)
    if (pauseLeft > 0) rememberTicker()

    Scaffold(
        topBar = {
            HFTopAppBar(
                title = { Text("xrnhabit") },
                actions = {
                    // A break only lifts soft blocks (I25); with none, there's nothing to offer.
                    if (pauseLeft == 0L && world.blocks.any(store.engine::pausable)) {
                        IconButton(onClick = {
                            if (store.engine.canPause(world, store.clock)) {
                                loosen = LoosenRequest(
                                    title = "Take a ${formatDuration(Defaults.PAUSE_MS)} break?",
                                    detail = "Soft blocks stop blocking for ${formatDuration(Defaults.PAUSE_MS)}. " +
                                        "You get one break a day. Hardcore blocks and lockdowns stay on.",
                                    hardcore = false,
                                ) { store.dispatch(BlockEvent.StartPause()) }
                            } else {
                                breakUsed = true
                            }
                        }) {
                            Icon(Icons.Outlined.FreeBreakfast, contentDescription = "Take a break")
                        }
                    }
                    IconButton(onClick = onCreateBlock) {
                        Icon(Icons.Filled.Add, contentDescription = "New block")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (blockingBroken) {
                item {
                    SetupBanner(onClick = onOpenSettings)
                }
            }

            if (pauseLeft > 0) {
                item {
                    PauseBanner(leftMs = pauseLeft, onEnd = { store.dispatch(BlockEvent.EndPause) })
                }
            }

            if (world.blocks.isEmpty()) {
                item {
                    EmptyState(icon = Icons.Outlined.Inbox, title = "No blocks yet")
                    Gap(20)
                    Button(onClick = onCreateBlock, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        WGap(8)
                        Text("Create your first block")
                    }
                }
            }

            items(world.blocks, key = { it.id }) { block ->
                BlockCard(
                    block = block,
                    world = world,
                    store = store,
                    appLabel = targetSummary(block, appLabels),
                    onOpen = { onOpenBlock(block.id) },
                    onRedeem = { store.dispatch(BlockEvent.Redeem(block.id)) },
                    onToggleTimer = { running -> store.dispatch(BlockEvent.TimerControl(block.id, running)) },
                )
            }
        }
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, onDismiss = { loosen = null }) }
    if (breakUsed) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { breakUsed = false },
            title = { Text("No more breaks today") },
            text = { Text("You've had today's break. The next one is available tomorrow.") },
            confirmButton = { TextButton(onClick = { breakUsed = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun PauseBanner(leftMs: Long, onEnd: () -> Unit) {
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FreeBreakfast, contentDescription = null, tint = statusStyleFor(BlockState.UNLOCKED).accent)
            WGap(12)
            Column(Modifier.weight(1f)) {
                Text("On a break", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Soft blocks are back in ${formatRemaining(leftMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEnd) { Text("End now") }
        }
    }
}

@Composable
private fun SetupBanner(onClick: () -> Unit) {
    val style = statusStyleFor(BlockState.EARNED)
    HFCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = style.accent)
            WGap(12)
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            WGap(8)
            Text("Fix", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BlockCard(
    block: Block,
    world: World,
    store: WorldStore,
    appLabel: String,
    onOpen: () -> Unit,
    onRedeem: () -> Unit,
    onToggleTimer: (Boolean) -> Unit,
) {
    if (block.mode != BlockMode.HABIT) rememberTicker() // countdowns move with time, not the World
    val status = blockStatus(block, world, store.engine, store.clock)
    HFCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(block.name, style = MaterialTheme.typography.titleMedium)
                Text(appLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(status)
        }

        Gap(12)
        Text(
            status.line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        status.fraction?.let {
            Gap(8)
            MeterBar(it, color = statusStyleFor(status.tone).accent)
        }

        if (block.mode == BlockMode.LOCKDOWN && store.engine.lockdownRemainingMs(world, block.id, store.clock) == 0L) {
            Gap(14)
            OutlinedButton(
                onClick = { store.dispatch(BlockEvent.StartLockdown(block.id)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                WGap(8)
                Text("Block again for ${formatDuration(block.lockdownMs)}")
            }
        }
        if (block.mode != BlockMode.HABIT || block.state == BlockState.UNLOCKED) return@HFCard

        Gap(14)
        when {
            block.state == BlockState.EARNED -> {
                if (store.engine.redeemRejection(world, block.id, store.clock) == RejectReason.CAP_REACHED) {
                    CapReachedNote(block, world, store)
                } else {
                    Button(onClick = onRedeem, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        WGap(8)
                        Text("Open for ${block.reward.unlockDurationMs / 60_000} min")
                    }
                }
            }
            block.trigger.type == TriggerType.TIMER || block.trigger.type == TriggerType.COMBINED -> {
                val running = world.progress[block.id]?.timerRunning == true
                OutlinedButton(onClick = { onToggleTimer(!running) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    WGap(8)
                    Text(if (running) "Pause timer" else "Start timer")
                }
            }
            block.trigger.type == TriggerType.CHECKLIST -> {
                OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text("Open checklist")
                }
            }
            else -> Unit
        }
    }
}

/** Shown instead of "Open" when the unlock would go past today's limit (I9). */
@Composable
private fun CapReachedNote(block: Block, world: World, store: WorldStore) {
    Text(
        capReachedText(store.engine.unlockUsedTodayMs(world, store.clock), world.settings.dailyCapMs, block.reward.unlockDurationMs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// =====================================================================================
//  Block detail
// =====================================================================================

@Composable
fun BlockDetailScreen(
    world: World,
    store: WorldStore,
    blockId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    guard: HardcoreGuard,
    loop: LoopHabits,
) {
    val block = world.block(blockId) ?: run { onBack(); return }
    val context = LocalContext.current
    val habit = block.mode == BlockMode.HABIT
    if (!habit) rememberTicker()
    val status = blockStatus(block, world, store.engine, store.clock)
    val appLabel = remember(blockId) {
        targetSummary(block, block.targetPackages.associateWith { InstalledApps.labelFor(context, it) })
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingLoopHabit by remember { mutableStateOf(false) }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    val guarded = BlockEdits.guardNeeded(store.engine, world, block, store.clock)
    val hardcore = block.enforcementTier == EnforcementTier.HARDCORE

    /** Runs [action] at once if this block is inert, otherwise after the guard (contract §2.3). */
    fun loosening(title: String, detail: String, action: () -> Unit) {
        if (guarded) loosen = LoosenRequest(title, detail, hardcore, action) else action()
    }

    // Which linked habits aren't every-day ones, so the "Goal days" choice is offered only there.
    val linkedIds = world.items(block.id).mapNotNull { it.externalHabitId }
    var nonDaily by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(linkedIds.isNotEmpty()) {
        if (linkedIds.isEmpty()) return@LaunchedEffect
        nonDaily = withContext(Dispatchers.IO) { loop.read(LocalDate.now()) }
            ?.filterNot { it.daily }?.mapTo(mutableSetOf()) { it.uuid }
            .orEmpty()
    }

    Scaffold(
        topBar = {
            HFTopAppBar(
                title = { Text(block.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit block")
                    }
                    IconButton(onClick = {
                        if (guarded) {
                            loosen = LoosenRequest(
                                title = "Delete this block?",
                                detail = "${block.name} stops blocking for good.",
                                hardcore = hardcore,
                            ) {
                                store.edit { it.deleteBlock(block.id) }
                                onBack()
                            }
                        } else {
                            confirmDelete = true
                        }
                    }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete block")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- status + primary action ----
            HFCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(status)
                    WGap(10)
                    Text(
                        appLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Gap(12)
                Text(status.line, style = MaterialTheme.typography.bodyLarge)
                status.fraction?.let {
                    Gap(8)
                    MeterBar(it, color = statusStyleFor(status.tone).accent)
                }
                if (!habit) {
                    StrictDetails(block, world, store)
                    return@HFCard
                }
                when {
                    block.state == BlockState.EARNED -> {
                        Gap(16)
                        if (store.engine.redeemRejection(world, block.id, store.clock) == RejectReason.CAP_REACHED) {
                            CapReachedNote(block, world, store)
                        } else {
                            Button(
                                onClick = { store.dispatch(BlockEvent.Redeem(block.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                WGap(8)
                                Text("Open ${appLabel.take(18)} for ${block.reward.unlockDurationMs / 60_000} min")
                            }
                        }
                        TextButton(
                            onClick = { store.dispatch(BlockEvent.UserResetEarned(block.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Give up this reward") }
                    }
                    block.state == BlockState.UNLOCKED -> Unit
                    block.trigger.type == TriggerType.TIMER || block.trigger.type == TriggerType.COMBINED -> {
                        val running = world.progress[block.id]?.timerRunning == true
                        Gap(16)
                        Button(
                            onClick = { store.dispatch(BlockEvent.TimerControl(block.id, !running)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                            WGap(8)
                            Text(if (running) "Pause" else "Start timer")
                        }
                    }
                }
            }

            // ---- checklist ----
            if (habit && (block.trigger.type == TriggerType.CHECKLIST || block.trigger.type == TriggerType.COMBINED)) {
                HFCard {
                    Text("Checklist", style = MaterialTheme.typography.titleMedium)
                    val editable = block.state == BlockState.ARMED
                    Gap(4)
                    world.items(block.id).sortedBy { it.order }.forEach { item ->
                        val removeItem = {
                            loosening("Remove \"${item.text}\"?", "The checklist gets easier to finish.") {
                                store.edit { it.removeChecklistItem(item.id) }
                            }
                        }
                        if (item.externalHabitId != null) {
                            LoopLinkedRow(
                                name = item.text,
                                done = item.done,
                                yesterday = item.linkedDay == LinkedDay.YESTERDAY,
                                onYesterdayChange = if (editable) ({ yesterday ->
                                    loosening("Change which day counts?", "A different day's tick will unlock this block.") {
                                        val day = if (yesterday) LinkedDay.YESTERDAY else LinkedDay.TODAY
                                        store.edit { w -> w.updateItem(item.id) { it.copy(linkedDay = day) } }
                                        loop.sync() // re-tick it against the other day
                                    }
                                }) else null,
                                goal = item.countsGoal.takeIf { it || item.externalHabitId in nonDaily },
                                onGoalChange = if (editable) ({ goal ->
                                    loosening("Change what counts?", "Days covered by the habit's goal would count too.") {
                                        store.edit { w -> w.updateItem(item.id) { it.copy(countsGoal = goal) } }
                                        loop.sync()
                                    }
                                }) else null,
                                onRemove = if (editable) removeItem else null,
                            )
                        } else {
                            ChecklistEditRow(
                                item = item,
                                editable = editable,
                                onToggle = { done -> store.dispatch(BlockEvent.ToggleChecklistItem(item.id, done)) },
                                onRename = { text -> store.edit { it.renameChecklistItem(item.id, text) } },
                                onRemove = removeItem,
                            )
                        }
                    }
                    if (editable) {
                        Row {
                            TextButton(onClick = { store.edit { it.addChecklistItem(block.id) } }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                WGap(6)
                                Text("Add task")
                            }
                            TextButton(onClick = { pickingLoopHabit = true }) {
                                Icon(Icons.Filled.Link, contentDescription = null)
                                WGap(6)
                                Text("Link xrnloops habit")
                            }
                        }
                    }
                }
            }

            // ---- reward ----
            if (habit) {
                HFCard {
                    Text("Reward", style = MaterialTheme.typography.titleMedium)
                    KeyValueRow("Unlock", formatDuration(block.reward.unlockDurationMs))
                    KeyValueRow("Use within", formatDuration(block.reward.creditTtlMs))
                }
            }

            // ---- enforcement (sideload only) ----
            if (guard.isSupported()) {
                HFCard {
                    Text("Enforcement", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !hardcore,
                            onClick = {
                                if (hardcore) {
                                    loosening("Make this block soft?", "Settings and uninstalling won't be guarded for it any more.") {
                                        store.edit { it.setEnforcementTier(block.id, EnforcementTier.SOFT) }
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Soft") }
                        SegmentedButton(
                            selected = hardcore,
                            onClick = { store.edit { it.setEnforcementTier(block.id, EnforcementTier.HARDCORE) } },
                            enabled = guard.hasPassword(), // needs a password set in Settings first
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Hardcore") }
                    }
                }
            }

            Gap(8)
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this block?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.edit { it.deleteBlock(block.id) }
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, onDismiss = { loosen = null }) }

    if (pickingLoopHabit) {
        LoopHabitPickerDialog(
            loop = loop,
            alreadyLinked = world.items(block.id).mapNotNullTo(mutableSetOf()) { it.externalHabitId },
            onDismiss = { pickingLoopHabit = false },
            onPick = { habit ->
                pickingLoopHabit = false
                store.edit { it.addLinkedItem(block.id, habit) }
                loop.sync(force = true) // ticks it right away if it's already done today
            },
        )
    }
}

/** Detail-card body for daily-limit / schedule / lockdown blocks: the rule's setting, plus restart for a lockdown. */
@Composable
private fun StrictDetails(block: Block, world: World, store: WorldStore) {
    val running = store.engine.lockdownRemainingMs(world, block.id, store.clock) > 0
    Gap(4)
    when (block.mode) {
        BlockMode.ALLOWANCE -> KeyValueRow("Daily limit", formatDuration(block.dailyAllowanceMs))
        BlockMode.SCHEDULE -> KeyValueRow("Schedule", describeSchedule(block.schedule))
        BlockMode.LOCKDOWN -> KeyValueRow("Length", formatDuration(block.lockdownMs))
        BlockMode.HABIT -> Unit
    }
    if (block.mode == BlockMode.LOCKDOWN && !running) {
        Gap(16)
        Button(onClick = { store.dispatch(BlockEvent.StartLockdown(block.id)) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            WGap(8)
            Text("Block again for ${formatDuration(block.lockdownMs)}")
        }
    }
}

@Composable
private fun ChecklistEditRow(
    item: ChecklistItem,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.done, onCheckedChange = { onToggle(it) }, enabled = editable)
        Column(Modifier.weight(1f)) {
            if (editable) {
                var text by remember(item.id) { mutableStateOf(item.text) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onRename(it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (editable) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove task")
            }
        }
    }
}

// =====================================================================================
//  Settings
// =====================================================================================

@Composable
fun SettingsScreen(
    world: World,
    store: WorldStore,
    guard: HardcoreGuard,
    websites: WebsiteMonitor,
    loop: LoopHabits,
    clockGuard: ClockGuard,
    shared: SharedSync,
) {
    val context = LocalContext.current
    val resumeSignal = rememberResumeSignal()
    val granted = remember(resumeSignal) { AppPermission.entries.associateWith { it.isGranted(context) } }
    val websiteTracking = remember(resumeSignal) { websites.tracking(context) }
    val loopStatus = remember(resumeSignal) { loop.status() }
    val loopSync by loop.lastSync.collectAsStateWithLifecycle()
    val anyLoopLinks = world.checklist.values.any { items -> items.any { it.externalHabitId != null } }
    val readyCount = granted.values.count { it }
    val deviceAdminActive = remember(resumeSignal) { guard.isDeviceAdminActive() }
    val automaticTime = remember(resumeSignal) { clockGuard.automaticTime() }
    val clockSkew = remember(resumeSignal) { clockGuard.check(); clockGuard.skewMs }
    var hasPassword by remember { mutableStateOf(guard.hasPassword()) }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = BlocksBackups.export(store.world.value)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) } }.isSuccess
            }
            backupMessage = if (ok) "Exported ${store.world.value.blocks.size} blocks." else "Couldn't write that file."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            val parsed = text?.let { runCatching { BlocksBackups.import(store.world.value, it) }.getOrNull() }
            backupMessage = if (parsed == null) {
                "That file isn't an xrnhabit backup."
            } else {
                store.edit { w -> BlocksBackups.import(w, text).first }
                if (parsed.second == 0) "Nothing new: every block in it is already here." else "Added ${parsed.second} blocks."
            }
        }
    }

    Scaffold(topBar = { HFTopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HFCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$readyCount of ${AppPermission.entries.size} ready",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Gap(4)
                AppPermission.entries.forEach { perm ->
                    PermRow(perm.label, granted = granted[perm] == true) {
                        runCatching { context.startActivity(perm.settingsIntent(context)) }
                    }
                }
                if (OemAutostart.isKnownOem()) {
                    PermRow("Autostart (${Build.MANUFACTURER})", granted = null) { OemAutostart.open(context) }
                }
                PermRow("Automatic date & time", granted = automaticTime) {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }
                }
                if (clockSkew != 0L) {
                    Text(
                        "The phone's clock was set ${formatDuration(kotlin.math.abs(clockSkew))} " +
                            "${if (clockSkew > 0) "ahead" else "back"} by hand. Blocks keep using the real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (websites.isSupported()) {
                HFCard {
                    Text("Websites", style = MaterialTheme.typography.titleMedium)
                    Gap(4)
                    PermRow(
                        if (websiteTracking == WebsiteTracking.NOT_RUNNING) "Website tracking (not running)" else "Website tracking",
                        granted = websiteTracking == WebsiteTracking.ON,
                    ) {
                        runCatching { context.startActivity(websites.settingsIntent()) }
                    }
                }
            }

            // Only worth a row once Loop is around or a block relies on it — otherwise it's noise.
            if (loopStatus != LoopStatus.NOT_INSTALLED || anyLoopLinks) {
                HFCard {
                    Text("Habit tracker", style = MaterialTheme.typography.titleMedium)
                    Gap(4)
                    PermRow(
                        when (loopStatus) {
                            LoopStatus.READY -> "xrnloops"
                            LoopStatus.NOT_INSTALLED -> "xrnloops (not installed)"
                            LoopStatus.NO_ACCESS -> "xrnloops (signed with another key)"
                        },
                        granted = loopStatus == LoopStatus.READY,
                    ) {
                        loop.launchIntent()?.let { runCatching { context.startActivity(it) } }
                    }
                    loopSync?.let { sync ->
                        rememberTicker(30_000)
                        Text(
                            if (sync.ok) {
                                "Last read ${formatAgo(sync.atWallMs)}"
                            } else {
                                "Couldn't reach xrnloops ${formatAgo(sync.atWallMs)}. Open it once, then come back."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sync.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SyncCard(shared)

            HFCard {
                Text("Daily unlock limit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The most time all unlocks together can give you in a day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(8)
                val cap = world.settings.dailyCapMs
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DAILY_CAP_CHOICES_MIN.forEach { minutes ->
                        val ms = minutes * 60_000
                        FilterChip(
                            selected = cap == ms,
                            onClick = {
                                val apply = { store.edit { w -> w.copy(settings = w.settings.copy(dailyCapMs = ms)) } }
                                if (ms > cap && world.blocks.any { BlockEdits.guardNeeded(store.engine, world, it, store.clock) }) {
                                    loosen = LoosenRequest(
                                        title = "Raise the limit to ${formatDuration(ms)}?",
                                        detail = "Unlocks could give you more time each day.",
                                        hardcore = guard.hasPassword() && world.blocks.any { it.enforcementTier == EnforcementTier.HARDCORE },
                                        apply = { apply() },
                                    )
                                } else {
                                    apply()
                                }
                            },
                            label = { Text(formatDuration(ms)) },
                        )
                    }
                }
                val tooLong = world.blocks.filter { it.mode == BlockMode.HABIT && it.reward.unlockDurationMs > cap }
                if (tooLong.isNotEmpty()) {
                    Gap(8)
                    Text(
                        "Can't open at this limit (their unlock is longer): ${tooLong.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (guard.isSupported()) {
                HFCard {
                    Text("Hardcore mode", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    when {
                        !hasPassword -> PasswordForm(
                            askCurrent = false,
                            submitLabel = "Set password",
                            onSubmit = { _, new ->
                                guard.setPassword(new)
                                hasPassword = true
                                null
                            },
                        )
                        !deviceAdminActive -> {
                            Button(
                                onClick = { (context as? Activity)?.let(guard::requestDeviceAdmin) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Turn on anti-uninstall") }
                        }
                        else -> KeyValueRow("Anti-uninstall", "On")
                    }
                    if (hasPassword) {
                        var changing by remember { mutableStateOf(false) }
                        if (changing) {
                            Gap(8)
                            PasswordForm(
                                askCurrent = true,
                                submitLabel = "Change password",
                                onSubmit = { current, new ->
                                    if (guard.changePassword(current, new)) {
                                        changing = false
                                        null
                                    } else {
                                        "The current password is wrong."
                                    }
                                },
                            )
                        } else {
                            TextButton(onClick = { changing = true }) { Text("Change password") }
                        }
                    }
                }
            }

            HFCard {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Save your blocks to a file, or add blocks from one. Importing only adds blocks; it never changes or removes the ones here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("xrnhabit-blocks-${LocalDate.now()}.json") },
                        enabled = world.blocks.isNotEmpty(),
                    ) { Text("Export") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Import")
                    }
                }
                backupMessage?.let {
                    Gap(4)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, onDismiss = { loosen = null }) }
}

private val DAILY_CAP_CHOICES_MIN = listOf(15L, 30L, 60L, 90L, 120L, 180L)

/**
 * A new password typed twice, so a typo can't lock Settings for good; plus the current one when
 * changing it. [onSubmit] returns an error to show, or null when done.
 */
@Composable
private fun PasswordForm(askCurrent: Boolean, submitLabel: String, onSubmit: (current: String, new: String) -> String?) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = repeat.isNotEmpty() && new != repeat
    @Composable
    fun field(value: String, label: String, isError: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); error = null },
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (askCurrent) field(current, "Current password") { current = it }
    field(new, "New password") { new = it }
    field(repeat, "New password again", isError = mismatch) { repeat = it }
    if (mismatch) Text("The two don't match.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    Gap(8)
    Button(
        enabled = new.isNotBlank() && new == repeat && (!askCurrent || current.isNotEmpty()),
        onClick = { error = onSubmit(current, new) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(submitLabel) }
}

/** The home-server link that shares unlocks, breaks and blocks with the laptop (contract §2.4). */
@Composable
private fun SyncCard(shared: SharedSync) {
    val settings = shared.settings
    val status by shared.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(!settings.isConfigured) }
    var url by remember { mutableStateOf(settings.url) }
    var user by remember { mutableStateOf(settings.user.ifEmpty { "sonn" }) }
    var password by remember { mutableStateOf(settings.password) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    HFCard {
        Text("Sync with laptop", style = MaterialTheme.typography.titleMedium)
        Text(
            "Shares blocks, unlocks, breaks and lockdowns with xrnhabit on your laptop through your home server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        if (editing) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server address") },
                placeholder = { Text("https://your-server/xrnhabit") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Gap(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = url.trim().startsWith("http") && user.isNotBlank() && password.isNotEmpty() && !busy,
                    onClick = {
                        settings.save(url, user, password)
                        editing = false
                        busy = true
                        scope.launch { shared.syncNow(); busy = false }
                    },
                ) { Text("Save and sync") }
                if (settings.isConfigured) TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        } else {
            rememberTicker(30_000)
            Text(
                when {
                    busy -> "Syncing…"
                    status == null -> "Connected to ${settings.url}"
                    status!!.ok -> "Synced ${formatAgo(status!!.atWallMs)} · ${settings.url}"
                    else -> "${status!!.message} (${formatAgo(status!!.atWallMs)})"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status?.ok == false && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch { shared.syncNow(); busy = false } }) { Text("Sync now") }
                TextButton(onClick = { editing = true }) { Text("Change") }
            }
        }
    }
}

/** "just now", "5 min ago", "2 h ago". */
private fun formatAgo(wallMs: Long): String {
    val ago = System.currentTimeMillis() - wallMs
    return if (ago < 60_000) "just now" else "${formatDuration(ago)} ago"
}

@Composable
private fun PermRow(name: String, granted: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (granted) {
                true -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = statusStyleFor(BlockState.UNLOCKED).accent)
                false -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = statusStyleFor(BlockState.EARNED).accent)
                null -> Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WGap(12)
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            if (granted == true) "Done" else "Open",
            style = MaterialTheme.typography.labelLarge,
            color = if (granted == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

// =====================================================================================
//  Create block
// =====================================================================================

/** The create screen's top-level choice; "Set time" splits into LOCKDOWN / SCHEDULE below it. */
private enum class HowItBlocks(val label: String) {
    HABIT("Habit"),
    LIMIT("Daily limit"),
    TIME("Set time"),
}

/**
 * Creates a block, or edits [existing]. An edit keeps the block's kind and how it unlocks (the
 * checklist is edited on the block's own page); a change that loosens it goes through the guard
 * (contract §2.3).
 */
@Composable
fun CreateBlockScreen(
    store: WorldStore,
    websites: WebsiteMonitor,
    loop: LoopHabits,
    guard: HardcoreGuard,
    onDone: () -> Unit,
    existing: Block? = null,
) {
    val context = LocalContext.current
    val editing = existing != null
    val hadTimer = existing?.trigger?.type.let { it == TriggerType.TIMER || it == TriggerType.COMBINED }
    val apps = remember { InstalledApps.launchable(context) }
    var selectedTargets by remember { mutableStateOf(existing?.targetPackages ?: emptySet()) }
    var sites by remember { mutableStateOf(existing?.targetSites ?: emptySet()) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var how by remember {
        mutableStateOf(
            when (existing?.mode) {
                null, BlockMode.HABIT -> HowItBlocks.HABIT
                BlockMode.ALLOWANCE -> HowItBlocks.LIMIT
                BlockMode.SCHEDULE, BlockMode.LOCKDOWN -> HowItBlocks.TIME
            },
        )
    }
    var useChecklist by remember { mutableStateOf(existing?.trigger?.type.let { it == null || it == TriggerType.CHECKLIST || it == TriggerType.COMBINED }) }
    var useTimer by remember { mutableStateOf(hadTimer) }
    var combineMode by remember { mutableStateOf(existing?.trigger?.combineMode ?: CombineMode.AND) }
    var timerMinutes by remember { mutableStateOf(if (hadTimer) (existing!!.trigger.requiredAmount / 60_000).toString() else "30") }
    var unlockMinutes by remember { mutableStateOf(((existing?.reward?.unlockDurationMs ?: Defaults.DEFAULT_UNLOCK_MS) / 60_000).toString()) }
    var checklistItems by remember { mutableStateOf(listOf("")) }
    var linkedHabits by remember { mutableStateOf(listOf<LoopHabit>()) }
    var yesterdayLinks by remember { mutableStateOf(setOf<String>()) } // uuids counting yesterday's tick
    var goalLinks by remember { mutableStateOf(setOf<String>()) } // uuids counting goal-met days
    var pickingLoopHabit by remember { mutableStateOf(false) }
    var useDifferentHabitApp by remember { mutableStateOf(existing?.trigger?.habitPackages?.isNotEmpty() == true) }
    var habitApps by remember { mutableStateOf(existing?.trigger?.habitPackages ?: emptySet()) }
    var allowanceMinutes by remember { mutableStateOf(existing?.takeIf { it.mode == BlockMode.ALLOWANCE }?.let { (it.dailyAllowanceMs / 60_000).toString() } ?: "30") }
    var onSchedule by remember { mutableStateOf(existing?.mode == BlockMode.SCHEDULE) }
    var lockdownMinutes by remember { mutableStateOf(existing?.takeIf { it.mode == BlockMode.LOCKDOWN }?.let { (it.lockdownMs / 60_000).toString() } ?: "60") }
    val existingWindow = existing?.takeIf { it.mode == BlockMode.SCHEDULE }?.schedule
    var scheduleDays by remember { mutableStateOf(existingWindow?.activeDays ?: (1..5).toSet()) }
    var startMinute by remember { mutableIntStateOf(existingWindow?.activeWindow?.startMinuteOfDay ?: (9 * 60)) }
    var endMinute by remember { mutableIntStateOf(existingWindow?.activeWindow?.endMinuteOfDay ?: (17 * 60)) }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    val capMinutes = store.world.collectAsStateWithLifecycle().value.settings.dailyCapMs / 60_000

    val habitMode = how == HowItBlocks.HABIT
    val validItems = checklistItems.map { it.trim() }.filter { it.isNotEmpty() }
    val checklistValid = validItems.isNotEmpty() || linkedHabits.isNotEmpty()
    val timerValid = (timerMinutes.toLongOrNull() ?: 0) > 0
    val hasTargets = selectedTargets.isNotEmpty() || sites.isNotEmpty()
    val allowanceValid = (allowanceMinutes.toLongOrNull() ?: 0) > 0
    val lockdownValid = (lockdownMinutes.toLongOrNull() ?: 0) > 0
    val scheduleValid = scheduleDays.isNotEmpty() && startMinute != endMinute
    val unlockMin = unlockMinutes.toLongOrNull() ?: 0
    val hint = when {
        !hasTargets -> if (websites.isSupported()) "Pick an app or add a website to block" else "Pick at least one app to lock"
        name.isBlank() -> "Give the block a name"
        how == HowItBlocks.LIMIT && !allowanceValid -> "Set how many minutes a day"
        how == HowItBlocks.TIME && onSchedule && scheduleDays.isEmpty() -> "Pick at least one day"
        how == HowItBlocks.TIME && onSchedule && !scheduleValid -> "Start and end time can't be the same"
        how == HowItBlocks.TIME && !onSchedule && !lockdownValid -> "Set how long to block"
        !habitMode -> null
        !useChecklist && !useTimer -> "Pick at least one way to unlock it"
        useChecklist && !editing && !checklistValid -> "Add at least one task"
        useTimer && !timerValid -> "Set the timer minutes"
        useTimer && useDifferentHabitApp && habitApps.isEmpty() -> "Pick which app counts the timer"
        unlockMin <= 0 -> "Set how many minutes an unlock lasts"
        unlockMin > capMinutes -> "An unlock can't be longer than your daily unlock limit ($capMinutes min, in Settings)"
        else -> null
    }
    val canCreate = hint == null

    Scaffold(
        topBar = {
            HFTopAppBar(
                title = { Text(if (editing) "Edit block" else "New block") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!canCreate) return@Button
                        val timerMs = (timerMinutes.toLongOrNull() ?: 30) * 60_000
                        val habitPkgs = if (useTimer && useDifferentHabitApp) habitApps else emptySet()
                        val trigger = when {
                            // Non-HABIT modes never read the trigger (contract §2.2).
                            !habitMode -> Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS)
                            useChecklist && useTimer ->
                                Trigger(TriggerType.COMBINED, timerMs, TriggerUnit.MS, combineMode, habitPkgs)
                            useTimer -> Trigger(TriggerType.TIMER, timerMs, TriggerUnit.MS, habitPackages = habitPkgs)
                            else -> Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS)
                        }
                        val mode = when (how) {
                            HowItBlocks.HABIT -> BlockMode.HABIT
                            HowItBlocks.LIMIT -> BlockMode.ALLOWANCE
                            HowItBlocks.TIME -> if (onSchedule) BlockMode.SCHEDULE else BlockMode.LOCKDOWN
                        }
                        if (existing != null) {
                            val updated = existing.copy(
                                name = name.trim(),
                                targetPackages = selectedTargets,
                                targetSites = sites,
                                trigger = if (habitMode && hadTimer) {
                                    existing.trigger.copy(requiredAmount = timerMs, combineMode = combineMode, habitPackages = habitPkgs)
                                } else {
                                    existing.trigger
                                },
                                reward = existing.reward.copy(unlockDurationMs = unlockMin * 60_000),
                                dailyAllowanceMs = if (mode == BlockMode.ALLOWANCE) (allowanceMinutes.toLongOrNull() ?: 0) * 60_000 else existing.dailyAllowanceMs,
                                lockdownMs = if (mode == BlockMode.LOCKDOWN) (lockdownMinutes.toLongOrNull() ?: 0) * 60_000 else existing.lockdownMs,
                                schedule = if (mode == BlockMode.SCHEDULE) Schedule(scheduleDays, TimeWindow(startMinute, endMinute)) else existing.schedule,
                            )
                            val save = {
                                store.edit { it.replaceBlock(updated) }
                                onDone()
                            }
                            val world = store.world.value
                            if (BlockEdits.loosens(existing, updated) && BlockEdits.guardNeeded(store.engine, world, existing, store.clock)) {
                                loosen = LoosenRequest(
                                    title = "Save these changes?",
                                    detail = "They make ${existing.name} easier to get past.",
                                    hardcore = existing.enforcementTier == EnforcementTier.HARDCORE,
                                    apply = save,
                                )
                            } else {
                                save()
                            }
                            return@Button
                        }
                        val blockId = UUID.randomUUID().toString()
                        store.edit {
                            it.createBlock(
                                id = blockId,
                                name = name.trim(),
                                targetPackages = selectedTargets,
                                targetSites = sites,
                                trigger = trigger,
                                unlockMinutes = unlockMinutes.toLongOrNull() ?: 5,
                                checklistItems = if (habitMode) validItems else emptyList(),
                                linkedHabits = if (habitMode && useChecklist) linkedHabits else emptyList(),
                                yesterdayLinks = yesterdayLinks,
                                goalLinks = goalLinks,
                                mode = mode,
                                dailyAllowanceMs = (allowanceMinutes.toLongOrNull() ?: 0) * 60_000,
                                lockdownMs = (lockdownMinutes.toLongOrNull() ?: 0) * 60_000,
                                schedule = if (mode == BlockMode.SCHEDULE) {
                                    Schedule(scheduleDays, TimeWindow(startMinute, endMinute))
                                } else {
                                    Schedule.ALWAYS
                                },
                            ).withLockdownStarted(blockId, store.clock) // "for a while" starts now
                        }
                        if (habitMode && useChecklist && linkedHabits.isNotEmpty()) loop.sync(force = true)
                        onDone()
                    },
                ) { Text(if (editing) "Save" else "Create block") }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HFCard {
                Text("1 · What to block", style = MaterialTheme.typography.titleMedium)
                Gap(12)
                Text("Apps", style = MaterialTheme.typography.titleSmall)
                Gap(8)
                AppPicker(
                    apps = apps,
                    selected = selectedTargets,
                    onToggle = { pkg ->
                        val wasEmpty = selectedTargets.isEmpty() && sites.isEmpty()
                        selectedTargets = selectedTargets.toggled(pkg)
                        if (wasEmpty && name.isBlank()) {
                            name = apps.firstOrNull { it.packageName == pkg }?.label ?: name
                        }
                    },
                )
                if (websites.isSupported()) {
                    Gap(16)
                    Hairline()
                    Gap(12)
                    Text("Websites", style = MaterialTheme.typography.titleSmall)
                    Gap(8)
                    SiteInput(
                        sites = sites,
                        onAdd = { host ->
                            if (selectedTargets.isEmpty() && sites.isEmpty() && name.isBlank()) name = host
                            sites = sites + host
                        },
                        onRemove = { sites = sites - it },
                    )
                    WebsiteTrackingWarning(websites)
                }
            }

            HFCard {
                Text("2 · Name", style = MaterialTheme.typography.titleMedium)
                Gap(8)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("e.g. Instagram gate") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HFCard {
                Text("3 · How it blocks", style = MaterialTheme.typography.titleMedium)
                Gap(10)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HowItBlocks.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = how == option,
                            onClick = { how = option },
                            enabled = !editing || how == option, // an edit keeps the block's kind
                            shape = SegmentedButtonDefaults.itemShape(i, HowItBlocks.entries.size),
                            icon = {}, // three labels need the room the checkmark would take
                        ) { Text(option.label, maxLines = 1) }
                    }
                }

                when (how) {
                    HowItBlocks.HABIT -> Unit
                    HowItBlocks.LIMIT -> {
                        Gap(12)
                        OutlinedTextField(
                            value = allowanceMinutes,
                            onValueChange = { v -> allowanceMinutes = v.filter(Char::isDigit).take(4) },
                            label = { Text("Minutes per day") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HowItBlocks.TIME -> {
                        Gap(12)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !onSchedule,
                                onClick = { onSchedule = false },
                                enabled = !editing || !onSchedule,
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("For a while") }
                            SegmentedButton(
                                selected = onSchedule,
                                onClick = { onSchedule = true },
                                enabled = !editing || onSchedule,
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("On a schedule") }
                        }
                        Gap(12)
                        if (onSchedule) {
                            ScheduleEditor(
                                days = scheduleDays,
                                onDaysChange = { scheduleDays = it },
                                startMinute = startMinute,
                                endMinute = endMinute,
                                onStartChange = { startMinute = it },
                                onEndChange = { endMinute = it },
                            )
                        } else {
                            LockdownEditor(minutes = lockdownMinutes, onMinutesChange = { lockdownMinutes = it })
                        }
                    }
                }
            }

            if (habitMode && !editing) HFCard {
                Text("How you unlock it", style = MaterialTheme.typography.titleMedium)
                Gap(10)
                Row(
                    Modifier.fillMaxWidth().clickable { useChecklist = !useChecklist },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = useChecklist, onCheckedChange = { useChecklist = it })
                    WGap(8)
                    Text("Checklist", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().clickable { useTimer = !useTimer },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = useTimer, onCheckedChange = { useTimer = it })
                    WGap(8)
                    Text("Timer", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }

                if (useChecklist && useTimer) {
                    Gap(12)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = combineMode == CombineMode.AND,
                            onClick = { combineMode = CombineMode.AND },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Both") }
                        SegmentedButton(
                            selected = combineMode == CombineMode.OR,
                            onClick = { combineMode = CombineMode.OR },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Either") }
                    }
                }
            }

            if (habitMode && useChecklist && !editing) {
                HFCard {
                    Text("Checklist", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    checklistItems.forEachIndexed { i, text ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { v -> checklistItems = checklistItems.toMutableList().also { it[i] = v } },
                                placeholder = { Text("Task ${i + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (checklistItems.size > 1) {
                                IconButton(onClick = {
                                    checklistItems = checklistItems.toMutableList().also { it.removeAt(i) }
                                }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                            }
                        }
                    }
                    linkedHabits.forEach { habit ->
                        LoopLinkedRow(
                            name = habit.name,
                            done = null,
                            yesterday = habit.uuid in yesterdayLinks,
                            onYesterdayChange = { yesterdayLinks = yesterdayLinks.toggled(habit.uuid) },
                            goal = if (habit.daily) null else habit.uuid in goalLinks,
                            onGoalChange = { goalLinks = goalLinks.toggled(habit.uuid) },
                            onRemove = {
                                linkedHabits = linkedHabits - habit
                                yesterdayLinks = yesterdayLinks - habit.uuid
                                goalLinks = goalLinks - habit.uuid
                            },
                        )
                    }
                    Row {
                        TextButton(onClick = { checklistItems = checklistItems + "" }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            WGap(6)
                            Text("Add task")
                        }
                        TextButton(onClick = { pickingLoopHabit = true }) {
                            Icon(Icons.Filled.Link, contentDescription = null)
                            WGap(6)
                            Text("Link xrnloops habit")
                        }
                    }
                }
            }

            if (habitMode && useTimer) {
                HFCard {
                    Text("Timer", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    OutlinedTextField(
                        value = timerMinutes,
                        onValueChange = { v -> timerMinutes = v.filter(Char::isDigit) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Gap(16)
                    Hairline()
                    Gap(12)
                    Row(
                        Modifier.fillMaxWidth().clickable { useDifferentHabitApp = !useDifferentHabitApp },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = useDifferentHabitApp,
                            onCheckedChange = { useDifferentHabitApp = it },
                        )
                        WGap(8)
                        Text(
                            "Count time in a different app",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (useDifferentHabitApp) {
                        Gap(8)
                        AppPicker(
                            apps = apps,
                            selected = habitApps,
                            onToggle = { pkg -> habitApps = habitApps.toggled(pkg) },
                            listHeight = 200.dp,
                        )
                    }
                }
            }

            if (habitMode) HFCard {
                Text("Reward", style = MaterialTheme.typography.titleMedium)
                Gap(8)
                OutlinedTextField(
                    value = unlockMinutes,
                    onValueChange = { v -> unlockMinutes = v.filter(Char::isDigit).take(4) },
                    label = { Text("Unlock minutes") },
                    supportingText = { Text("Up to $capMinutes min, your daily unlock limit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (pickingLoopHabit) {
        LoopHabitPickerDialog(
            loop = loop,
            alreadyLinked = linkedHabits.mapTo(mutableSetOf()) { it.uuid },
            onDismiss = { pickingLoopHabit = false },
            onPick = { habit ->
                pickingLoopHabit = false
                linkedHabits = linkedHabits + habit
            },
        )
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, onDismiss = { loosen = null }) }
}

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value

/** Type a site, press Add/Done; normalized to a bare domain that also covers its subdomains. */
@Composable
private fun SiteInput(sites: Set<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() {
        val host = Sites.normalize(text)
        if (host == null) {
            error = text.isNotBlank()
        } else {
            if (host !in sites) onAdd(host)
            text = ""
            error = false
        }
    }
    if (sites.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sites.sorted().forEach { host ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(host) },
                    label = { Text(host) },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $host", modifier = Modifier.size(16.dp)) },
                )
            }
        }
        Gap(8)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = false },
        placeholder = { Text("e.g. youtube.com") },
        singleLine = true,
        isError = error,
        supportingText = if (error) {
            { Text("That doesn't look like a website address.") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = "Add website")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Website rules do nothing until the Accessibility reader is on; say so where the rule is made. */
@Composable
private fun WebsiteTrackingWarning(websites: WebsiteMonitor) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val tracking = remember(resume) { websites.tracking(context) }
    if (tracking == WebsiteTracking.ON) return
    Gap(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = statusStyleFor(BlockState.EARNED).accent,
            modifier = Modifier.size(18.dp),
        )
        WGap(8)
        Text(
            if (tracking == WebsiteTracking.OFF) "Website tracking is off" else "Website tracking isn't running",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { runCatching { context.startActivity(websites.settingsIntent()) } }) {
            Text(if (tracking == WebsiteTracking.OFF) "Turn on" else "Fix")
        }
    }
}

@Composable
private fun LockdownEditor(minutes: String, onMinutesChange: (String) -> Unit) {
    val presets = listOf(30L, 60L, 120L, 240L)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { m ->
            FilterChip(
                selected = minutes.toLongOrNull() == m,
                onClick = { onMinutesChange(m.toString()) },
                label = { Text(formatDuration(m * 60_000)) },
            )
        }
    }
    Gap(4)
    OutlinedTextField(
        value = minutes,
        onValueChange = { v -> onMinutesChange(v.filter(Char::isDigit).take(5)) },
        label = { Text("Minutes") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScheduleEditor(
    days: Set<Int>,
    onDaysChange: (Set<Int>) -> Unit,
    startMinute: Int,
    endMinute: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) } // "start" | "end"
    Text(
        "Days",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..7).forEach { day ->
            DayToggle(day, selected = day in days, onToggle = { onDaysChange(days.toggledDay(day)) })
        }
    }
    Gap(14)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { editing = "start" }, modifier = Modifier.weight(1f)) {
            Text("From ${minuteText(startMinute)}")
        }
        OutlinedButton(onClick = { editing = "end" }, modifier = Modifier.weight(1f)) {
            Text("To ${minuteText(endMinute)}")
        }
    }
    editing?.let { which ->
        TimeInputDialog(
            initialMinute = if (which == "start") startMinute else endMinute,
            onDismiss = { editing = null },
            onConfirm = { m ->
                if (which == "start") onStartChange(m) else onEndChange(m)
                editing = null
            },
        )
    }
}

private fun Set<Int>.toggledDay(day: Int): Set<Int> = if (day in this) this - day else this + day

@Composable
private fun DayToggle(isoDay: Int, selected: Boolean, onToggle: () -> Unit) {
    val name = java.time.DayOfWeek.of(isoDay).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun TimeInputDialog(initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimeInput(state = state) },
    )
}

// =====================================================================================
//  World edits (pure transforms applied through store.edit { })
// =====================================================================================

private fun World.createBlock(
    id: String,
    name: String,
    targetPackages: Set<String>,
    targetSites: Set<String>,
    trigger: Trigger,
    unlockMinutes: Long,
    checklistItems: List<String>,
    linkedHabits: List<LoopHabit>,
    yesterdayLinks: Set<String>,
    goalLinks: Set<String>,
    mode: BlockMode,
    dailyAllowanceMs: Long,
    lockdownMs: Long,
    schedule: Schedule,
): World {
    val block = Block(
        id = id,
        name = name,
        targetPackages = targetPackages,
        targetSites = targetSites,
        trigger = trigger,
        reward = Reward(unlockDurationMs = unlockMinutes * 60_000),
        schedule = schedule,
        state = BlockState.ARMED,
        mode = mode,
        dailyAllowanceMs = if (mode == BlockMode.ALLOWANCE) dailyAllowanceMs else 0,
        lockdownMs = if (mode == BlockMode.LOCKDOWN) lockdownMs else 0,
    )
    val plain = checklistItems.mapIndexed { i, text ->
        ChecklistItem(id = "$id-i$i", blockId = id, text = text, order = i)
    }
    val items = plain + linkedHabits.mapIndexed { i, habit ->
        val day = if (habit.uuid in yesterdayLinks) LinkedDay.YESTERDAY else LinkedDay.TODAY
        linkedItem(id, habit, order = plain.size + i, day = day).copy(countsGoal = habit.uuid in goalLinks)
    }
    return copy(
        blocks = blocks + block,
        checklist = if (items.isEmpty()) checklist else checklist + (id to items),
    )
}

/** Saves an edited block's settings; its state moves on through events only, so the current one is kept. */
private fun World.replaceBlock(block: Block): World =
    copy(blocks = blocks.map { if (it.id == block.id) block.copy(state = it.state) else it })

private fun World.setEnforcementTier(blockId: String, tier: EnforcementTier): World =
    copy(blocks = blocks.map { if (it.id == blockId) it.copy(enforcementTier = tier) else it })

private fun World.addChecklistItem(blockId: String): World {
    val existing = items(blockId)
    val item = ChecklistItem(
        id = "$blockId-${UUID.randomUUID()}",
        blockId = blockId,
        text = "New task",
        order = existing.size,
    )
    return copy(checklist = checklist + (blockId to existing + item))
}

/** A checklist item only Loop can tick (contract I23); the habit's name is its text. */
private fun linkedItem(blockId: String, habit: LoopHabit, order: Int, day: LinkedDay = LinkedDay.TODAY) = ChecklistItem(
    id = "$blockId-${UUID.randomUUID()}",
    blockId = blockId,
    text = habit.name,
    order = order,
    externalHabitId = habit.uuid,
    linkedDay = day,
)

private fun World.addLinkedItem(blockId: String, habit: LoopHabit): World {
    val existing = items(blockId)
    if (existing.any { it.externalHabitId == habit.uuid }) return this
    return copy(checklist = checklist + (blockId to existing + linkedItem(blockId, habit, order = existing.size)))
}

/** Changes one checklist item, wherever it lives (e.g. which day a linked item counts, I23). */
private fun World.updateItem(itemId: String, transform: (ChecklistItem) -> ChecklistItem): World {
    val entry = checklist.entries.firstOrNull { (_, items) -> items.any { it.id == itemId } } ?: return this
    val updated = entry.value.map { if (it.id == itemId) transform(it) else it }
    return copy(checklist = checklist + (entry.key to updated))
}

private fun World.renameChecklistItem(itemId: String, text: String): World {
    val entry = checklist.entries.firstOrNull { (_, items) -> items.any { it.id == itemId } } ?: return this
    val updated = entry.value.map { if (it.id == itemId) it.copy(text = text) else it }
    return copy(checklist = checklist + (entry.key to updated))
}

private fun World.removeChecklistItem(itemId: String): World {
    val entry = checklist.entries.firstOrNull { (_, items) -> items.any { it.id == itemId } } ?: return this
    val updated = entry.value.filterNot { it.id == itemId }.mapIndexed { i, item -> item.copy(order = i) }
    return copy(checklist = checklist + (entry.key to updated))
}

private fun World.deleteBlock(blockId: String): World = copy(
    blocks = blocks.filterNot { it.id == blockId },
    progress = progress - blockId,
    credits = credits - blockId,
    sessions = sessions - blockId,
    lockdowns = lockdowns - blockId,
    checklist = checklist - blockId,
)
