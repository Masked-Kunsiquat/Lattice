package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun JournalHistoryScreen(
    viewModel: JournalHistoryViewModel,
    onOpenEntry: (UUID) -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteEntry by remember { mutableStateOf<JournalEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.deleteEvent.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = "Entry deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete(event.entry)
            }
        }
    }

    pendingDeleteEntry?.let { entryToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("Delete entry?") },
            text = { Text("This entry will be deleted. You can undo using the snackbar.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEntry(entryToDelete)
                        pendingDeleteEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteEntry = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No entries yet. Start writing in the Journal tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        onTap = { onOpenEntry(entry.id) },
                        onDeleteRequest = { pendingDeleteEntry = entry },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryCard(
    entry: JournalEntry,
    onTap: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteRequest()
                false  // don't dismiss — wait for confirmation dialog
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete entry",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Surface {
            ListItem(
                overlineContent = {
                    Text(
                        "${fmt.format(Date(entry.timestamp))}  ·  ${entry.moodLabel.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(
                        entry.content?.let { if (it.length > 80) "${it.take(80)}…" else it } ?: "Mood log (no text)",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = when {
                    entry.reframedContent != null -> {
                        {
                            Text(
                                "✦ Reframed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    entry.cognitiveDistortions.isNotEmpty() -> {
                        {
                            Text(
                                entry.cognitiveDistortions.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTap),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Card – with distortions (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewEntryCard() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryCard(
            entry = JournalEntry(
                id = UUID.randomUUID(),
                timestamp = 1_700_000_000_000L,
                content = "Someone asked if I was well. I said yes. The answer felt dishonest.",
                valence = -0.6f,
                arousal = -0.4f,
                moodLabel = "SOMBER",
                embedding = FloatArray(384),
                cognitiveDistortions = listOf("Emotional Reasoning", "Mind Reading"),
            ),
            onTap = {},
            onDeleteRequest = {},
        )
    }
}

@Preview(name = "Card – reframed (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewEntryCardReframed() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryCard(
            entry = JournalEntry(
                id = UUID.randomUUID(),
                timestamp = 1_700_000_000_000L,
                content = "I did not reply. There is nothing worth saying.",
                valence = -0.7f,
                arousal = -0.5f,
                moodLabel = "SOMBER",
                embedding = FloatArray(384),
                reframedContent = "I'm not obligated to respond to every message.",
            ),
            onTap = {},
            onDeleteRequest = {},
        )
    }
}
