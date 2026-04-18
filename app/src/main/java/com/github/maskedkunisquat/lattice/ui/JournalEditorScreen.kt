package com.github.maskedkunisquat.lattice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

private val LocalBlue  = Color(0xFF1976D2)
private val CloudAmber = Color(0xFFFF8F00)
private val PlaceGreen = Color(0xFF2E7D32)

// Public entry point — collects ViewModel state and delegates to JournalEditorContent.
@Composable
fun JournalEditorScreen(
    viewModel: JournalEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val privacyState by viewModel.privacyState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelLoadState by viewModel.modelLoadState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        JournalEditorContent(
            privacyState = privacyState,
            uiState = uiState,
            modelLoadState = modelLoadState,
            onTextChanged = viewModel::onTextChanged,
            onMoodChanged = viewModel::onMoodChanged,
            onSave = viewModel::save,
            onResetSaved = viewModel::resetSaved,
            onReframe = viewModel::requestReframe,
            onApplyReframe = { viewModel.applyReframe() },
            onDismissReframe = viewModel::dismissReframe,
            onMentionSelected = viewModel::onMentionSelected,
            onMentionCreateNew = viewModel::onMentionCreateNew,
            onTagSelected = viewModel::onTagSelected,
            onTagCreateNew = viewModel::onTagCreateNew,
            onPlaceSelected = viewModel::onPlaceSelected,
            onPlaceCreateNew = viewModel::onPlaceCreateNew,
            onMentionDismiss = viewModel::onMentionDismiss,
        )

        AnimatedVisibility(
            visible = modelLoadState == ModelLoadState.COPYING_MODEL
                   || modelLoadState == ModelLoadState.LOADING_SESSION,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = when (modelLoadState) {
                        ModelLoadState.COPYING_MODEL ->
                            "Preparing local model…"
                        else ->
                            "Loading model session… (first launch may take a few minutes)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "Running entirely on-device — no network needed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * Docked suggestion strip — a horizontal scrollable row of chips that appears above the
 * soft keyboard whenever a mention trigger (`@`, `#`, `!`) is active.
 *
 * Each result is a [SuggestionChip]; the trailing "Add" action uses [AssistChip] to
 * distinguish it visually as a create operation rather than a selection.
 */
@Composable
private fun SuggestionStrip(
    mentionState: MentionState,
    onPersonSelected: (Person) -> Unit,
    onPersonCreateNew: (String) -> Unit,
    onTagSelected: (Tag) -> Unit,
    onTagCreateNew: (String) -> Unit,
    onPlaceSelected: (Place) -> Unit,
    onPlaceCreateNew: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = mentionState !is MentionState.Idle,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (val state = mentionState) {
                    is MentionState.SuggestingPerson -> {
                        items(state.results, key = { it.id }) { person ->
                            val displayName = person.nickname
                                ?: if (person.lastName != null) "${person.firstName} ${person.lastName}"
                                else person.firstName
                            SuggestionChip(
                                onClick = { onPersonSelected(person) },
                                label = { Text("@$displayName") },
                                icon = {
                                    Icon(
                                        Icons.Filled.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                        if (state.query.isNotBlank()) {
                            item {
                                AssistChip(
                                    onClick = { onPersonCreateNew(state.query) },
                                    label = { Text("Add @${state.query}") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.PersonAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    is MentionState.SuggestingTag -> {
                        items(state.results, key = { it.id }) { tag ->
                            SuggestionChip(
                                onClick = { onTagSelected(tag) },
                                label = { Text("#${tag.name}") },
                            )
                        }
                        if (state.query.isNotBlank()) {
                            item {
                                AssistChip(
                                    onClick = { onTagCreateNew(state.query) },
                                    label = { Text("Add #${state.query}") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    is MentionState.SuggestingPlace -> {
                        items(state.results, key = { it.id }) { place ->
                            SuggestionChip(
                                onClick = { onPlaceSelected(place) },
                                label = { Text("!${place.name}") },
                                icon = {
                                    Icon(
                                        Icons.Filled.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                        if (state.query.isNotBlank()) {
                            item {
                                AssistChip(
                                    onClick = { onPlaceCreateNew(state.query) },
                                    label = { Text("Add !${state.query}") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.AddLocation,
                                            contentDescription = null,
                                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    is MentionState.Idle -> Unit
                }
            }
        }
    }
}

// Stateless inner composable — all mutable state lives in the ViewModel.
// Previews target this directly with stub state.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalEditorContent(
    privacyState: PrivacyLevel,
    uiState: EditorUiState,
    modelLoadState: ModelLoadState,
    onTextChanged: (String) -> Unit,
    onMoodChanged: (Float, Float, MoodLabel) -> Unit,
    onSave: () -> Unit,
    onResetSaved: () -> Unit,
    onReframe: () -> Unit,
    onApplyReframe: (String) -> Unit,
    onDismissReframe: () -> Unit,
    onMentionSelected: (Person) -> Unit,
    onMentionCreateNew: (String) -> Unit,
    onTagSelected: (Tag) -> Unit,
    onTagCreateNew: (String) -> Unit,
    onPlaceSelected: (Place) -> Unit,
    onPlaceCreateNew: (String) -> Unit,
    onMentionDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = when (privacyState) {
            is PrivacyLevel.LocalOnly -> LocalBlue
            is PrivacyLevel.CloudTransit -> CloudAmber
        },
        animationSpec = tween(durationMillis = 600),
        label = "privacyBorder",
    )

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onResetSaved()
    }

    val privacyLabel = when (val ps = privacyState) {
        is PrivacyLevel.LocalOnly -> "Processing Locally"
        is PrivacyLevel.CloudTransit -> "Cloud: ${ps.providerName}"
    }

    // Collapse the mood grid when the keyboard is open so the text field can expand.
    val imeVisible = WindowInsets.isImeVisible

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Privacy status pill
        Surface(
            color = borderColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = privacyLabel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
            )
        }

        // Mood grid — hidden when the keyboard is open so the text field gets full height
        AnimatedVisibility(
            visible = !imeVisible,
            enter = expandVertically(),
            exit = shrinkVertically(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MoodGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                initialValence = uiState.valence,
                initialArousal = uiState.arousal,
                hasInitialMood = uiState.moodSelected,
                onMoodChanged = onMoodChanged,
            )
        }

        // Text field — weight(1f) fills all remaining space; expands to full screen height
        // when the mood grid is hidden and the keyboard is active
        OutlinedTextField(
            value = uiState.text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onFocusChanged { focus -> if (!focus.isFocused) onMentionDismiss() },
            placeholder = { Text("What's on your mind?") },
            supportingText = { Text("@name · #tag · !place") },
            visualTransformation = PiiHighlightTransformation(
                highlightColor = MaterialTheme.colorScheme.tertiary,
                tagHighlightColor = MaterialTheme.colorScheme.secondary,
                placeHighlightColor = PlaceGreen,
                resolvedPersonNames = uiState.resolvedPersons.keys,
                resolvedPlaceNames = uiState.resolvedPlaces.keys,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor.copy(alpha = 0.5f),
            ),
        )

        // Suggestion strip — docked above the soft keyboard via imePadding() on the Column.
        // Animates in/out as mention triggers become active or resolve.
        SuggestionStrip(
            mentionState = uiState.mentionState,
            onPersonSelected = onMentionSelected,
            onPersonCreateNew = onMentionCreateNew,
            onTagSelected = onTagSelected,
            onTagCreateNew = onTagCreateNew,
            onPlaceSelected = onPlaceSelected,
            onPlaceCreateNew = onPlaceCreateNew,
            modifier = Modifier.fillMaxWidth(),
        )

        // Save error (non-reframe failures)
        uiState.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val reframeInFlight = uiState.reframeState is ReframeState.Loading
            || uiState.reframeState is ReframeState.Streaming
        val canReframe = uiState.text.isNotBlank()
            && !reframeInFlight
            && modelLoadState == ModelLoadState.READY
        val canSave = uiState.moodSelected && !reframeInFlight

        Row(modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onReframe,
                enabled = canReframe,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (reframeInFlight) "Reframing…"
                    else if (modelLoadState != ModelLoadState.READY) "Model loading…"
                    else "Reframe"
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }
    }

    // Reframe bottom sheet — shown whenever there is active reframe state
    if (uiState.reframeState !is ReframeState.Idle) {
        ReframeBottomSheet(
            reframeState = uiState.reframeState,
            onApply = onApplyReframe,
            onDismiss = onDismissReframe,
        )
    }
}

@Preview(name = "Editor – Local (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorLocalPreview() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.LocalOnly,
            uiState = EditorUiState(text = "Today I spoke with @Watson about the project."),
            modelLoadState = ModelLoadState.READY,
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onMentionSelected = {},
            onMentionCreateNew = {},
            onTagSelected = {},
            onTagCreateNew = {},
            onPlaceSelected = {},
            onPlaceCreateNew = {},
            onMentionDismiss = {},
        )
    }
}

@Preview(name = "Editor – Suggestion strip (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorStripPreview() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.LocalOnly,
            uiState = EditorUiState(
                text = "Spoke with @Wat",
                mentionState = MentionState.SuggestingPerson(
                    query = "Wat",
                    results = emptyList(),
                ),
            ),
            modelLoadState = ModelLoadState.READY,
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onMentionSelected = {},
            onMentionCreateNew = {},
            onTagSelected = {},
            onTagCreateNew = {},
            onPlaceSelected = {},
            onPlaceCreateNew = {},
            onMentionDismiss = {},
        )
    }
}

@Preview(name = "Editor – Cloud transit (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorCloudPreview() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.CloudTransit(
                providerName = "cloud_claude",
                sinceTimestamp = 0L,
            ),
            uiState = EditorUiState(text = "Feeling anxious about the presentation.", moodSelected = true),
            modelLoadState = ModelLoadState.LOADING_SESSION,
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onReframe = {},
            onApplyReframe = { _ -> },
            onDismissReframe = {},
            onMentionSelected = {},
            onMentionCreateNew = {},
            onTagSelected = {},
            onTagCreateNew = {},
            onPlaceSelected = {},
            onPlaceCreateNew = {},
            onMentionDismiss = {},
        )
    }
}
