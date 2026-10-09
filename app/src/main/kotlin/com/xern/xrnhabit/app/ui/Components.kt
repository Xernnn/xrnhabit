@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xern.xrnhabit.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xern.xrnhabit.app.data.InstalledApp
import com.xern.xrnhabit.app.ui.theme.ChartPalette
import com.xern.xrnhabit.app.ui.theme.StatusPalette
import com.xern.xrnhabit.app.ui.theme.StatusStyle
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.CombineMode
import com.xern.xrnhabit.domain.TriggerType
import com.xern.xrnhabit.domain.World

/** Outer padding for every screen body. */
val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * Bumps on every `ON_RESUME` so Settings rows can re-read permission state the user changed
 * while they were off in a system screen.
 */
@Composable
fun rememberResumeSignal(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

// ---- text helpers -----------------------------------------------------------------------

fun stateVerbose(state: BlockState): String = when (state) {
    BlockState.ARMED -> "Locked"
    BlockState.EARNED -> "Ready to open"
    BlockState.UNLOCKED -> "Open now"
}

fun stateIcon(state: BlockState): ImageVector = when (state) {
    BlockState.ARMED -> Icons.Filled.Lock
    BlockState.EARNED -> Icons.Filled.Bolt
    BlockState.UNLOCKED -> Icons.Filled.LockOpen
}

@Composable
fun statusStyleFor(state: BlockState): StatusStyle {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    return when (state) {
        BlockState.ARMED -> if (dark) StatusPalette.lockedDark else StatusPalette.lockedLight
        BlockState.EARNED -> if (dark) StatusPalette.readyDark else StatusPalette.readyLight
        BlockState.UNLOCKED -> if (dark) StatusPalette.openDark else StatusPalette.openLight
    }
}

@Composable
fun chartSeriesColor(): Color =
    if (MaterialTheme.colorScheme.background.luminanceIsDark()) ChartPalette.seriesDark else ChartPalette.seriesLight

private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

private fun checklistFraction(block: Block, world: World): Float {
    val items = world.items(block.id)
    return if (items.isEmpty()) 0f else items.count { it.done }.toFloat() / items.size
}

private fun timerFraction(block: Block, world: World): Float {
    val done = world.progress[block.id]?.accumulatedMs ?: 0
    return if (block.trigger.requiredAmount == 0L) 0f else (done.toFloat() / block.trigger.requiredAmount).coerceIn(0f, 1f)
}

/** "3 of 4 done" / "18 of 30 min" / "4:12 left" — the human-readable progress line. */
fun progressLine(block: Block, world: World): String = when {
    block.state == BlockState.UNLOCKED -> {
        val s = world.sessions[block.id] ?: return stateVerbose(block.state)
        "${formatMs(s.durationMs)} unlock window"
    }
    block.state == BlockState.EARNED ->
        "${block.reward.unlockDurationMs / 60_000}-min unlock ready"
    block.trigger.type == TriggerType.CHECKLIST -> {
        val items = world.items(block.id)
        "${items.count { it.done }} of ${items.size} tasks done"
    }
    block.trigger.type == TriggerType.TIMER -> {
        val done = world.progress[block.id]?.accumulatedMs ?: 0
        "${done / 60_000} of ${block.trigger.requiredAmount / 60_000} min"
    }
    block.trigger.type == TriggerType.COMBINED -> {
        val items = world.items(block.id)
        val doneMin = (world.progress[block.id]?.accumulatedMs ?: 0) / 60_000
        val reqMin = block.trigger.requiredAmount / 60_000
        val joiner = if (block.trigger.combineMode == CombineMode.AND) "and" else "or"
        "${items.count { it.done }} of ${items.size} tasks $joiner $doneMin of $reqMin min"
    }
    else -> stateVerbose(block.state)
}

fun triggerFraction(block: Block, world: World): Float = when (block.trigger.type) {
    TriggerType.CHECKLIST -> checklistFraction(block, world)
    TriggerType.TIMER -> timerFraction(block, world)
    TriggerType.COMBINED -> {
        val checklist = checklistFraction(block, world)
        val timer = timerFraction(block, world)
        if (block.trigger.combineMode == CombineMode.AND) minOf(checklist, timer) else maxOf(checklist, timer)
    }
    TriggerType.COUNT -> 0f
}

/** A block's apps and websites at a glance: "Instagram + youtube.com", "Reddit + 2 more". */
fun targetSummary(block: Block, labels: Map<String, String>): String =
    summarizeNames(block.targetPackages.map { labels[it] ?: it } + block.targetSites.sorted(), empty = "Nothing")

private fun summarizeNames(names: List<String>, empty: String): String {
    if (names.isEmpty()) return empty
    return when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} + ${names[1]}"
        else -> "${names[0]} + ${names.size - 1} more"
    }
}

fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ---- building blocks ------------------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * [TopAppBar] with its container colour pinned to the screen background instead of the
 * default surface tone, so there's no visible seam between the bar and the scrolling content
 * underneath it.
 */
@Composable
fun HFTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A soft rounded container. The main layout unit on every screen. */
@Composable
fun HFCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(padding),
        content = content,
    )
}

/** Rounded progress meter. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "meter")
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
fun StatusChip(state: BlockState, modifier: Modifier = Modifier) =
    StatusChip(stateVerbose(state), stateIcon(state), state, modifier)

@Composable
fun StatusChip(status: BlockStatusUi, modifier: Modifier = Modifier) =
    StatusChip(status.label, status.icon, status.tone, modifier)

@Composable
fun StatusChip(label: String, icon: ImageVector, tone: BlockState, modifier: Modifier = Modifier) {
    val style = statusStyleFor(tone)
    val container by animateColorAsState(style.container, label = "chipBg")
    Row(
        modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = style.content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    HFCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Gap(height: Int = 12) = Spacer(Modifier.height(height.dp))

@Composable
fun WGap(width: Int = 12) = Spacer(Modifier.width(width.dp))

// ---- app picker ------------------------------------------------------------------------

/**
 * Search + multi-select app list with icons, and the current selection shown as removable
 * chips above it. Used both for the apps a block locks and for an optional "do the habit in a
 * different app" picker — selection is just a `Set<String>` of package names either way.
 */
@Composable
fun AppPicker(
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = "Search apps",
    listHeight: Dp = 260.dp,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }
    Column(modifier.fillMaxWidth()) {
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected.forEach { pkg ->
                    val label = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    InputChip(
                        selected = true,
                        onClick = { onToggle(pkg) },
                        label = { Text(label) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $label", modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
            Gap(8)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(searchPlaceholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(8)
        LazyColumn(Modifier.fillMaxWidth().height(listHeight)) {
            items(filtered, key = { it.packageName }) { app ->
                AppPickerRow(app, checked = app.packageName in selected, onToggle = { onToggle(app.packageName) })
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(width = 96, height = 96).asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun AppPickerRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        WGap(12)
        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
