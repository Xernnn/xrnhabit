@file:OptIn(ExperimentalMaterial3Api::class)

package com.xern.xrnhabit.app.ui

import android.app.Activity
import android.os.Build
import android.text.format.DateFormat
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.xern.xrnhabit.app.web.WebsiteMonitor
import com.xern.xrnhabit.app.web.WebsiteTracking
import com.xern.xrnhabit.app.web.tracking
import com.xern.xrnhabit.app.data.AppPermission
import com.xern.xrnhabit.app.data.InstalledApps
import com.xern.xrnhabit.app.data.OemAutostart
import com.xern.xrnhabit.app.data.WorldStore
import com.xern.xrnhabit.app.data.isGranted
import com.xern.xrnhabit.app.data.settingsIntent
import com.xern.xrnhabit.app.hardcore.HardcoreGuard
import com.xern.xrnhabit.app.loop.LoopHabit
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.loop.LoopStatus
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.ChecklistItem
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.Defaults
import com.xern.xrnhabit.domain.EnforcementTier
import com.xern.xrnhabit.domain.Reward
import com.xern.xrnhabit.domain.Schedule
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.TimeWindow
import com.xern.xrnhabit.domain.Trigger
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.TriggerUnit
import com.xern.xrnhabit.domain.World
import java.util.UUID

// =====================================================================================
//  Blocks
// =====================================================================================

