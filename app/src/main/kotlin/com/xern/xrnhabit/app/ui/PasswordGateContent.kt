package com.xern.xrnhabit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xern.xrnhabit.app.ui.theme.XrnhabitTheme

/**
 * Shown over the system Settings app while a HARDCORE block is armed (brainstorm §4.5) — the
 * only way past it is the app password or backing out to the home screen. Shares the same
 * theme/layout language as [BlockOverlayContent] since it's the same kind of surface: a plain,
 * unavoidable overlay.
 */
@Composable
fun PasswordGateContent(showError: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    XrnhabitTheme {
        var input by remember { mutableStateOf("") }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Settings locked",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                isError = showError,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showError) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wrong password.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSubmit(input) }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock")
            }
            TextButton(onClick = onCancel) { Text("Back to Home") }
        }
    }
}
