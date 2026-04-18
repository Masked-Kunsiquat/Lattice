package com.github.maskedkunisquat.lattice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

// ── PII highlight helper ──────────────────────────────────────────────────────

private val PERSON_HIGHLIGHT_REGEX = Regex("@[\\p{L}\\p{N}_]+(?=$|\\s|[^\\p{L}\\p{N}_])")
private val PLACE_HIGHLIGHT_REGEX  = Regex("![\\p{L}\\p{N}_]+(?=$|\\s|[^\\p{L}\\p{N}_])")
private val TAG_HIGHLIGHT_REGEX    = Regex("#[\\p{L}\\p{N}_]+")

/**
 * Returns an [AnnotatedString] where @person, !place, and #tag tokens are
 * colored with the supplied colors and rendered at medium weight.
 * If the text contains no tokens the result is equivalent to a plain string.
 */
fun buildHighlightedText(
    text: String,
    personColor: Color,
    placeColor: Color,
    tagColor: Color,
) = buildAnnotatedString {
    append(text)
    fun highlight(regex: Regex, color: Color) {
        regex.findAll(text).forEach { match ->
            addStyle(
                SpanStyle(color = color, fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
    }
    highlight(PERSON_HIGHLIGHT_REGEX, personColor)
    highlight(PLACE_HIGHLIGHT_REGEX, placeColor)
    highlight(TAG_HIGHLIGHT_REGEX, tagColor)
}

// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
            if (searchState.expanded) {
                // ── Search overlay — fully self-contained, avoids M3 SearchBar
                // expanded-state quirks (centering, premature onExpandedChange(false) on
                // keyboard show, silent onSearch). Input stays pinned at the top; results
                // stream below the TabRow.
                SearchOverlay(
                    state         = searchState,
                    onQueryChange = searchViewModel::onQueryChange,
                    onCollapse    = searchViewModel::collapse,
                    onOpenEntry   = onOpenEntry,
                    onOpenPerson  = onOpenPerson,
                )
            } else {
                // ── Collapsed search pill ─────────────────────────────────────
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = "",
                            onQueryChange = {},
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = { if (it) searchViewModel.onExpandedChange(true) },
                            placeholder = { Text("Search entries, people, places, tags") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        )
                    },
                    expanded = false,
                    onExpandedChange = { if (it) searchViewModel.onExpandedChange(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {}

                // ── History list ──────────────────────────────────────────────
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No entries yet\n\nSwitch to the Journal tab to start writing.\nMention @people, tag #topics, and note !places as you go.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(40.dp),
                        )
                    }
                } else {
                    val historyItems = remember(entries) { entries.toHistoryItems() }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        historyItems.forEach { historyItem ->
                            when (historyItem) {
                                is HistoryListItem.DateHeader -> stickyHeader(key = "date_${historyItem.label}") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.background,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = historyItem.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                                is HistoryListItem.EntryItem -> item(key = historyItem.entry.id.toString()) {
                                    EntryCard(
                                        entry = historyItem.entry,
                                        onTap = { onOpenEntry(historyItem.entry.id) },
                                        onDeleteRequest = { pendingDeleteEntry = historyItem.entry },
                                    )
                                }
                            }
                        }
                    }
                }
            } // end else (collapsed)
        }
    }
}

// ── Search overlay ────────────────────────────────────────────────────────────

/**
 * Full-screen search UI shown when the search bar is expanded. Replaces the
 * Material3 SearchBar expanded-state slot to avoid its positioning quirks
 * (input centering, premature onExpandedChange(false) on keyboard events,
 * silent onSearch). The input is pinned at the top; all result categories stream
 * below as a single grouped scrollable list — Entries, People, Places, Tags.
 */
