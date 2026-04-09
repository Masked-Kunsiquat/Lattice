package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen lock gate shown when biometric authentication is required.
 *
 * On first composition [onUnlockClick] is triggered automatically via [LaunchedEffect]
 * so the system biometric prompt appears without the user needing to tap anything.
 * The Unlock button allows re-triggering the prompt after a user-initiated cancel.
 */
@Composable
fun LockScreen(onUnlockClick: () -> Unit) {
    // Auto-trigger the system prompt as soon as the lock screen enters composition.
    // If the user cancels, the screen stays and they can tap Unlock to try again.
    LaunchedEffect(Unit) { onUnlockClick() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = "Lattice is locked",
                    style = MaterialTheme.typography.headlineSmall,
                )

                Text(
                    text = "Verify your identity to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.size(8.dp))

                Button(onClick = onUnlockClick) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock")
                }
            }
        }
    }
}