@Composable
fun BlocksScreen(
    world: World,
    store: WorldStore,
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

    Scaffold(
        topBar = {
            HFTopAppBar(
                title = { Text("xrnhabit") },
                actions = {
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
                Button(onClick = onRedeem, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    WGap(8)
                    Text("Open for ${block.reward.unlockDurationMs / 60_000} min")
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

// =====================================================================================
//  Block detail
// =====================================================================================

@Composable
fun BlockDetailScreen(
    world: World,
    store: WorldStore,
    blockId: String,
    onBack: () -> Unit,
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
                    IconButton(onClick = { confirmDelete = true }) {
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
                        Button(
                            onClick = { store.dispatch(BlockEvent.Redeem(block.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            WGap(8)
                            Text("Open ${appLabel.take(18)} for ${block.reward.unlockDurationMs / 60_000} min")
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
                        if (item.externalHabitId != null) {
                            LoopLinkedRow(
                                name = item.text,
                                done = item.done,
                                onRemove = if (editable) ({ store.edit { it.removeChecklistItem(item.id) } }) else null,
                            )
                        } else {
                            ChecklistEditRow(
                                item = item,
                                editable = editable,
                                onToggle = { done -> store.dispatch(BlockEvent.ToggleChecklistItem(item.id, done)) },
                                onRename = { text -> store.edit { it.renameChecklistItem(item.id, text) } },
                                onRemove = { store.edit { it.removeChecklistItem(item.id) } },
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
                            selected = block.enforcementTier == EnforcementTier.SOFT,
                            onClick = { store.edit { it.setEnforcementTier(block.id, EnforcementTier.SOFT) } },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Soft") }
                        SegmentedButton(
                            selected = block.enforcementTier == EnforcementTier.HARDCORE,
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
fun SettingsScreen(world: World, store: WorldStore, guard: HardcoreGuard, websites: WebsiteMonitor, loop: LoopHabits) {
    val context = LocalContext.current
    val resumeSignal = rememberResumeSignal()
    val granted = remember(resumeSignal) { AppPermission.entries.associateWith { it.isGranted(context) } }
    val websiteTracking = remember(resumeSignal) { websites.tracking(context) }
    val loopStatus = remember(resumeSignal) { loop.status() }
    val anyLoopLinks = world.checklist.values.any { items -> items.any { it.externalHabitId != null } }
    val readyCount = granted.values.count { it }
    val deviceAdminActive = remember(resumeSignal) { guard.isDeviceAdminActive() }
    var hasPassword by remember { mutableStateOf(guard.hasPassword()) }
    var newPassword by remember { mutableStateOf("") }

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
                }
            }

            HFCard {
                Text("Behavior", style = MaterialTheme.typography.titleMedium)
                KeyValueRow("Daily unlock cap", "${world.settings.dailyCapMs / 60_000} min")
            }

            if (guard.isSupported()) {
                HFCard {
                    Text("Hardcore mode", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    when {
                        !hasPassword -> {
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text("New password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Gap(8)
                            Button(
                                enabled = newPassword.isNotBlank(),
                                onClick = {
                                    guard.setPassword(newPassword)
                                    hasPassword = true
                                    newPassword = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Set password") }
                        }

                        !deviceAdminActive -> {
                            Button(
                                onClick = { (context as? Activity)?.let(guard::requestDeviceAdmin) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Turn on anti-uninstall") }
                        }

                        else -> KeyValueRow("Anti-uninstall", "On")
                    }
                }
            }
        }
    }
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

@Composable
fun CreateBlockScreen(store: WorldStore, websites: WebsiteMonitor, loop: LoopHabits, onDone: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launchable(context) }
    var selectedTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var name by remember { mutableStateOf("") }
    var how by remember { mutableStateOf(HowItBlocks.HABIT) }
    var useChecklist by remember { mutableStateOf(true) }
    var useTimer by remember { mutableStateOf(false) }
    var combineMode by remember { mutableStateOf(CombineMode.AND) }
    var timerMinutes by remember { mutableStateOf("30") }
    var unlockMinutes by remember { mutableStateOf("5") }
    var checklistItems by remember { mutableStateOf(listOf("")) }
    var linkedHabits by remember { mutableStateOf(listOf<LoopHabit>()) }
    var pickingLoopHabit by remember { mutableStateOf(false) }
    var useDifferentHabitApp by remember { mutableStateOf(false) }
    var habitApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allowanceMinutes by remember { mutableStateOf("30") }
    var onSchedule by remember { mutableStateOf(false) }
    var lockdownMinutes by remember { mutableStateOf("60") }
    var scheduleDays by remember { mutableStateOf((1..5).toSet()) }
    var startMinute by remember { mutableIntStateOf(9 * 60) }
    var endMinute by remember { mutableIntStateOf(17 * 60) }

    val habitMode = how == HowItBlocks.HABIT
    val validItems = checklistItems.map { it.trim() }.filter { it.isNotEmpty() }
    val checklistValid = validItems.isNotEmpty() || linkedHabits.isNotEmpty()
    val timerValid = (timerMinutes.toLongOrNull() ?: 0) > 0
    val hasTargets = selectedTargets.isNotEmpty() || sites.isNotEmpty()
    val allowanceValid = (allowanceMinutes.toLongOrNull() ?: 0) > 0
    val lockdownValid = (lockdownMinutes.toLongOrNull() ?: 0) > 0
    val scheduleValid = scheduleDays.isNotEmpty() && startMinute != endMinute
    val hint = when {
        !hasTargets -> if (websites.isSupported()) "Pick an app or add a website to block" else "Pick at least one app to lock"
        name.isBlank() -> "Give the block a name"
        how == HowItBlocks.LIMIT && !allowanceValid -> "Set how many minutes a day"
        how == HowItBlocks.TIME && onSchedule && scheduleDays.isEmpty() -> "Pick at least one day"
        how == HowItBlocks.TIME && onSchedule && !scheduleValid -> "Start and end time can't be the same"
        how == HowItBlocks.TIME && !onSchedule && !lockdownValid -> "Set how long to block"
        !habitMode -> null
        !useChecklist && !useTimer -> "Pick at least one way to unlock it"
        useChecklist && !checklistValid -> "Add at least one task"
        useTimer && !timerValid -> "Set the timer minutes"
        useTimer && useDifferentHabitApp && habitApps.isEmpty() -> "Pick which app counts the timer"
        else -> null
    }
    val canCreate = hint == null

    Scaffold(
        topBar = {
            HFTopAppBar(
                title = { Text("New block") },
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
                ) { Text("Create block") }
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
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("For a while") }
                            SegmentedButton(
                                selected = onSchedule,
                                onClick = { onSchedule = true },
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

            if (habitMode) HFCard {
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

            if (habitMode && useChecklist) {
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
                        LoopLinkedRow(name = habit.name, done = null, onRemove = { linkedHabits = linkedHabits - habit })
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
                    onValueChange = { v -> unlockMinutes = v.filter(Char::isDigit) },
                    label = { Text("Unlock minutes") },
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
    val items = plain + linkedHabits.mapIndexed { i, habit -> linkedItem(id, habit, order = plain.size + i) }
    return copy(
        blocks = blocks + block,
        checklist = if (items.isEmpty()) checklist else checklist + (id to items),
    )
}

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
private fun linkedItem(blockId: String, habit: LoopHabit, order: Int) = ChecklistItem(
    id = "$blockId-${UUID.randomUUID()}",
    blockId = blockId,
    text = habit.name,
    order = order,
    externalHabitId = habit.uuid,
)

private fun World.addLinkedItem(blockId: String, habit: LoopHabit): World {
    val existing = items(blockId)
    if (existing.any { it.externalHabitId == habit.uuid }) return this
    return copy(checklist = checklist + (blockId to existing + linkedItem(blockId, habit, order = existing.size)))
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
