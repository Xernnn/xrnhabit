package com.xern.xrnhabit.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xern.xrnhabit.app.loop.LoopHabit
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.loop.LoopStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Loading → loaded habits, or a failed read (Loop still starting, or refusing us). */
private sealed interface PickerState {
    data object Loading : PickerState
    data object Failed : PickerState
    data class Loaded(val habits: List<LoopHabit>) : PickerState
}

/**
 * Picks a Loop habit to link to a checklist item (contract I23). Archived habits are hidden;
 * "at most" habits are shown but can't be picked, since Loop never counts them as done.
 */
@Composable
fun LoopHabitPickerDialog(
    loop: LoopHabits,
    alreadyLinked: Set<String>,
    onDismiss: () -> Unit,
    onPick: (LoopHabit) -> Unit,
) {
    val status = remember { loop.status() }
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }
    LaunchedEffect(status) {
        // When xrnloops is missing or refusing us, the dialog explains that instead; nothing to read.
        if (status != LoopStatus.READY) return@LaunchedEffect
        state = withContext(Dispatchers.IO) { loop.read(LocalDate.now()) }
            ?.let { PickerState.Loaded(it.filterNot(LoopHabit::archived)) }
            ?: PickerState.Failed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an xrnloops habit") },
        text = {
            val s = state
            when {
                status == LoopStatus.NOT_INSTALLED -> Text(
                    "Install the xrnloops app first. The Play Store " +
                        "Loop can't share its habits.",
                )
                status == LoopStatus.NO_ACCESS -> Text(
                    "xrnloops is installed but won't share its habits. Both apps must be " +
                        "signed with the same key.",
                )
                s is PickerState.Loading -> CircularProgressIndicator()
                s is PickerState.Failed -> Text("Couldn't read xrnloops' habits. Open xrnloops once, then try again.")
                s is PickerState.Loaded && s.habits.isEmpty() -> Text("xrnloops has no habits yet.")
                s is PickerState.Loaded -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(s.habits, key = { it.uuid }) { habit ->
                        val linked = habit.uuid in alreadyLinked
                        val enabled = habit.countable && !linked
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onPick(habit) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                habit.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when {
                                linked -> Text("Already in this checklist", style = MaterialTheme.typography.labelSmall)
                                !habit.countable -> Text("\"At most\" habits can't unlock", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A Loop-linked row in a checklist editor: no checkbox to tick, since only Loop can (I23). */
@Composable
fun LoopLinkedRow(name: String, done: Boolean?, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Link,
            contentDescription = null,
            tint = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                when (done) {
                    true -> "Done in xrnloops today"
                    false -> "xrnloops habit · tick it there"
                    null -> "xrnloops habit"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove xrnloops habit")
            }
        }
    }
}
