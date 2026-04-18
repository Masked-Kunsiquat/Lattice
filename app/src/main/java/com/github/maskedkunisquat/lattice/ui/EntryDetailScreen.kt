package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Public entry point — collects ViewModel state and delegates to EntryDetailContent.
@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenPerson: (UUID) -> Unit,
) {
    val entryState by viewModel.entryState.collectAsStateWithLifecycle()
    val reframeState by viewModel.reframeState.collectAsStateWithLifecycle()
    val modelLoadState by viewModel.modelLoadState.collectAsStateWithLifecycle()
    val tagsData by viewModel.tagsData.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.deletedEvent.collect { onBack() }
    }

    LaunchedEffect(entryState) {
        if (entryState is EntryDetailState.NotFound) onBack()
    }

    EntryDetailContent(
        entryState = entryState,
        reframeState = reframeState,
        modelLoadState = modelLoadState,
        tagsData = tagsData,
        onBack = onBack,
        onEdit = onEdit,
        onDelete = viewModel::deleteEntry,
        onReframe = viewModel::requestReframe,
        onApplyReframe = viewModel::acceptReframe,
        onDismissReframe = viewModel::dismissReframe,
        onConfirmMood = viewModel::confirmMoodCoordinates,
        onOpenPerson = onOpenPerson,
    )
}

// Stateless inner composable — all mutable state lives in the ViewModel or local remember.
// Previews target this directly with stub state.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDetailContent(
    entryState: EntryDetailState,
    reframeState: ReframeState,
    modelLoadState: ModelLoadState,
    tagsData: EntryTagsData,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReframe: () -> Unit,
    onApplyReframe: (String) -> Unit,
    onDismissReframe: () -> Unit,
    onConfirmMood: (valence: Float, arousal: Float) -> Unit,
    onOpenPerson: (UUID) -> Unit,
) {
    val entry = (entryState as? EntryDetailState.Found)?.entry
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var moodGridSkipped by remember(entry?.id) { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    val titleText = entry?.let { fmt.format(Date(it.timestamp)) } ?: "Entry"

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete entry?") },
            text = { Text("This entry will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    val reframeInFlight = reframeState is ReframeState.Loading
        || reframeState is ReframeState.Streaming

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit entry")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete entry",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (entry?.content?.isNotBlank() == true) {
                Surface(tonalElevation = 3.dp) {
                    FilledTonalButton(
                        onClick = onReframe,
                        enabled = modelLoadState == ModelLoadState.READY && !reframeInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(when {
                            reframeInFlight -> "Reframing…"
                            modelLoadState != ModelLoadState.READY -> "Model loading…"
                            entry?.reframedContent != null -> "Reframe again"
                            else -> "Reframe"
                        })
                    }
                }
            }
        },
    ) { innerPadding ->
        if (entryState is EntryDetailState.Loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (entry != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Mood card — label prominent, coordinates + distortions as supporting text
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            entry.moodLabel.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "v: ${"%.2f".format(entry.valence)}  a: ${"%.2f".format(entry.arousal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entry.cognitiveDistortions.isNotEmpty()) {
                            Text(
                                entry.cognitiveDistortions.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Tagged entity chips — people are tappable (navigate to PersonDetail),
                // places and tags are display-only for this release.
                val hasEntities = tagsData.people.isNotEmpty()
                    || tagsData.places.isNotEmpty()
                    || tagsData.tags.isNotEmpty()
                if (hasEntities) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(tagsData.people) { person ->
                            val name = person.nickname
                                ?: "${person.firstName}${if (person.lastName != null) " ${person.lastName}" else ""}"
                            SuggestionChip(
                                onClick = { onOpenPerson(person.id) },
                                label = { Text("@$name") },
                            )
                        }
                        items(tagsData.places) { place ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text("!${place.name}") },
                            )
                        }
                        items(tagsData.tags) { tag ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text("#${tag.name}") },
                            )
                        }
                    }
                }

                // Original entry text
                val content = entry.content
                if (content != null) {
                    Text(
                        "Original",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    Text(
                        "Mood log — no text recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Persisted reframe (written by Apply)
                entry.reframedContent?.let { reframe ->
                    Text(
                        "Reframe",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Text(
                            reframe,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    // Mood grid — shown once after reframe, until user confirms or skips.
                    // Disappears permanently once userValence is written (entry reloads from DB).
                    if (entry.userValence == null && !moodGridSkipped) {
                        CircumplexGrid(
                            onConfirm = { v, a -> onConfirmMood(v, a) },
                            onSkip = { moodGridSkipped = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (reframeState !is ReframeState.Idle) {
        ReframeBottomSheet(
            reframeState = reframeState,
            onApply = onApplyReframe,
            onDismiss = onDismissReframe,
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

private val previewEntry = JournalEntry(
    id = UUID.randomUUID(),
    timestamp = 1_700_000_000_000L,
    content = "Someone asked if I was well. I said yes. The answer felt dishonest but the truth felt impossible to explain.",
    valence = -0.6f,
    arousal = -0.4f,
    moodLabel = "SOMBER",
    embedding = FloatArray(384),
    cognitiveDistortions = listOf("Emotional Reasoning", "Mind Reading"),
)

@Preview(name = "Detail – original only (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewOriginalOnly() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryDetailContent(
            entryState = EntryDetailState.Found(previewEntry),
            reframeState = ReframeState.Idle,
            modelLoadState = ModelLoadState.READY,
            tagsData = EntryTagsData(),
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onConfirmMood = { _, _ -> },
            onOpenPerson = {},
        )
    }
}

@Preview(name = "Detail – with reframe (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewWithReframe() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryDetailContent(
            entryState = EntryDetailState.Found(previewEntry.copy(
                reframedContent = "I'm assuming I have to reveal all my symptoms to prove I'm not just \"fine.\" But is it really true that I have to justify my answer to someone else?"
            )),
            reframeState = ReframeState.Idle,
            modelLoadState = ModelLoadState.READY,
            tagsData = EntryTagsData(),
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onConfirmMood = { _, _ -> },
            onOpenPerson = {},
        )
    }
}

@Preview(name = "Detail – model loading (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewModelLoading() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryDetailContent(
            entryState = EntryDetailState.Found(previewEntry),
            reframeState = ReframeState.Idle,
            modelLoadState = ModelLoadState.LOADING_SESSION,
            tagsData = EntryTagsData(),
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onConfirmMood = { _, _ -> },
            onOpenPerson = {},
        )
    }
}

@Preview(name = "Detail – mood log only (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewMoodLog() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        EntryDetailContent(
            entryState = EntryDetailState.Found(previewEntry.copy(content = null, cognitiveDistortions = emptyList())),
            reframeState = ReframeState.Idle,
            modelLoadState = ModelLoadState.READY,
            tagsData = EntryTagsData(),
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onConfirmMood = { _, _ -> },
            onOpenPerson = {},
        )
    }
}
