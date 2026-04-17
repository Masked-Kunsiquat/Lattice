package com.github.maskedkunisquat.lattice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalHistoryScreen(
    viewModel: JournalHistoryViewModel,
    searchViewModel: SearchHistoryViewModel,
    onOpenEntry: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit = {},
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteEntry by remember { mutableStateOf<JournalEntry?>(null) }

    BackHandler(enabled = searchState.expanded) {
        searchViewModel.collapse()
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchState.query,
                        onQueryChange = { searchViewModel.onQueryChange(it) },
                        onSearch = {},
                        expanded = searchState.expanded,
                        onExpandedChange = { searchViewModel.onExpandedChange(it) },
                        placeholder = { Text("Search entries, people, places, tags") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchState.expanded && searchState.query.isNotEmpty()) {
                                IconButton(onClick = { searchViewModel.onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear query")
                                }
                            }
                        },
                    )
                },
                expanded = searchState.expanded,
                onExpandedChange = { searchViewModel.onExpandedChange(it) },
                modifier = if (searchState.expanded) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                },
            ) {
                // ── Tab row ──────────────────────────────────────────────────
                val tabs = SearchTab.entries
                TabRow(selectedTabIndex = searchState.activeTab.ordinal) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == searchState.activeTab,
                            onClick = { searchViewModel.onTabChange(tab) },
                            text = {
                                Text(tab.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) })
                            },
                        )
                    }
                }

                if (searchState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // ── Tab content ───────────────────────────────────────────────
                when (searchState.activeTab) {
                    SearchTab.ENTRIES -> SearchEntryResults(
                        results = searchState.entryResults,
                        isLoading = searchState.isLoading,
                        query = searchState.query,
                        onOpenEntry = onOpenEntry,
                        onCollapse = { searchViewModel.onExpandedChange(false) },
                    )
                    SearchTab.PEOPLE -> SearchPeopleResults(
                        results = searchState.peopleResults,
                        query = searchState.query,
                        onOpenPerson = onOpenPerson,
                        onCollapse = { searchViewModel.onExpandedChange(false) },
                    )
                    SearchTab.PLACES -> SearchPlaceResults(
                        results = searchState.placeResults,
                        query = searchState.query,
                    )
                    SearchTab.TAGS -> SearchTagResults(
                        results = searchState.tagResults,
                        query = searchState.query,
                    )
                }
            }

            // ── History list (hidden while search overlay is open) ────────────
            if (!searchState.expanded) {
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                        modifier = Modifier.fillMaxSize(),
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
    }
}

// ── Search result sections ─────────────────────────────────────────────────────

@Composable
private fun SearchEntryResults(
    results: List<JournalEntry>,
    isLoading: Boolean,
    query: String,
    onOpenEntry: (UUID) -> Unit,
    onCollapse: () -> Unit,
) {
    when {
        query.isBlank() -> SearchEmptyHint("Start typing to search entries")
        !isLoading && results.isEmpty() -> SearchEmptyHint("No entries found")
        else -> LazyColumn {
            items(results, key = { it.id }) { entry ->
                ListItem(
                    overlineContent = {
                        Text(
                            entry.moodLabel.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(
                            entry.content ?: "Mood log (no text)",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable {
                        onCollapse()
                        onOpenEntry(entry.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchPeopleResults(
    results: List<Person>,
    query: String,
    onOpenPerson: (UUID) -> Unit,
    onCollapse: () -> Unit,
) {
    when {
        query.isBlank() -> SearchEmptyHint("Start typing to search people")
        results.isEmpty() -> SearchEmptyHint("No people found")
        else -> LazyColumn {
            items(results, key = { it.id }) { person ->
                val displayName = person.nickname
                    ?: listOfNotNull(person.firstName, person.lastName).joinToString(" ")
                val vibeColor = when {
                    person.vibeScore > 0.3f -> MaterialTheme.colorScheme.tertiary
                    person.vibeScore < -0.3f -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                ListItem(
                    headlineContent = { Text(displayName) },
                    supportingContent = {
                        Text(
                            person.relationshipType.name.lowercase(Locale.ROOT)
                                .replaceFirstChar { it.uppercase(Locale.ROOT) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(vibeColor, CircleShape),
                        )
                    },
                    modifier = Modifier.clickable {
                        onCollapse()
                        onOpenPerson(person.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchPlaceResults(
    results: List<PlaceResult>,
    query: String,
) {
    when {
        query.isBlank() -> SearchEmptyHint("Start typing to search places")
        results.isEmpty() -> SearchEmptyHint("No places found")
        else -> LazyColumn {
            items(results, key = { it.place.id }) { (place, count) ->
                ListItem(
                    headlineContent = { Text(place.name) },
                    supportingContent = {
                        Text(
                            if (count == 1) "1 entry" else "$count entries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchTagResults(
    results: List<TagResult>,
    query: String,
) {
    when {
        query.isBlank() -> SearchEmptyHint("Start typing to search tags")
        results.isEmpty() -> SearchEmptyHint("No tags found")
        else -> LazyColumn {
            items(results, key = { it.tag.id }) { (tag, count) ->
                ListItem(
                    headlineContent = { Text("#${tag.name}") },
                    supportingContent = {
                        Text(
                            if (count == 1) "1 entry" else "$count entries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchEmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── History entry card ─────────────────────────────────────────────────────────

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
                        "${fmt.format(Date(entry.timestamp))}  ·  ${entry.moodLabel.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }}",
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

@Preview(name = "Person result row (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewSearchPersonRow() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        SearchPeopleResults(
            results = listOf(
                Person(
                    id = UUID.randomUUID(),
                    firstName = "Sherlock",
                    lastName = "Holmes",
                    nickname = null,
                    relationshipType = RelationshipType.FRIEND,
                    vibeScore = 0.7f,
                )
            ),
            query = "holmes",
            onOpenPerson = {},
            onCollapse = {},
        )
    }
}
