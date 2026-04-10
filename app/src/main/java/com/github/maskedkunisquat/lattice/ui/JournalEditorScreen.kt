package com.github.maskedkunisquat.lattice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            onApplyReframe = viewModel::applyReframe,
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
            visible = modelLoadState == ModelLoadState.COPYING_SHARDS
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
                    text = if (modelLoadState == ModelLoadState.COPYING_SHARDS)
                        "Preparing local model…"
                    else
                        "Loading model session…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// Stateless inner composable — all mutable state lives in the ViewModel.
// Previews target this directly with stub state.
@Composable
private fun MentionDropdown(
    mentionState: MentionState,
    onPersonSelected: (Person) -> Unit,
    onPersonCreateNew: (String) -> Unit,
    onTagSelected: (Tag) -> Unit,
    onTagCreateNew: (String) -> Unit,
    onPlaceSelected: (Place) -> Unit,
    onPlaceCreateNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (mentionState) {
        is MentionState.SuggestingPerson -> DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = false),
        ) {
            mentionState.results.forEach { person ->
                DropdownMenuItem(
                    text = { Text(person.nickname ?: person.firstName) },
                    onClick = { onPersonSelected(person) },
                )
            }
            if (mentionState.query.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Create \"@${mentionState.query}\"") },
                    onClick = { onPersonCreateNew(mentionState.query) },
                )
            }
        }
        is MentionState.SuggestingTag -> DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = false),
        ) {
            mentionState.results.forEach { tag ->
                DropdownMenuItem(
                    text = { Text("#${tag.name}") },
                    onClick = { onTagSelected(tag) },
                )
            }
            if (mentionState.query.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Create \"#${mentionState.query}\"") },
                    onClick = { onTagCreateNew(mentionState.query) },
                )
            }
        }
        is MentionState.SuggestingPlace -> DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = false),
        ) {
            mentionState.results.forEach { place ->
                DropdownMenuItem(
                    text = { Text("!${place.name}") },
                    onClick = { onPlaceSelected(place) },
                )
            }
            if (mentionState.query.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Create \"!${mentionState.query}\"") },
                    onClick = { onPlaceCreateNew(mentionState.query) },
                )
            }
        }
        is MentionState.Idle -> Unit
    }
}

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
    onApplyReframe: () -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
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

        // Mood grid
        MoodGrid(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            initialValence = uiState.valence,
            initialArousal = uiState.arousal,
            hasInitialMood = uiState.moodSelected,
            onMoodChanged = onMoodChanged,
        )

        // Privacy-bordered journal text field with mention dropdown
        Box(modifier = Modifier.fillMaxWidth().weight(0.45f)) {
            OutlinedTextField(
                value = uiState.text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxSize(),
                placeholder = { Text("What's on your mind?") },
                visualTransformation = PiiHighlightTransformation(
                    highlightColor = MaterialTheme.colorScheme.tertiary,
                    tagHighlightColor = MaterialTheme.colorScheme.secondary,
                    placeHighlightColor = PlaceGreen,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = borderColor,
                    unfocusedBorderColor = borderColor.copy(alpha = 0.5f),
                ),
            )
            Box(modifier = Modifier.align(Alignment.BottomStart)) {
                MentionDropdown(
                    mentionState = uiState.mentionState,
                    onPersonSelected = onMentionSelected,
                    onPersonCreateNew = onMentionCreateNew,
                    onTagSelected = onTagSelected,
                    onTagCreateNew = onTagCreateNew,
                    onPlaceSelected = onPlaceSelected,
                    onPlaceCreateNew = onPlaceCreateNew,
                    onDismiss = onMentionDismiss,
                )
            }
        }

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
            uiState = EditorUiState(text = "Today I spoke with [PERSON_00000000-0000-0000-0000-000000000001] about the project."),
            modelLoadState = ModelLoadState.READY,
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onReframe = {},
            onApplyReframe = {},
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
            onApplyReframe = {},
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
