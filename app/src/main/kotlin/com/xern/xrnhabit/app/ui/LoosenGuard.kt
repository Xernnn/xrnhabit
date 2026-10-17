package com.xern.xrnhabit.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xern.xrnhabit.app.hardcore.HardcoreGuard

/**
 * A change that makes a block easier to get past, held until [LoosenGuardDialog] lets it through
 * (contract §2.3).
 */
data class LoosenRequest(
    val title: String,
    val detail: String,
    /** A HARDCORE block is affected: the hardcore password is asked for instead of a wait. */
    val hardcore: Boolean,
    val apply: () -> Unit,
)

/** How long a SOFT block makes you wait before a loosening change goes through. */
const val LOOSEN_WAIT_MS = 60_000L

/**
 * Friction for loosening a block, the moment you're most likely to regret it: a 60-second wait
 * (leaving the screen starts it over), or the hardcore password for a HARDCORE block.
 */
@Composable
fun LoosenGuardDialog(request: LoosenRequest, guard: HardcoreGuard, onDismiss: () -> Unit) {
    val usePassword = request.hardcore && guard.hasPassword()
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    rememberTicker(250)
    val leftMs = (LOOSEN_WAIT_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)

    // Waiting it out means staying here: going to another app or the home screen cancels.
    val lifecycleOwner = LocalLifecycleOwner.current
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column {
                Text(request.detail)
                Gap(12)
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; wrong = false },
                        label = { Text("Hardcore password") },
                        singleLine = true,
                        isError = wrong,
                        supportingText = if (wrong) ({ Text("Wrong password.") }) else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (leftMs > 0) {
                            "Wait ${(leftMs + 999) / 1000} s. Leaving this screen starts the wait over."
                        } else {
                            "You can go ahead now."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (usePassword) password.isNotEmpty() else leftMs == 0L,
                onClick = {
                    if (usePassword && !guard.verifyPassword(password)) {
                        wrong = true
                    } else {
                        request.apply()
                        onDismiss()
                    }
                },
            ) { Text("Go ahead") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
