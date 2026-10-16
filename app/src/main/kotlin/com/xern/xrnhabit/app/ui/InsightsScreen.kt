package com.xern.xrnhabit.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnhabit.app.data.AppPermission
import com.xern.xrnhabit.app.data.AppUsage
import com.xern.xrnhabit.app.data.DayUsage
import com.xern.xrnhabit.app.data.InstalledApps
import com.xern.xrnhabit.app.data.LocalTelemetry
import com.xern.xrnhabit.app.data.TelemetryEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnhabit.app.data.ScreenTime
import com.xern.xrnhabit.app.data.SiteUsageLog
import com.xern.xrnhabit.app.data.isGranted
import com.xern.xrnhabit.app.data.settingsIntent
import com.xern.xrnhabit.app.web.WebsiteMonitor
import com.xern.xrnhabit.app.web.WebsiteTracking
import com.xern.xrnhabit.app.web.tracking
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.World
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAYS = 7
private const val APPS_COLLAPSED = 8
private const val REFRESH_MS = 60_000L
private val PlotHeight = 132.dp
private val PlotTopPad = 8.dp

@Composable
fun InsightsScreen(world: World, websites: WebsiteMonitor, siteUsage: SiteUsageLog, telemetry: LocalTelemetry) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val hasAccess = remember(resume) { AppPermission.USAGE_ACCESS.isGranted(context) }
    val websiteTracking = remember(resume) { websites.tracking(context) }
    val siteDays by siteUsage.days.collectAsStateWithLifecycle()
    val blockedSites = remember(world.blocks) { world.blocks.flatMapTo(HashSet()) { it.targetSites } }
    // Keyed on resume so returning to the app refetches; the previous value stays on screen meanwhile.
    var usage by remember { mutableStateOf<List<DayUsage>?>(null) }
    LaunchedEffect(hasAccess, resume) {
        while (hasAccess) {
            usage = withContext(Dispatchers.IO) { ScreenTime.lastDays(context, DAYS) }
            delay(REFRESH_MS)
        }
    }
    var history by remember { mutableStateOf<List<TelemetryEvent>>(emptyList()) }
    LaunchedEffect(resume) {
        while (true) {
            history = withContext(Dispatchers.IO) { telemetry.events() }
            delay(REFRESH_MS)
        }
    }
    var selected by rememberSaveable { mutableIntStateOf(DAYS - 1) }
    val blocked = remember(world.blocks) { world.blocks.flatMapTo(HashSet()) { it.targetPackages } }

    Scaffold(topBar = { HFTopAppBar(title = { Text("Insights") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val days = usage
            when {
                !hasAccess -> UsageAccessCard {
                    runCatching { context.startActivity(AppPermission.USAGE_ACCESS.settingsIntent(context)) }
                }
                days.isNullOrEmpty() -> HFCard {
                    Text("Screen time", style = MaterialTheme.typography.titleMedium)
                    Gap(4)
                    Text(
                        "Reading usage data…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val index = selected.coerceIn(days.indices)
                    ScreenTimeCard(days, index, blocked, onSelect = { selected = it })
                    MostUsedCard(days[index], blocked)
                }
            }

            if (websites.isSupported()) {
                // Follows the day picked on the chart; today when there's no chart to pick from.
                val date = days?.takeIf { it.isNotEmpty() }?.let { it[selected.coerceIn(it.indices)].date } ?: LocalDate.now()
                WebsitesCard(date, siteDays, blockedSites, websiteTracking) {
                    runCatching { context.startActivity(websites.settingsIntent()) }
                }
            }

            SectionLabel("xrnhabit")
            val historyDate = days?.takeIf { it.isNotEmpty() }?.let { it[selected.coerceIn(it.indices)].date } ?: LocalDate.now()
            HistoryCard(historyDate, history)
            val usedMin = (world.dailyUnlockUsedMs + world.unlockLog.values.sum()) / 60_000 // I28
            val capMin = world.settings.dailyCapMs / 60_000
            HFCard {
                Text(
                    "Unlock time used today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(4)
                Text("$usedMin / $capMin min", style = MaterialTheme.typography.headlineMedium)
                Gap(10)
                MeterBar(if (capMin == 0L) 0f else usedMin.toFloat() / capMin)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(world.blocks.count { it.state == BlockState.ARMED }.toString(), "Locked", Modifier.weight(1f))
                BigStat(world.blocks.count { it.state == BlockState.EARNED }.toString(), "Ready", Modifier.weight(1f))
                BigStat(world.blocks.count { it.state == BlockState.UNLOCKED }.toString(), "Open", Modifier.weight(1f))
            }
            Gap(8)
        }
    }
}

@Composable
private fun UsageAccessCard(onGrant: () -> Unit) {
    HFCard {
        Text("Screen time", style = MaterialTheme.typography.titleMedium)
        Gap(12)
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Allow usage access") }
    }
}

// ---- screen time: headline + week ------------------------------------------------------

@Composable
private fun ScreenTimeCard(days: List<DayUsage>, selected: Int, blocked: Set<String>, onSelect: (Int) -> Unit) {
    val day = days[selected]
    // Past days only: today is partial and would drag the average down all morning. Zero days
    // are usually just outside the OS's event retention, not a phone left untouched.
    val avgMs = days.dropLast(1).map { it.totalMs }.filter { it > 0 }
        .takeIf { it.isNotEmpty() }?.average()?.toLong()

    HFCard {
        Text(
            "Screen time · ${dayTitle(day.date)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(2)
        Text(formatDuration(day.totalMs), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold))
        if (blocked.isNotEmpty()) {
            val blockedMs = day.apps.filter { it.packageName in blocked }.sumOf { it.foregroundMs }
            Text(
                if (blockedMs > 0) "${formatDuration(blockedMs)} of it in apps you block" else "None of it in apps you block",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(16)
        WeekChart(days, selected, avgMs, onSelect)
        if (avgMs != null) {
            Gap(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(1.5.dp).background(avgLineColor()))
                WGap(8)
                Text(
                    "Daily average ${formatDuration(avgMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun avgLineColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

/**
 * One column per day, one series colour. The selected day is marked by its backing pill and a
 * bold label rather than a second bar colour, so every bar keeps full contrast; its value is
 * the headline above. Tap a column to select it.
 */
@Composable
private fun WeekChart(days: List<DayUsage>, selected: Int, avgMs: Long?, onSelect: (Int) -> Unit) {
    val series = chartSeriesColor()
    val maxMs = maxOf(days.maxOf { it.totalMs }, avgMs ?: 0L).coerceAtLeast(1L)

    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, d ->
                val isSelected = i == selected
                val fraction by animateFloatAsState(d.totalMs.toFloat() / maxMs, label = "bar$i")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .selectable(selected = isSelected, onClick = { onSelect(i) }, role = Role.Tab)
                        .semantics { contentDescription = "${fullDay(d.date)}, ${formatDuration(d.totalMs)}" }
                        .padding(top = PlotTopPad, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(PlotHeight), contentAlignment = Alignment.BottomCenter) {
                        if (d.totalMs > 0) {
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .heightIn(min = 2.dp)
                                    .fillMaxHeight(fraction)
                                    .background(series, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                            )
                        }
                    }
                    Gap(6)
                    Text(
                        if (d.date == LocalDate.now()) "Today" else d.date.format(ShortDay),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        // Baseline, then the average reference line, drawn over the columns.
        Box(
            Modifier
                .offset(y = PlotTopPad + PlotHeight)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
        if (avgMs != null) {
            Box(
                Modifier
                    .offset(y = PlotTopPad + PlotHeight * (1f - avgMs.toFloat() / maxMs))
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(avgLineColor()),
            )
        }
    }
}

// ---- screen time: per app ----------------------------------------------------------------

@Composable
private fun MostUsedCard(day: DayUsage, blocked: Set<String>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Sub-minute blips (a share sheet, a permission prompt) still count toward the total above.
    val apps = day.apps.filter { it.foregroundMs >= 60_000 }
    val shown = if (showAll) apps else apps.take(APPS_COLLAPSED)
    val maxMs = apps.firstOrNull()?.foregroundMs ?: 1L

    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Most used", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                dayTitle(day.date),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (apps.isEmpty()) {
            Gap(6)
            Text(
                "No apps yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Gap(4)
            shown.forEach { AppUsageRow(it, it.foregroundMs.toFloat() / maxMs, it.packageName in blocked) }
        }
        if (apps.size > APPS_COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show less" else "Show all ${apps.size} apps")
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsage, fraction: Float, isBlocked: Boolean) =
    UsageRow(app.label, app.foregroundMs, fraction, isBlocked) { AppIcon(app.packageName, size = 36.dp) }

@Composable
private fun SiteUsageRow(host: String, ms: Long, fraction: Float, isBlocked: Boolean) =
    UsageRow(host, ms, fraction, isBlocked) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

@Composable
private fun UsageRow(label: String, ms: Long, fraction: Float, isBlocked: Boolean, leading: @Composable () -> Unit) {
    val series = chartSeriesColor()
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "usageBar")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        WGap(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBlocked) {
                    WGap(8)
                    BlockedTag()
                }
            }
            Gap(6)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(series.copy(alpha = 0.14f), RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(6.dp)
                        .background(series, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
                )
            }
        }
        WGap(12)
        Text(
            formatDuration(ms),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * Time per website for the selected day. Android keeps no per-site history, so this only covers
 * time since website tracking (the Accessibility reader) was switched on.
 */
@Composable
private fun WebsitesCard(
    date: LocalDate,
    perDay: Map<Long, Map<String, Long>>,
    blockedSites: Set<String>,
    tracking: WebsiteTracking,
    onTurnOn: () -> Unit,
) {
    val trackingOn = tracking == WebsiteTracking.ON
    var showAll by rememberSaveable { mutableStateOf(false) }
    val sites = perDay[date.toEpochDay()].orEmpty().entries
        .filter { it.value >= 60_000 }
        .sortedByDescending { it.value }
    val shown = if (showAll) sites else sites.take(APPS_COLLAPSED)
    val maxMs = sites.firstOrNull()?.value ?: 1L

    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Websites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                dayTitle(date),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !trackingOn -> {
                Gap(12)
                Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tracking == WebsiteTracking.OFF) "Turn on website tracking" else "Website tracking isn't running")
                }
            }
            sites.isEmpty() -> {
                Gap(6)
                Text(
                    "No websites yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Gap(4)
                shown.forEach { (host, ms) ->
                    SiteUsageRow(host, ms, ms.toFloat() / maxMs, blockedSites.any { Sites.matches(host, it) })
                }
            }
        }
        if (trackingOn && sites.size > APPS_COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show less" else "Show all ${sites.size} sites")
            }
        }
    }
}

@Composable
private fun BlockedTag() {
    val style = statusStyleFor(BlockState.ARMED)
    Row(
        Modifier
            .background(style.container, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = style.content, modifier = Modifier.size(11.dp))
        WGap(3)
        Text("Blocked", style = MaterialTheme.typography.labelSmall, color = style.content)
    }
}

// ---- formatting ------------------------------------------------------------------------------

private val ShortDay = DateTimeFormatter.ofPattern("EEE")
private val LongDay = DateTimeFormatter.ofPattern("EEEE, d MMM")

/**
 * What xrnhabit itself saw on [date] (the day picked on the chart): habits done, attempts to open
 * a blocked app and which apps, unlock time used. Plus the run of days with a habit done.
 */
@Composable
private fun HistoryCard(date: LocalDate, events: List<TelemetryEvent>) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    fun TelemetryEvent.date(): LocalDate = Instant.ofEpochMilli(atWallMs).atZone(zone).toLocalDate()
    val onDay = events.filter { it.date() == date }
    val habits = onDay.count { it is TelemetryEvent.HabitDone }
    val blocked = onDay.filterIsInstance<TelemetryEvent.Blocked>()
    val unlockMs = onDay.filterIsInstance<TelemetryEvent.UnlockUsed>().sumOf { it.ms }
    val habitDays = events.filterIsInstance<TelemetryEvent.HabitDone>().mapTo(HashSet()) { it.date() }
    val today = LocalDate.now(zone)
    // A streak isn't broken until today ends without a habit, so it may still run from yesterday.
    var day = if (today in habitDays) today else today.minusDays(1)
    var streak = 0
    while (day in habitDays) {
        streak++
        day = day.minusDays(1)
    }
    val topBlocked = remember(blocked) {
        blocked.groupingBy { it.pkg }.eachCount().entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { (pkg, n) -> "${InstalledApps.labelFor(context, pkg)} ${n}×" }
    }

    HFCard {
        Text(
            "Your habits · ${dayTitle(date)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InlineStat(habits.toString(), "Habits done", Modifier.weight(1f))
            InlineStat(blocked.size.toString(), "Times blocked", Modifier.weight(1f))
            InlineStat(formatDuration(unlockMs), "Unlocked", Modifier.weight(1f))
        }
        if (topBlocked.isNotEmpty()) {
            Gap(10)
            Text(
                "Most tried: $topBlocked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(6)
        Text(
            when (streak) {
                0 -> "No habit streak yet"
                1 -> "1 day in a row with a habit done"
                else -> "$streak days in a row with a habit done"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A number and its label inside a card ([BigStat] is a card of its own). */
@Composable
private fun InlineStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dayTitle(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> date.format(LongDay)
}

private fun fullDay(date: LocalDate): String = date.format(LongDay)