@Composable
private fun SearchOverlay(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
    onOpenEntry: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus the text field when the overlay first appears.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Input row ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyboardController?.hide(); onCollapse() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close search",
                )
            }
            TextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search entries, people, places, tags") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Search button on the keyboard hides the IME but keeps results visible.
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor   = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear query")
                }
            }
        }
        HorizontalDivider()
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ── Grouped results ───────────────────────────────────────────────────
        if (state.query.isBlank()) {
            SearchEmptyHint("Start typing to search")
        } else {
            val onDismiss: () -> Unit = { keyboardController?.hide(); onCollapse() }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Entries section
                item {
                    SearchSectionHeader("Entries")
                }
                if (state.entryResults.isEmpty()) {
                    item {
                        SearchSectionEmptyHint(
                            if (state.isSemanticLoading) "Searching…" else "No entries found"
                        )
                    }
                } else {
                    items(state.entryResults, key = { "entry_${it.id}" }) { entry ->
                        ListItem(
                            overlineContent = {
                                Text(
                                    entry.moodLabel.lowercase(Locale.ROOT)
                                        .replaceFirstChar { it.uppercase(Locale.ROOT) },
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
                            modifier = Modifier.clickable { onDismiss(); onOpenEntry(entry.id) },
                        )
                    }
                }

                // People section
                item { SearchSectionHeader("People") }
                if (state.peopleResults.isEmpty()) {
                    item {
                        SearchSectionEmptyHint(
                            if (state.isLikeLoading) "Searching…" else "No people found"
                        )
                    }
                } else {
                    items(state.peopleResults, key = { "person_${it.id}" }) { person ->
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
                            modifier = Modifier.clickable { onDismiss(); onOpenPerson(person.id) },
                        )
                    }
                }

                // Places section
                item { SearchSectionHeader("Places") }
                if (state.placeResults.isEmpty()) {
                    item {
                        SearchSectionEmptyHint(
                            if (state.isLikeLoading) "Searching…" else "No places found"
                        )
                    }
                } else {
                    items(state.placeResults, key = { "place_${it.place.id}" }) { (place, count) ->
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

                // Tags section
                item { SearchSectionHeader("Tags") }
                if (state.tagResults.isEmpty()) {
                    item {
                        SearchSectionEmptyHint(
                            if (state.isLikeLoading) "Searching…" else "No tags found"
                        )
                    }
                } else {
                    items(state.tagResults, key = { "tag_${it.tag.id}" }) { (tag, count) ->
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
    }
}

// ── Search result helpers ─────────────────────────────────────────────────────

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchSectionEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
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
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = MaterialTheme.colorScheme.secondary
                    val tertiary = MaterialTheme.colorScheme.tertiary
                    val snippet = entry.content ?: "Mood log (no text)"
                    Text(
                        remember(entry.id, entry.content, primary, secondary, tertiary) {
                            buildHighlightedText(snippet, primary, secondary, tertiary)
                        },
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

// ── Date grouping ─────────────────────────────────────────────────────────────

private sealed class HistoryListItem {
    data class DateHeader(val label: String) : HistoryListItem()
    data class EntryItem(val entry: JournalEntry) : HistoryListItem()
}

/** Compact Long key for a calendar day: yyyyMMdd. Avoids Triple allocation. */
private fun Calendar.dateKey(): Long =
    get(Calendar.YEAR) * 10_000L + get(Calendar.MONTH) * 100L + get(Calendar.DAY_OF_MONTH)

/**
 * Transforms an already-sorted (newest-first) entry list into a flat list of
 * [HistoryListItem]s with a [HistoryListItem.DateHeader] injected at each day boundary.
 * Today/Yesterday labels are computed relative to the call-site clock.
 */
private fun List<JournalEntry>.toHistoryItems(): List<HistoryListItem> {
    if (isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val now = System.currentTimeMillis()
    val todayKey = Calendar.getInstance().also { it.timeInMillis = now }.dateKey()
    val yesterdayKey = Calendar.getInstance().also {
        it.timeInMillis = now
        it.add(Calendar.DAY_OF_YEAR, -1)
    }.dateKey()
    val thisYear = Calendar.getInstance().also { it.timeInMillis = now }.get(Calendar.YEAR)
    val fmtShort = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val fmtLong  = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())

    val result   = mutableListOf<HistoryListItem>()
    var lastKey  = -1L
    for (entry in this) {
        cal.timeInMillis = entry.timestamp
        val key = cal.dateKey()
        if (key != lastKey) {
            val label = when (key) {
                todayKey     -> "Today"
                yesterdayKey -> "Yesterday"
                else         -> if (cal.get(Calendar.YEAR) == thisYear)
                    fmtShort.format(Date(entry.timestamp))
                else
                    fmtLong.format(Date(entry.timestamp))
            }
            result += HistoryListItem.DateHeader(label)
            lastKey = key
        }
        result += HistoryListItem.EntryItem(entry)
    }
    return result
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

